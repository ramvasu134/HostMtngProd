import makeWASocket, { DisconnectReason, fetchLatestBaileysVersion } from '@whiskeysockets/baileys';
import { Boom } from '@hapi/boom';
import pino from 'pino';
import QRCode from 'qrcode';
import pg from 'pg';
import { usePostgresAuthState, clearAuthState } from './pgAuthState.js';

const { Pool } = pg;
const logger = pino({ level: process.env.BAILEYS_LOG_LEVEL || 'warn' });

let sock = null;
let latestQr = null; // data URL (base64 PNG) of the most recently issued QR code
let connectionState = 'starting'; // starting | qr | connecting | open | closed
let reconnectTimer = null;
let unlinking = false; // guards against the 'close' handler auto-reconnecting mid-unlink

function buildPool() {
    // Reuses the SAME Postgres database the Java app already has provisioned
    // (Render's free Postgres) — no extra database, no extra cost.
    if (process.env.DATABASE_URL) {
        return new Pool({ connectionString: process.env.DATABASE_URL, ssl: { rejectUnauthorized: false } });
    }
    return new Pool({
        host: process.env.DB_HOST,
        port: Number(process.env.DB_PORT || 5432),
        database: process.env.DB_NAME,
        user: process.env.DB_USER,
        password: process.env.DB_PASSWORD,
        ssl: { rejectUnauthorized: false },
    });
}

const pool = buildPool();

export async function startBaileys() {
    const sessionId = process.env.BAILEYS_SESSION_ID || 'default';
    const { state, saveCreds } = await usePostgresAuthState(pool, sessionId);
    const { version } = await fetchLatestBaileysVersion();

    sock = makeWASocket({
        version,
        auth: state,
        logger,
        printQRInTerminal: false,
    });

    sock.ev.on('creds.update', saveCreds);

    sock.ev.on('connection.update', async (update) => {
        const { connection, qr, lastDisconnect } = update;

        if (qr) {
            latestQr = await QRCode.toDataURL(qr);
            connectionState = 'qr';
            logger.info('[Baileys] New QR issued — open GET /qr on this service to scan it.');
        }

        if (connection === 'open') {
            connectionState = 'open';
            latestQr = null;
            logger.info(`[Baileys] WhatsApp linked and connected. Outbound audio will go to ${getLinkedNumber() || '(unknown)'} (self-chat).`);
        }

        if (connection === 'close') {
            connectionState = 'closed';
            const statusCode = lastDisconnect?.error ? new Boom(lastDisconnect.error).output?.statusCode : null;
            const loggedOut = statusCode === DisconnectReason.loggedOut;

            if (unlinking) {
                // unlinkSession() below is already handling teardown + restart.
                return;
            }

            if (loggedOut) {
                // The stored creds are no longer valid — wipe them and restart
                // so a fresh QR code is issued automatically instead of getting
                // stuck in "closed" state forever until someone manually unlinks.
                logger.warn('[Baileys] Session logged out — clearing stale credentials and restarting for a new QR code.');
                const sessionId = process.env.BAILEYS_SESSION_ID || 'default';
                clearAuthState(pool, sessionId)
                    .then(() => {
                        sock = null;
                        latestQr = null;
                        connectionState = 'starting';
                        return startBaileys();
                    })
                    .catch((e) => logger.error({ err: e }, '[Baileys] Auto-recovery after logout failed'));
                return;
            }

            logger.warn(`[Baileys] Connection closed (code=${statusCode ?? 'unknown'}). Reconnecting in 3s…`);
            clearTimeout(reconnectTimer);
            reconnectTimer = setTimeout(() => {
                startBaileys().catch((e) => logger.error({ err: e }, '[Baileys] Reconnect attempt failed'));
            }, 3000);
        }
    });
}

/**
 * The phone number of whichever WhatsApp account most recently scanned the
 * QR code (E.164-ish, e.g. "+919876543210"). This is intentionally the
 * ONLY destination outbound audio ever goes to — see sendAudioMessage().
 * Re-scanning the QR with a different phone replaces this value, and all
 * subsequent recordings go to the new number instead.
 */
function getLinkedNumber() {
    const rawId = sock?.user?.id;
    if (!rawId) return null;
    const digits = String(rawId).split(':')[0].replace(/[^0-9]/g, '');
    return digits ? `+${digits}` : null;
}

function getSelfJid() {
    const rawId = sock?.user?.id;
    if (!rawId) return null;
    const digits = String(rawId).split(':')[0].replace(/[^0-9]/g, '');
    return digits ? `${digits}@s.whatsapp.net` : null;
}

export function getStatus() {
    return {
        connectionState,
        hasQr: !!latestQr,
        linkedNumber: connectionState === 'open' ? getLinkedNumber() : null,
    };
}

export function getQrDataUrl() {
    return latestQr;
}

/**
 * Manually de-links the currently-connected WhatsApp number: logs the
 * session out on WhatsApp's side (removes it from the phone's Linked
 * Devices list, same as unlinking from the phone), wipes the stored
 * credentials in Postgres, and immediately restarts so a fresh QR code
 * is issued for the next scan. Safe to call even if nothing is linked
 * yet — it just clears any half-finished session and restarts.
 */
export async function unlinkSession() {
    unlinking = true;
    try {
        clearTimeout(reconnectTimer);
        if (sock) {
            try {
                await sock.logout();
            } catch (e) {
                logger.warn({ err: e }, '[Baileys] logout() during unlink failed (continuing anyway)');
            }
        }
        const sessionId = process.env.BAILEYS_SESSION_ID || 'default';
        await clearAuthState(pool, sessionId);
        sock = null;
        latestQr = null;
        connectionState = 'starting';
        logger.info('[Baileys] Unlinked — stored session cleared, restarting for a fresh QR code.');
    } finally {
        unlinking = false;
    }
    await startBaileys();
}

/**
 * Sends an audio file as a WhatsApp message.
 *
 * IMPORTANT: this ALWAYS delivers to the currently-linked WhatsApp account's
 * own chat (WhatsApp's "Message Yourself" feature) — i.e. whichever number
 * scanned the QR code. There is deliberately no per-call destination
 * override: this service has exactly one active recipient at a time, which
 * only changes when someone re-scans the QR with a different phone.
 *
 * @param {Buffer} audioBuffer Raw audio bytes already downloaded server-side
 * @param {string} mimetype e.g. "audio/mp4", "audio/ogg; codecs=opus"
 * @param {string} [caption] Optional follow-up text message (audio messages
 *                            in WhatsApp don't support a caption directly)
 * @returns {Promise<string>} the E.164-ish number the message was sent to
 */
export async function sendAudioMessage(audioBuffer, mimetype, caption) {
    if (!sock || connectionState !== 'open') {
        throw new Error('WhatsApp is not connected yet. Link a number first (see /qr-data).');
    }
    const jid = getSelfJid();
    if (!jid) {
        throw new Error('Could not resolve the linked WhatsApp number.');
    }
    await sock.sendMessage(jid, {
        audio: audioBuffer,
        mimetype: mimetype || 'audio/mp4',
        ptt: false, // false = plain audio file attachment, not a voice-note bubble
    });
    if (caption) {
        await sock.sendMessage(jid, { text: caption });
    }
    return getLinkedNumber();
}
