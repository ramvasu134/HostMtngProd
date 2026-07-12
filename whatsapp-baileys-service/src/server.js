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
 * One-time linking page. Open this in a browser, scan with the WhatsApp app
 * (Settings -> Linked Devices -> Link a Device) using the number you want
 * outbound audio clips to be sent FROM. The session is then persisted in
 * Postgres, so this only needs to be done again if the phone unlinks it.
 */
app.get('/qr', (_req, res) => {
    const status = getStatus();
    if (status.connectionState === 'open') {
        return res.send('<h2>&#9989; WhatsApp is already linked and connected.</h2>');
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
            <p><small>This page auto-refreshes every 5 seconds until linked.</small></p>
            <script>setTimeout(function () { window.location.reload(); }, 5000);</script>
        </body></html>
    `);
});

/**
 * POST /send-audio
 * Body: { phoneNumber: "+919876543210", audioUrl: "https://...m4a", caption?: "..." }
 * Header: x-notification-token: <shared secret, matches NOTIFICATION_INTERNAL_TOKEN>
 *
 * Downloads the audio from the given public URL (the Java app's existing
 * public recording endpoint) and relays it as a WhatsApp audio message via
 * the linked Baileys session.
 */
app.post('/send-audio', async (req, res) => {
    const requestToken = req.header('x-notification-token');
    const expectedToken = process.env.NOTIFICATION_INTERNAL_TOKEN;
    if (expectedToken && requestToken !== expectedToken) {
        return res.status(401).json({ success: false, message: 'Unauthorized notification trigger.' });
    }

    const { phoneNumber, audioUrl, caption } = req.body || {};
    if (!phoneNumber || !audioUrl) {
        return res.status(400).json({ success: false, message: 'phoneNumber and audioUrl are required.' });
    }

    const status = getStatus();
    if (status.connectionState !== 'open') {
        return res.status(503).json({
            success: false,
            message: 'WhatsApp is not linked yet. Open GET /qr on this service to link a number.',
        });
    }

    try {
        const response = await axios.get(audioUrl, { responseType: 'arraybuffer', timeout: 20000 });
        const mimetype = response.headers['content-type'] || 'audio/mp4';
        const audioBuffer = Buffer.from(response.data);

        await sendAudioMessage(phoneNumber, audioBuffer, mimetype, caption);

        return res.status(200).json({ success: true, message: 'Audio sent via WhatsApp (Baileys).' });
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
