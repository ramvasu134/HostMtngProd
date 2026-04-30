# Notification Service (Node.js + Express)

This service sends recording URLs through WhatsApp using Twilio.

## Quick setup

1. Copy `.env.example` to `.env`
2. Fill in your Twilio credentials
3. Install packages
4. Start the service

```powershell
cd notification-service
npm install
npm start
```

## Render deployment

This folder is already wired in the root `render.yaml` as a dedicated Render web service:

- Service name: `notification-service`
- Runtime: Node
- Port: `3000`
- Health check: `GET /health`

Set these env vars in Render (or via Blueprint secrets):

- `TWILIO_ACCOUNT_SID`
- `TWILIO_AUTH_TOKEN`
- `TWILIO_WHATSAPP_PHONE_NUMBER`
- `NOTIFICATION_INTERNAL_TOKEN` (auto-generated in `render.yaml`)

## Sample request

```powershell
curl -X POST http://localhost:3000/notify `
  -H "Content-Type: application/json" `
  -H "x-notification-token: your_internal_token" `
  -d "{\"phoneNumber\":\"whatsapp:+919999999999\",\"recordingUrl\":\"https://your-cdn.com/recording.mp4\"}"
```

## Behavior

- Returns `202 Accepted` immediately
- Sends WhatsApp in background
- Retries up to 3 times if Twilio fails
- Logs errors without crashing process
- Includes optional token-based route protection (`x-notification-token`)
