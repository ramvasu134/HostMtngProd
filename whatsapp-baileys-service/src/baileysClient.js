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
            logger.info('[Baileys] WhatsApp linked and connected.');
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

export function getStatus() {
    return { connectionState, hasQr: !!latestQr };
}

export function getQrDataUrl() {
    return latestQr;
}

function toJid(phoneNumber) {
    const digits = String(phoneNumber).replace(/[^0-9]/g, '');
    return `${digits}@s.whatsapp.net`;
}

/**
 * Sends an audio file as a WhatsApp message.
 * @param {string} phoneNumber E.164-ish number, e.g. "+919876543210"
 * @param {Buffer} audioBuffer Raw audio bytes already downloaded server-side
 * @param {string} mimetype e.g. "audio/mp4", "audio/ogg; codecs=opus"
 * @param {string} [caption] Optional follow-up text message (audio messages
 *                            in WhatsApp don't support a caption directly)
 */
export async function sendAudioMessage(phoneNumber, audioBuffer, mimetype, caption) {
    if (!sock || connectionState !== 'open') {
        throw new Error('WhatsApp is not connected yet. Open GET /qr on this service to link a number.');
    }
    const jid = toJid(phoneNumber);
    await sock.sendMessage(jid, {
        audio: audioBuffer,
        mimetype: mimetype || 'audio/mp4',
        ptt: false, // false = plain audio file attachment, not a voice-note bubble
    });
    if (caption) {
        await sock.sendMessage(jid, { text: caption });
    }
}
