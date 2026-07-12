import 'dotenv/config';
import express from 'express';
import axios from 'axios';
import { startBaileys, getStatus, getQrDataUrl, sendAudioMessage } from './baileysClient.js';

const app = express();
app.use(express.json());

app.get('/health', (_req, res) => {
    res.status(200).json({ status: 'ok', service: 'whatsapp-baileys-service', ...getStatus() });
});

app.get('/status', (_req, res) => {
    res.status(200).json(getStatus());
});

/**
 * GET /qr-data — server-to-server JSON endpoint, protected by the same
 * shared secret as /send-audio. This is what the Java app's teacher-only
 * dashboard endpoint calls to render the QR inline for the logged-in
 * teacher; it is NOT meant to be opened directly in a browser.
 */
app.get('/qr-data', (req, res) => {
    const requestToken = req.header('x-notification-token');
    const expectedToken = process.env.NOTIFICATION_INTERNAL_TOKEN;
    if (expectedToken && requestToken !== expectedToken) {
        return res.status(401).json({ success: false, message: 'Unauthorized.' });
    }
    const status = getStatus();
    return res.status(200).json({
        connectionState: status.connectionState,
        linkedNumber: status.linkedNumber,
        qrDataUrl: getQrDataUrl(),
    });
});

/**
 * Human-facing linking page — kept for manual/debug use only. The intended
 * flow is the teacher dashboard (which proxies /qr-data server-to-server
 * and never exposes this URL or its token to students). Since this raw URL
 * could still be guessed/discovered directly, it also requires the shared
 * secret as a query param: /qr?token=...
 */
app.get('/qr', (req, res) => {
    const expectedToken = process.env.NOTIFICATION_INTERNAL_TOKEN;
    if (expectedToken && req.query.token !== expectedToken) {
        return res.status(401).send('<h2>&#128274; Unauthorized. This page requires a valid ?token=.</h2>');
    }
    const status = getStatus();
    if (status.connectionState === 'open') {
        return res.send(`<h2>&#9989; WhatsApp is linked and connected.</h2><p>Outbound audio goes to: <b>${status.linkedNumber || '(unknown)'}</b></p>`);
    }
    const dataUrl = getQrDataUrl();
    if (!dataUrl) {
        res.set('Refresh', '3');
        return res.send('<h2>Waiting for a QR code&hellip; this page refreshes automatically.</h2>');
    }
    res.send(`
        <html><body style="text-align:center;font-family:sans-serif;padding:40px;">
            <h2>Scan this QR code with WhatsApp</h2>
            <p>WhatsApp app &rarr; Settings &rarr; Linked Devices &rarr; Link a Device</p>
            <img src="${dataUrl}" alt="WhatsApp QR code" style="width:300px;height:300px;" />
            <p><small>This page auto-refreshes every 5 seconds until linked.<br>
            Whichever number scans this QR becomes the ONLY number outbound audio is sent to.</small></p>
            <script>setTimeout(function () { window.location.reload(); }, 5000);</script>
        </body></html>
    `);
});

/**
 * POST /send-audio
 * Body: { audioUrl: "https://...m4a", caption?: "..." }
 * Header: x-notification-token: <shared secret, matches NOTIFICATION_INTERNAL_TOKEN>
 *
 * Downloads the audio from the given public URL (the Java app's existing
 * public recording endpoint) and relays it as a WhatsApp audio message to
 * the CURRENTLY LINKED number only (see sendAudioMessage doc). Any
 * "phoneNumber" field in the body is intentionally ignored — this service
 * has exactly one active recipient at a time, determined solely by which
 * phone most recently scanned the QR code.
 */
app.post('/send-audio', async (req, res) => {
    const requestToken = req.header('x-notification-token');
    const expectedToken = process.env.NOTIFICATION_INTERNAL_TOKEN;
    if (expectedToken && requestToken !== expectedToken) {
        return res.status(401).json({ success: false, message: 'Unauthorized notification trigger.' });
    }

    const { audioUrl, caption } = req.body || {};
    if (!audioUrl) {
        return res.status(400).json({ success: false, message: 'audioUrl is required.' });
    }

    const status = getStatus();
    if (status.connectionState !== 'open') {
        return res.status(503).json({
            success: false,
            message: 'WhatsApp is not linked yet. Link a number first (see /qr-data).',
        });
    }

    try {
        const response = await axios.get(audioUrl, { responseType: 'arraybuffer', timeout: 20000 });
        const mimetype = response.headers['content-type'] || 'audio/mp4';
        const audioBuffer = Buffer.from(response.data);

        const sentTo = await sendAudioMessage(audioBuffer, mimetype, caption);

        return res.status(200).json({ success: true, message: 'Audio sent via WhatsApp (Baileys).', sentTo });
    } catch (err) {
        console.error('[whatsapp-baileys-service] /send-audio failed:', err.message);
        return res.status(500).json({ success: false, message: err.message });
    }
});

const port = Number(process.env.PORT || 3100);
app.listen(port, () => {
    console.log(`whatsapp-baileys-service listening on port ${port}`);
    startBaileys().catch((e) => console.error('[Baileys] startup failed:', e));
});
