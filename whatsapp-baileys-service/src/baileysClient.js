import makeWASocket, { DisconnectReason, fetchLatestBaileysVersion } from '@whiskeysockets/baileys';
import { Boom } from '@hapi/boom';
import pino from 'pino';
import QRCode from 'qrcode';
import pg from 'pg';
import { usePostgresAuthState } from './pgAuthState.js';

const { Pool } = pg;
const logger = pino({ level: process.env.BAILEYS_LOG_LEVEL || 'warn' });

let sock = null;
let latestQr = null; // data URL (base64 PNG) of the most recently issued QR code
let connectionState = 'starting'; // starting | qr | connecting | open | closed
let reconnectTimer = null;

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

            if (loggedOut) {
                logger.error('[Baileys] Session was logged out from the phone — re-scan the QR code at GET /qr to relink.');
                return; // Do NOT auto-reconnect; the stored creds are no longer valid.
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
