require("dotenv").config();
const express = require("express");
const NotificationService = require("./NotificationService");

const app = express();
app.use(express.json());

const notificationService = new NotificationService({
  accountSid: process.env.TWILIO_ACCOUNT_SID,
  apiKey: process.env.TWILIO_AUTH_TOKEN,
  fromPhoneNumber: process.env.TWILIO_WHATSAPP_FROM,
  maxAttempts: Number(process.env.NOTIFICATION_MAX_ATTEMPTS || 3),
  retryDelayMs: Number(process.env.NOTIFICATION_RETRY_DELAY_MS || 2000),
});

/**
 * Demo route for recording completion.
 *
 * Important behavior:
 * - The route responds immediately.
 * - WhatsApp sending is triggered in background (non-blocking).
 */
app.post("/recordings/complete", (req, res) => {
  const { phoneNumber, recordingUrl } = req.body;

  if (!phoneNumber || !recordingUrl) {
    return res.status(400).json({
      success: false,
      message: "phoneNumber and recordingUrl are required.",
    });
  }

  notificationService.sendRecordingNotification(phoneNumber, recordingUrl);

  return res.status(202).json({
    success: true,
    message: "Recording completion accepted. WhatsApp notification is processing.",
  });
});

const port = Number(process.env.PORT || 4000);
app.listen(port, () => {
  console.log(`WhatsApp notifier service listening on port ${port}`);
});
