# WhatsApp Recording Notifier (Standalone Node Service)

This service sends a WhatsApp message when a meeting recording is ready.

## Why this service exists

- It is standalone from the Spring Boot app.
- It responds quickly to API users.
- It sends WhatsApp notifications in the background with retry safety.

## Setup

1. Copy `.env.example` to `.env`.
2. Fill Twilio credentials in `.env`.
3. Install dependencies and run:

```bash
npm install
npm start
```

## Example API request

```bash
curl -X POST http://localhost:4000/recordings/complete \
  -H "Content-Type: application/json" \
  -d "{\"phoneNumber\":\"whatsapp:+919999999999\",\"recordingUrl\":\"https://example.com/recording.mp4\"}"
```

## Response behavior

- API returns `202 Accepted` immediately.
- WhatsApp send continues asynchronously in the background.
- Failures are retried up to 3 times (configurable).
- Errors are logged, and the process does not crash.
