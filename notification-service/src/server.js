require("dotenv").config();
const express = require("express");
const NotificationService = require("./NotificationService");

const app = express();
app.use(express.json());

const notificationService = new NotificationService({
  accountSid: process.env.TWILIO_ACCOUNT_SID,
  apiKey: process.env.TWILIO_AUTH_TOKEN,
  phoneNumber: process.env.TWILIO_WHATSAPP_PHONE_NUMBER,
  maxRetries: Number(process.env.NOTIFICATION_MAX_RETRIES || 3),
  retryDelayMs: Number(process.env.NOTIFICATION_RETRY_DELAY_MS || 2000),
});

app.get("/health", (_req, res) => {
  return res.status(200).json({
    status: "ok",
    service: "notification-service",
  });
});

/**
 * POST /notify
 * Non-blocking route: returns immediately, background send continues.
 */
app.post("/notify", (req, res) => {
  const requestAuthToken = req.header("x-notification-token");
  const expectedAuthToken = process.env.NOTIFICATION_INTERNAL_TOKEN;
  if (expectedAuthToken && requestAuthToken !== expectedAuthToken) {
    return res.status(401).json({
      success: false,
      message: "Unauthorized notification trigger.",
    });
  }

  const { phoneNumber, recordingUrl } = req.body;

  if (!phoneNumber || !recordingUrl) {
    return res.status(400).json({
      success: false,
      message: "phoneNumber and recordingUrl are required.",
    });
  }

  // Intentionally no await: this keeps API response instant.
  notificationService.sendWhatsApp(phoneNumber, recordingUrl);

  return res.status(202).json({
    success: true,
    message: "Notification accepted and processing in background.",
  });
});

const port = Number(process.env.PORT || 3000);
app.listen(port, () => {
  console.log(`Notification service is running on port ${port}`);
});
