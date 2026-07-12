# whatsapp-baileys-service (OPTIONAL — free/unofficial WhatsApp sender)

Standalone Node.js microservice that sends dynamically-generated audio files
to WhatsApp numbers using [Baileys](https://github.com/WhiskeySockets/Baileys)
— an open-source implementation of the WhatsApp Web protocol. No Meta Cloud
API fees, no Twilio per-message fees, no headless-browser/Puppeteer overhead.

This is **completely isolated** from the existing Twilio-based
`notification-service` and from the main Java app's Twilio/CallMeBot flow.
It does nothing until you deploy it **and** explicitly turn it on in the
Java app's config. If you never do either, nothing about the app's current
behavior changes.

## Who can link/see this, and where audio goes (important)

- **Teacher-only.** The QR-linking UI lives exclusively inside the teacher
  dashboard's WhatsApp settings (a page already restricted to the `HOST`
  role at the URL level), and its data comes from a
  `@PreAuthorize("hasRole('HOST')")` Spring endpoint. **Students have no
  page, button, link, or API that can ever show them a QR code.**
- **Single active recipient — self-chat.** There is no per-teacher "typed
  number" for this provider. Whichever phone scans the QR becomes both the
  bot's identity *and* its only destination: every outbound audio clip is
  sent to that same number via WhatsApp's "Message Yourself" feature.
  `phoneNumber` fields sent to `/send-audio` are ignored on purpose.
- **Re-linking replaces it.** If a different phone scans the QR later
  (e.g. another teacher, or the same teacher with a new number), that
  becomes the new sole recipient for all future recordings — the old
  number stops receiving anything.

## Does it actually work? (honest analysis)

**Yes, technically it works** — Baileys is actively maintained, doesn't need
a real browser (unlike `whatsapp-web.js`), and can send audio messages with
a few lines of code. But there are real trade-offs you should accept going
in:

| Concern | Reality |
|---|---|
| **Cost** | $0. No per-message fee, ever — you're just using your own WhatsApp account over its Web protocol. |
| **ToS risk** | Using WhatsApp Web automation outside the official Business API violates Meta's Terms of Service. The linked phone number *can* be banned, especially with high volume or spam reports. Use a spare/secondary number, not your primary one. |
| **Session persistence** | Render's free/starter disks are **ephemeral** — every redeploy wipes local files. This service works around that by persisting the Baileys login session (creds + signal keys) in the **same Postgres database** the Java app already uses, so you only scan the QR code once, not on every deploy. |
| **Uptime on Render free tier** | Free web services spin down after ~15 minutes idle and cold-start on the next request. Baileys keeps a persistent WebSocket to WhatsApp; if the container spins down, that socket dies and reconnects (using the saved session, no re-scan) on the next request — expect an occasional delay of anywhere from a few seconds to ~1 minute for the *first* message after idle time. A free uptime pinger (e.g. UptimeRobot hitting `/health` every 10 min) keeps it warm if you want faster delivery. |
| **Reliability vs Twilio** | Twilio gives you real delivery receipts and a stable, supported API. Baileys can disconnect unexpectedly (WhatsApp app updates, phone offline, manual unlink, rate-limiting) and requires re-scanning the QR code if the phone explicitly logs the linked device out. |

**Bottom line:** it's a legitimate zero-cost path to *try*, but treat it as
a best-effort secondary channel, not a guaranteed-delivery primary one — which
is exactly how it's wired into the Java app (see below).

## How it's wired into the existing app (non-breaking)

In `WhatsAppNotificationService.java`, Baileys is an **additional, off-by-default**
step that only runs first when explicitly enabled:

```
app.whatsapp.baileys.enabled=false   (default — zero behavior change)
```

- **Disabled (default):** dispatch flow is byte-for-byte identical to before
  (Twilio → CallMeBot fallback, exactly as today).
- **Enabled:** dispatch tries Baileys first; on *any* failure (not linked,
  disconnected, send error, service unreachable) it automatically falls
  through to the existing Twilio → CallMeBot chain — so worst case is
  identical to what already happens today.

This means turning it on can only ever *add* a free delivery attempt, never
remove or weaken the existing paths.

## Setup

### 1. Deploy this service

It's a normal Node/Express app — deploy it anywhere free that runs
long-lived Node processes, e.g. as its own Render **Web Service** (Free
plan) pointed at this `whatsapp-baileys-service/` subfolder, using the
included `Dockerfile`.

Required env vars (see `.env.example`):
- `DB_HOST` / `DB_PORT` / `DB_NAME` / `DB_USER` / `DB_PASSWORD` (or `DATABASE_URL`) — point at the **same** Postgres database the Java app uses (reuse the values already configured there; no new database needed).
- `NOTIFICATION_INTERNAL_TOKEN` — a random shared secret.

### 2. Link a WhatsApp number

**Recommended:** do this from the teacher dashboard (WhatsApp Notifications
→ "Free WhatsApp (Beta)" section → "Show QR to Link My WhatsApp"), once
step 3 below is configured. That page proxies the QR through an
authenticated, teacher-only Spring endpoint — the raw URL below is never
exposed to the browser and no token leaves the server.

**Manual/debug fallback:** open `https://<this-service-url>/qr?token=<NOTIFICATION_INTERNAL_TOKEN>`
directly. Scan the QR with **WhatsApp app → Settings → Linked Devices →
Link a Device** on the phone number you want to both send *and receive* on
(see "self-chat" above). Use a spare number if you're worried about ban
risk on your primary one.

Once linked, the session is saved to Postgres — you won't need to re-scan
on future redeploys/restarts (only if the phone unlinks the device, or you
deliberately scan again with a different number).

### 3. Enable it in the Java app

Set these on the main app (all optional; leaving them unset/false keeps
current behavior 100% unchanged):

```properties
app.whatsapp.baileys.enabled=true
app.whatsapp.baileys.url=https://<this-service-url>
app.whatsapp.baileys.token=<same value as NOTIFICATION_INTERNAL_TOKEN above>
```

Or as env vars: `WHATSAPP_BAILEYS_ENABLED=true`, `WHATSAPP_BAILEYS_URL=...`,
`WHATSAPP_BAILEYS_TOKEN=...`.

### 4. Test

Trigger a recording (or wait for a real one). Watch this service's logs —
you'll see `[Baileys] ...` lines and `/send-audio` requests coming from the
Java app, and the audio should land in the "Message Yourself" chat on the
linked phone. (The existing "Send Test" button only exercises Twilio/CallMeBot
today, since test messages have no audio clip to attach — see the dispatch
note below.)

## Rolling back

Set `app.whatsapp.baileys.enabled=false` (or just don't set it — that's the
default). The Java app immediately reverts to using only Twilio/CallMeBot,
exactly as before this change. You can leave this service deployed and idle,
or delete it entirely — neither affects the main app.

## API

- `GET /health` — liveness + connection state + currently-linked number.
- `GET /status` — `{ connectionState, hasQr, linkedNumber }`.
- `GET /qr-data` — JSON `{ connectionState, linkedNumber, qrDataUrl }`, header
  `x-notification-token: <NOTIFICATION_INTERNAL_TOKEN>` required. This is what
  the Java app's teacher-only dashboard endpoint calls; not meant for direct
  browser use.
- `GET /qr?token=<NOTIFICATION_INTERNAL_TOKEN>` — HTML fallback page showing
  the current QR code (auto-refreshes). Manual/debug use only — prefer the
  dashboard flow, which never exposes this URL or token to the browser.
- `POST /send-audio` — body `{ audioUrl, caption? }`, header
  `x-notification-token: <NOTIFICATION_INTERNAL_TOKEN>`. Downloads `audioUrl`,
  re-encodes it to Ogg/Opus (see below), and relays it as a WhatsApp audio
  message to the currently-linked number (self-chat) — any `phoneNumber`
  field is ignored by design.

## Audio format (why re-encoding is needed)

The main app's recordings are captured client-side with the browser's
`MediaRecorder` API as **WebM/Opus** — great for the in-app web player, but
WhatsApp's mobile client cannot reliably play a raw WebM container as an
audio-message attachment (you'll see *"Sorry, can't load this audio now"* on
the recipient's phone), even though the Opus codec inside is the same one
WhatsApp itself uses for voice notes.

`/send-audio` re-encodes every clip to **Ogg/Opus** (WhatsApp's own
voice-note format) via `fluent-ffmpeg` + `@ffmpeg-installer/ffmpeg` (a
bundled static binary — no system package/Dockerfile changes needed, works
on Render's plain Node runtime) before handing it to Baileys. If conversion
ever fails for some reason, it falls back to sending the original bytes
as-is rather than dropping the notification entirely.
