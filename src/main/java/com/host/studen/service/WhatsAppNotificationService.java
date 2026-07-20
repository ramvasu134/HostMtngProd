package com.host.studen.service;

import com.host.studen.model.Recording;
import com.host.studen.model.User;
import com.host.studen.repository.RecordingRepository;
import com.twilio.Twilio;
import com.twilio.exception.ApiException;
import com.twilio.rest.api.v2010.account.Message;
import com.twilio.type.PhoneNumber;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Sends WhatsApp messages to teachers when a new recording is saved.
 *
 * <h2>Provider strategy (Twilio-primary as of v2, optional Baileys as of v3)</h2>
 * <ol>
 *   <li><b>Baileys</b> (optional, FREE, off by default). Only tried when
 *       {@code app.whatsapp.baileys.enabled=true} AND
 *       {@code app.whatsapp.baileys.url} point at a running
 *       {@code whatsapp-baileys-service} instance (see that module's README).
 *       Uses the unofficial WhatsApp-Web protocol — zero per-message cost,
 *       but no delivery receipts and a small ToS/ban risk on the linked
 *       number. On ANY failure (not configured, not linked, network error)
 *       this silently falls through to the chain below — it can only ever
 *       ADD a free delivery attempt, never remove the existing paths.</li>
 *   <li><b>Twilio WhatsApp API</b> (primary, recommended). Used whenever the
 *       admin has set the {@code TWILIO_ACCOUNT_SID}, {@code TWILIO_AUTH_TOKEN}
 *       and {@code FROM_WHATSAPP_NUMBER} environment variables. Twilio gives
 *       us per-message delivery receipts ({@code Sent}, {@code Delivered},
 *       {@code Read}, {@code Failed}) by POSTing to our status-callback
 *       webhook ({@code /api/whatsapp/twilio-callback}).</li>
 *   <li><b>CallMeBot</b> (fallback, FREE). Used only when Twilio is NOT
 *       configured AND the teacher has saved their personal CallMeBot API key.
 *       Single-state — there is no real delivery receipt, only "queued".</li>
 * </ol>
 *
 * <h2>Lifecycle status log</h2>
 * Every send attempt is recorded in an in-memory ring buffer (per teacher).
 * For Twilio messages, the entry is updated in place when the webhook fires,
 * so the UI shows {@code QUEUED → SENT → DELIVERED} in real time.
 */
@Service
public class WhatsAppNotificationService {

    private static final Logger log = LoggerFactory.getLogger(WhatsAppNotificationService.class);
    private static final int MAX_STATUS_PER_TEACHER = 10;

    // ── Twilio config ────────────────────────────────────────────────────────
    @Value("${app.twilio.account-sid:}")        private String accountSid;
    @Value("${app.twilio.auth-token:}")         private String authToken;
    @Value("${app.twilio.whatsapp-from:}")      private String fromNumber;

    /** Public URL Twilio should POST status callbacks to. */
    @Value("${app.twilio.status-callback-url:}")
    private String configuredCallbackUrl;

    @Value("${app.whatsapp.enabled:true}")      private boolean masterEnabled;
    @Value("${app.public-url:http://localhost:8080}")
    private String publicUrl;

    /** CallMeBot transient failures — retry with exponential backoff (Copilot-style worker reliability, in-process). */
    @Value("${app.whatsapp.callmebot.max-send-attempts:3}")
    private int callmebotMaxSendAttempts;

    @Value("${app.whatsapp.callmebot.initial-backoff-ms:1000}")
    private long callmebotInitialBackoffMs;

    // ── Baileys (optional, free, off by default) ────────────────────────────
    /** Master switch — default false means zero behavior change out of the box. */
    @Value("${app.whatsapp.baileys.enabled:false}")
    private boolean baileysEnabled;

    /** Base URL of the standalone whatsapp-baileys-service, e.g. https://wa-baileys.onrender.com */
    @Value("${app.whatsapp.baileys.url:}")
    private String baileysUrl;

    /** Shared secret sent as x-notification-token; must match that service's NOTIFICATION_INTERNAL_TOKEN. */
    @Value("${app.whatsapp.baileys.token:}")
    private String baileysToken;

    private boolean twilioReady = false;

    @Autowired
    private RecordingRepository recordingRepository;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
            new com.fasterxml.jackson.databind.ObjectMapper();

    /** Per-teacher ring-buffer of recent send attempts (newest first). */
    private final Map<Long, Deque<NotificationStatus>> statusByTeacher = new ConcurrentHashMap<>();

    /** Twilio MessageSid → owning teacherId (so callbacks find the right log row). */
    private final Map<String, Long> sidToTeacher = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        // Sanitize credentials — env vars often arrive with stray spaces
        // (e.g. "+1 816 819 3622"), which Twilio rejects with error 21212.
        accountSid = accountSid == null ? "" : accountSid.trim();
        authToken  = authToken  == null ? "" : authToken.trim();
        fromNumber = sanitizeFromNumber(fromNumber);
        if (!masterEnabled) {
            log.info("WhatsApp notifications globally disabled (app.whatsapp.enabled=false)");
            return;
        }
        if (!isBlank(accountSid) && !isBlank(authToken) && !isBlank(fromNumber)) {
            try {
                Twilio.init(accountSid, authToken);
                twilioReady = true;
                log.info("WhatsApp: Twilio backend READY (from={}, status-callback={})",
                        fromNumber,
                        isBlank(configuredCallbackUrl) ? "(none — set app.twilio.status-callback-url)" : configuredCallbackUrl);
            } catch (Exception e) {
                log.error("WhatsApp: Twilio init failed: {}", e.getMessage());
            }
        } else {
            log.info("WhatsApp: Twilio NOT configured — falling back to per-teacher CallMeBot keys. " +
                     "To enable Twilio set TWILIO_ACCOUNT_SID + TWILIO_AUTH_TOKEN + TWILIO_WHATSAPP_FROM.");
        }

        baileysUrl = baileysUrl == null ? "" : baileysUrl.trim();
        baileysToken = baileysToken == null ? "" : baileysToken.trim();
        if (baileysEnabled && !isBlank(baileysUrl)) {
            log.info("WhatsApp: optional free Baileys provider ENABLED (url={}). Tried first for audio " +
                     "notifications; falls back to Twilio/CallMeBot on any failure.", baileysUrl);
        } else if (baileysEnabled) {
            log.warn("WhatsApp: app.whatsapp.baileys.enabled=true but app.whatsapp.baileys.url is not set — " +
                     "Baileys provider will be skipped, no behavior change.");
        }
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /** Payload fragment for {@code /topic/recording/{meetingCode}} WebSocket messages. */
    public record RecordingWhatsappHint(String code, String message) {}

    /**
     * When a recording is saved, the host UI can show a toast if the teacher
     * opted into WhatsApp alerts but setup is incomplete (number or provider).
     *
     * @return {@code null} when no reminder is needed
     */
    public RecordingWhatsappHint hintForRecordingSavedEvent(User teacher) {
        if (!masterEnabled) {
            return null;
        }
        if (teacher == null || !teacher.isWhatsappNotificationsEnabled()) {
            return null;
        }
        if (normaliseNumber(teacher.getWhatsappNumber()) == null) {
            return new RecordingWhatsappHint(
                    "NUMBER_MISSING",
                    "WhatsApp number is not configured. Add your number under Dashboard → WhatsApp Notifications to receive automatic recording alerts.");
        }
        if (!twilioReady && isBlank(teacher.getWhatsappApiKey())) {
            return new RecordingWhatsappHint(
                    "PROVIDER_MISSING",
                    "WhatsApp alerts are on but no message provider is configured. Add your CallMeBot API key in WhatsApp settings, or ask your admin to configure Twilio.");
        }
        return null;
    }

    /**
     * Async hook called by RecordingService AFTER a recording has been
     * persisted. Builds the message with a clickable link to the recording,
     * dispatches it, and records the result in the per-teacher status log.
     */
    @Async(com.host.studen.config.AsyncConfig.NOTIFICATION_EXECUTOR)
    public void notifyTeacherOnRecording(Recording recording, User student, User teacher) {
        try {
            // ── Silent-skip preconditions (per "plug-and-play" spec) ─────────
            // None of these conditions are user-facing failures, so we don't
            // pollute the dashboard's "Recent Notifications" panel with rows
            // for every recording when notifications simply weren't requested.
            if (!masterEnabled) {
                log.info("WhatsApp skip: globally disabled (recording {})", safeId(recording));
                return;
            }
            if (teacher == null) {
                log.info("WhatsApp skip: teacher null (recording {})", safeId(recording));
                return;
            }
            if (!teacher.isWhatsappNotificationsEnabled()) {
                log.info("WhatsApp skip: teacher '{}' has notifications off", teacher.getUsername());
                return;
            }
            String to = normaliseNumber(teacher.getWhatsappNumber());
            if (to == null) {
                log.info("WhatsApp skip: teacher '{}' has no/invalid number ('{}')",
                        teacher.getUsername(), teacher.getWhatsappNumber());
                return;
            }

            // From here on, we DO log to the status box because the teacher
            // explicitly opted in and a real send is being attempted.
            String recordingUrl = buildRecordingUrl(recording);
            String body = buildRecordingMessage(student, recording, recordingUrl);
            String mediaUrl = buildRecordingMediaUrl(recording);
            DispatchOutcome outcome = dispatch(teacher, to, body, mediaUrl, recording, student);
            persistRecordingWhatsappOutbound(recording, outcome);
        } catch (Exception e) {
            log.error("WhatsApp: unexpected error notifying teacher on recording {}: {}",
                    safeId(recording), e.getMessage(), e);
            logStatus(teacher, recording, student, NotificationResult.FAILURE,
                    "Unexpected error: " + e.getMessage());
            persistRecordingWhatsappOutboundFailure(recording, "Unexpected error: " + e.getMessage());
        }
    }

    private static Object safeId(Recording r) { return r == null ? "?" : r.getId(); }

    /**
     * Send a "your recording is ready" message to an arbitrary recipient
     * number using the centralised gateway.
     *
     * <p>This is the "no-coupling" path the dashboard's Send Test button
     * (and any future "Send Now" UI) calls. The phone number is sanitised
     * server-side, so the caller can pass whatever the user typed in the
     * input field — spaces, dashes, parentheses, missing country code —
     * and the service normalises to E.164 before handing to Twilio.
     *
     * @param teacher    The currently-authenticated teacher (used to bind
     *                   the entry into the per-teacher status log).
     * @param recipient  Whatever the user typed in the WhatsApp Number box.
     * @param recordingUrl  The public URL to the recording (e.g. from
     *                   {@link #buildRecordingUrl(Recording)} or any
     *                   client-supplied HTTPS link).
     * @return human-readable result string suitable for the UI feedback area.
     */
    public String sendNow(User teacher, String recipient, String recordingUrl) {
        if (!masterEnabled) return "WhatsApp notifications are disabled on this server.";
        if (teacher == null) return "Teacher not found.";

        String to = normaliseNumber(recipient);
        if (to == null) {
            return "Invalid number. Use 10 digits (auto +91) or full international format (+919876543210).";
        }
        if (isBlank(recordingUrl)) {
            return "Recording URL is required.";
        }

        String body = "Hello, your recording is ready: " + recordingUrl;
        return dispatch(teacher, to, body, null, null).summary;
    }

    /** Synchronous test send — returns a human-readable result string. */
    public String sendTestMessage(User teacher) {
        if (teacher == null) return "Teacher not found.";
        if (!masterEnabled) {
            String msg = "WhatsApp notifications are disabled on this server.";
            logStatus(teacher, null, null, NotificationResult.FAILURE, msg);
            return msg;
        }

        String to = normaliseNumber(teacher.getWhatsappNumber());
        if (to == null) {
            String msg = "Invalid number. Save a number first (format: +919876543210 or 9876543210).";
            logStatus(teacher, null, null, NotificationResult.FAILURE, msg);
            return msg;
        }

        boolean hasKey = !isBlank(teacher.getWhatsappApiKey());
        if (!twilioReady && !hasKey) {
            String msg = "No WhatsApp provider available. Ask your admin to configure Twilio (recommended) " +
                   "or add your free CallMeBot API key (see instructions in the WhatsApp settings dialog).";
            logStatus(teacher, null, null, NotificationResult.FAILURE, msg);
            return msg;
        }

        String dashboard = publicUrl.replaceAll("/+$", "") + "/host/dashboard";
        String body = "Hello " + safeName(teacher) +
                ", your WhatsApp notifications are working. Dashboard: " + dashboard;

        return dispatch(teacher, to, body, null, null).summary;
    }

    /** Returns the last N notification attempts for the given teacher (newest first). */
    public List<NotificationStatus> getRecentStatuses(Long teacherId) {
        Deque<NotificationStatus> deque = statusByTeacher.get(teacherId);
        if (deque == null) return new ArrayList<>();
        return new ArrayList<>(deque);
    }

    /**
     * Called by the public Twilio status-callback webhook
     * ({@code POST /api/whatsapp/twilio-callback}). Updates the matching log
     * entry so the dashboard reflects the new lifecycle state.
     *
     * @param messageSid   Twilio's MessageSid (e.g. {@code SMxxxxxxxx...})
     * @param messageState Twilio's reported state — {@code queued},
     *                     {@code sent}, {@code delivered}, {@code read},
     *                     {@code failed}, {@code undelivered}
     * @param errorCode    Twilio numeric error code (or null)
     * @param errorMessage Human-readable error (or null)
     */
    public void applyTwilioStatusCallback(String messageSid, String messageState,
                                          String errorCode, String errorMessage) {
        if (isBlank(messageSid) || isBlank(messageState)) return;
        NotificationLifecycle newState = mapTwilioState(messageState);
        String rowDetail = buildTwilioCallbackRowDetail(newState, messageState, errorCode, errorMessage);

        Long teacherId = sidToTeacher.get(messageSid);
        if (teacherId != null) {
            Deque<NotificationStatus> deque = statusByTeacher.get(teacherId);
            if (deque != null) {
                for (NotificationStatus s : deque) {
                    if (messageSid.equals(s.getProviderMessageId())) {
                        s.lifecycle = newState;
                        s.result = newState.toResult();
                        s.message = rowDetail;
                        s.lastUpdated = LocalDateTime.now();
                        log.info("Twilio callback: SID={} → {} (teacher {})", messageSid, newState, teacherId);
                        break;
                    }
                }
            }
        } else {
            log.debug("Twilio callback: SID={} not in sid→teacher map (restart?). Updating recording row if present.", messageSid);
        }
        syncRecordingWhatsappFromTwilio(messageSid, newState, rowDetail);
    }

    private String buildTwilioCallbackRowDetail(NotificationLifecycle newState, String messageState,
                                                String errorCode, String errorMessage) {
        if (newState == NotificationLifecycle.FAILED || newState == NotificationLifecycle.UNDELIVERED) {
            if (!isBlank(errorMessage)) {
                return errorMessage + (isBlank(errorCode) ? "" : " [" + errorCode + "]");
            }
            return "Twilio reports state=" + messageState + (isBlank(errorCode) ? "" : " (code " + errorCode + ")");
        }
        return "Twilio: " + newState.label();
    }

    private void syncRecordingWhatsappFromTwilio(String messageSid, NotificationLifecycle lifecycle, String detail) {
        try {
            recordingRepository.findByWhatsappOutboundMessageId(messageSid).ifPresent(r -> {
                r.setWhatsappOutboundStatus(twilioLifecycleToOutboundStatus(lifecycle));
                r.setWhatsappOutboundDetail(truncateDetail(detail));
                r.setWhatsappOutboundUpdatedAt(LocalDateTime.now());
                recordingRepository.save(r);
            });
        } catch (Exception e) {
            log.warn("Could not persist Twilio callback for SID {}: {}", messageSid, e.getMessage());
        }
    }

    private static String twilioLifecycleToOutboundStatus(NotificationLifecycle lifecycle) {
        if (lifecycle == null) {
            return "UNKNOWN";
        }
        return switch (lifecycle) {
            case FAILED, UNDELIVERED -> "FAILED";
            case READ -> "READ";
            case DELIVERED -> "DELIVERED";
            case SENT, SENDING -> "SENT";
            case QUEUED -> "QUEUED";
        };
    }

    private String truncateDetail(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.length() <= 512 ? t : t.substring(0, 509) + "...";
    }

    private void persistRecordingWhatsappOutbound(Recording recordingRef, DispatchOutcome outcome) {
        if (recordingRef == null || recordingRef.getId() == null || outcome == null) {
            return;
        }
        try {
            recordingRepository.findById(recordingRef.getId()).ifPresent(r -> {
                if (outcome.success) {
                    r.setWhatsappOutboundStatus("SENT");
                    r.setWhatsappOutboundMessageId(outcome.twilioSid);
                    r.setWhatsappOutboundDetail(truncateDetail(outcome.summary));
                } else {
                    r.setWhatsappOutboundStatus("FAILED");
                    r.setWhatsappOutboundMessageId(null);
                    r.setWhatsappOutboundDetail(truncateDetail(outcome.summary));
                }
                r.setWhatsappOutboundUpdatedAt(LocalDateTime.now());
                recordingRepository.save(r);
            });
        } catch (Exception e) {
            log.warn("Could not persist WhatsApp outbound row for recording {}: {}",
                    recordingRef.getId(), e.getMessage());
        }
    }

    private void persistRecordingWhatsappOutboundFailure(Recording recordingRef, String message) {
        if (recordingRef == null || recordingRef.getId() == null) {
            return;
        }
        try {
            recordingRepository.findById(recordingRef.getId()).ifPresent(r -> {
                r.setWhatsappOutboundStatus("FAILED");
                r.setWhatsappOutboundDetail(truncateDetail(message));
                r.setWhatsappOutboundUpdatedAt(LocalDateTime.now());
                recordingRepository.save(r);
            });
        } catch (Exception e) {
            log.warn("Could not persist WhatsApp failure for recording {}: {}", recordingRef.getId(), e.getMessage());
        }
    }

    // ── Provider selection & dispatch ────────────────────────────────────────

    /**
     * Chooses Twilio first (if configured), falls back to CallMeBot. Side-effects: writes
     * a row into the per-teacher status log so the dashboard reflects state.
     */
    private DispatchOutcome dispatch(User teacher, String to, String body,
                                     Recording recording, User student) {
        return dispatch(teacher, to, body, null, recording, student);
    }

    private DispatchOutcome dispatch(User teacher, String to, String body, String mediaUrl,
                                     Recording recording, User student) {
        // 0) Baileys (optional, free, off by default) — only makes sense when
        // there's an audio clip to attach, and only tried when explicitly
        // configured. ANY failure here falls through to the untouched
        // Twilio → CallMeBot chain below, so this can only add a free
        // delivery attempt — it never removes/weakens existing behavior.
        if (baileysEnabled && !isBlank(baileysUrl) && !isBlank(mediaUrl)) {
            BaileysSendResult br = sendViaBaileys(mediaUrl, body);
            if (br.success) {
                NotificationStatus s = logStatus(teacher, recording, student,
                        NotificationResult.SUCCESS,
                        "Baileys (free WhatsApp-Web) accepted — delivered to linked number "
                                + (br.sentTo != null ? br.sentTo : "(unknown)"));
                s.provider = "Baileys";
                s.lifecycle = NotificationLifecycle.SENT;
                return DispatchOutcome.ok(
                        "Audio sent successfully via Baileys (free) to the linked WhatsApp number"
                                + (br.sentTo != null ? " (" + br.sentTo + ")" : ""),
                        null);
            }
            log.info("Baileys attempt failed for teacher '{}' (falling back to Twilio/CallMeBot): {}",
                    teacher.getUsername(), br.error);
        }

        // 1) Twilio first when admin has configured it
        if (twilioReady) {
            TwilioSendResult tr = sendViaTwilio(to, body, mediaUrl);
            if (tr.success) {
                NotificationStatus s = logStatus(teacher, recording, student,
                        NotificationResult.SUCCESS,
                        "Twilio accepted — awaiting delivery receipt");
                s.providerMessageId = tr.messageSid;
                s.provider = "Twilio";
                s.lifecycle = NotificationLifecycle.SENT;
                if (tr.messageSid != null && teacher.getId() != null) {
                    sidToTeacher.put(tr.messageSid, teacher.getId());
                }
                return DispatchOutcome.ok(
                        "Test message sent successfully to " + to + " via Twilio (SID " + tr.messageSid + ")",
                        tr.messageSid);
            }
            if (!isBlank(teacher.getWhatsappApiKey())) {
                log.warn("Twilio failed for teacher '{}', trying CallMeBot. Reason: {}",
                        teacher.getUsername(), tr.error);
            } else {
                logStatus(teacher, recording, student, NotificationResult.FAILURE,
                        "Twilio: " + tr.error);
                return DispatchOutcome.fail("Twilio send failed: " + tr.error);
            }
        }

        // 2) CallMeBot fallback — per-teacher API key (with bounded retries)
        if (!isBlank(teacher.getWhatsappApiKey())) {
            String r = sendViaCallMeBotWithBackoff(to, body, teacher.getWhatsappApiKey());
            if (r.startsWith("OK")) {
                NotificationStatus s = logStatus(teacher, recording, student,
                        NotificationResult.SUCCESS, "CallMeBot accepted");
                s.lifecycle = NotificationLifecycle.SENT;
                s.provider = "CallMeBot";
                return DispatchOutcome.ok(
                        "Test message sent successfully to " + to + " via CallMeBot", null);
            }
            logStatus(teacher, recording, student, NotificationResult.FAILURE,
                    "CallMeBot: " + r);
            return DispatchOutcome.fail("CallMeBot failed: " + r);
        }

        logStatus(teacher, recording, student, NotificationResult.FAILURE,
                "WhatsApp service is unavailable — please contact your administrator " +
                "(server-side Twilio credentials are not configured).");
        return DispatchOutcome.fail(
                "WhatsApp is currently unavailable on this server. Please contact your administrator.");
    }

    /** Twilio WhatsApp send — single API call. */
    private TwilioSendResult sendViaTwilio(String toNumber, String body, String mediaUrl) {
        try {
            // Use var so we don't have to depend on Twilio's exact creator class name
            // (which has moved across major versions of the SDK).
            var creator = Message.creator(
                    new PhoneNumber("whatsapp:" + toNumber),
                    new PhoneNumber(fromNumber.startsWith("whatsapp:") ? fromNumber : "whatsapp:" + fromNumber),
                    body
            );
            // Attach the audio clip when a publicly fetchable media URL is available.
            if (!isBlank(mediaUrl)) {
                creator.setMediaUrl(Collections.singletonList(URI.create(mediaUrl)));
                log.info("Twilio: attaching audio media {}", mediaUrl);
            }
            // Wire delivery receipts back to our webhook (optional — only when public URL is set).
            String callback = effectiveCallbackUrl();
            if (!isBlank(callback)) {
                creator.setStatusCallback(URI.create(callback));
            }
            Message msg = creator.create();
            log.info("Twilio send OK to {} (SID: {}, status={}, media={})",
                    toNumber, msg.getSid(), msg.getStatus(), !isBlank(mediaUrl));
            return TwilioSendResult.ok(msg.getSid());
        } catch (ApiException e) {
            log.error("Twilio API error to {}: [{}] {}", toNumber, e.getCode(), e.getMessage());
            return TwilioSendResult.fail("[" + e.getCode() + "] " + e.getMessage());
        } catch (Exception e) {
            log.error("Twilio unexpected error to {}: {}", toNumber, e.getMessage());
            return TwilioSendResult.fail(e.getMessage() != null ? e.getMessage() : "unknown error");
        }
    }

    /**
     * Optional free provider — POSTs to the standalone {@code whatsapp-baileys-service}
     * (see that module's README). That service downloads {@code mediaUrl} itself and
     * relays it as a WhatsApp audio message over the unofficial WhatsApp-Web protocol.
     * Any error (network, not-linked, timeout) is caught and reported so the caller
     * can fall back to Twilio/CallMeBot without interrupting the send.
     *
     * <p>Note there's no destination phone number in this request — the Node
     * service always delivers to whichever number is currently linked (see
     * that module's README / "self-chat" design), never to an arbitrary
     * per-teacher number. This is intentional: only a teacher can link a
     * number (via the dashboard-gated QR flow), and re-linking replaces the
     * single active recipient for everyone.
     */
    private static final int BAILEYS_MAX_ATTEMPTS = 3;
    private static final long BAILEYS_RETRY_DELAY_MS = 4000;

    /**
     * Retries transient failures (network errors, 5xx, request timeout) so a single
     * hiccup under concurrent load (e.g. many students' recordings landing at once,
     * or the Node service still warming up) doesn't permanently drop a recording's
     * WhatsApp delivery. Does NOT retry deterministic non-transient outcomes like
     * "not linked yet" (503 with a clear message) since retrying won't help.
     */
    private BaileysSendResult sendViaBaileys(String mediaUrl, String caption) {
        BaileysSendResult last = BaileysSendResult.fail("not attempted");
        for (int attempt = 1; attempt <= BAILEYS_MAX_ATTEMPTS; attempt++) {
            last = sendViaBaileysOnce(mediaUrl, caption);
            if (last.success || !last.transient_) {
                return last;
            }
            if (attempt < BAILEYS_MAX_ATTEMPTS) {
                log.info("Baileys attempt {}/{} failed transiently, retrying in {}ms: {}",
                        attempt, BAILEYS_MAX_ATTEMPTS, BAILEYS_RETRY_DELAY_MS, last.error);
                try {
                    Thread.sleep(BAILEYS_RETRY_DELAY_MS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        return last;
    }

    private BaileysSendResult sendViaBaileysOnce(String mediaUrl, String caption) {
        try {
            com.fasterxml.jackson.databind.node.ObjectNode payload = JSON.createObjectNode();
            payload.put("audioUrl", mediaUrl);
            if (!isBlank(caption)) {
                payload.put("caption", caption);
            }
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(baileysUrl.replaceAll("/+$", "") + "/send-audio"))
                    // Render's free tier spins whatsapp-baileys-service down after ~15 min
                    // idle; the first request afterwards can take 20-50s just to wake it up
                    // before any app logic runs, so this needs real headroom beyond that.
                    .timeout(Duration.ofSeconds(50))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(payload)));
            if (!isBlank(baileysToken)) {
                builder.header("x-notification-token", baileysToken);
            }
            HttpResponse<String> res = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            String responseBody = res.body() != null ? res.body() : "";
            if (res.statusCode() >= 200 && res.statusCode() < 300) {
                String sentTo = null;
                try {
                    sentTo = JSON.readTree(responseBody).path("sentTo").asText(null);
                } catch (Exception ignored) { /* best-effort only */ }
                log.info("Baileys send OK (HTTP {}, sentTo={})", res.statusCode(), sentTo);
                return BaileysSendResult.ok(sentTo);
            }
            // 503 = "not linked yet" (deterministic — no point retrying); anything else
            // (e.g. 500 mid-transcode blip, 502/504 from a cold-starting proxy) is transient.
            boolean transientFailure = res.statusCode() != 503;
            log.warn("Baileys send failed (HTTP {}): {}", res.statusCode(), responseBody);
            return BaileysSendResult.fail("HTTP " + res.statusCode() + ": " + responseBody, transientFailure);
        } catch (java.io.IOException e) {
            // Covers HttpTimeoutException and ConnectException (both are IOException subtypes) — network blips.
            log.warn("Baileys send transient exception: {}", e.getMessage());
            return BaileysSendResult.fail(e.getMessage() != null ? e.getMessage() : "network error", true);
        } catch (Exception e) {
            log.warn("Baileys send exception: {}", e.getMessage());
            return BaileysSendResult.fail(e.getMessage() != null ? e.getMessage() : "unknown error", false);
        }
    }

    /**
     * Fetches live linking status from the standalone whatsapp-baileys-service
     * so the TEACHER-ONLY dashboard endpoint can render a QR code / show which
     * number is currently linked. Never called from anything student-facing.
     */
    public BaileysStatusDto getBaileysStatus() {
        if (!baileysEnabled || isBlank(baileysUrl)) {
            return new BaileysStatusDto(false, baileysEnabled, null, null, null, null);
        }
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(baileysUrl.replaceAll("/+$", "") + "/qr-data"))
                    // See sendViaBaileys() above — must tolerate Render free-tier cold starts,
                    // not just a healthy warm response, or the dashboard shows a false
                    // "unreachable" every time the service has been idle for a while.
                    .timeout(Duration.ofSeconds(50))
                    .GET();
            if (!isBlank(baileysToken)) {
                builder.header("x-notification-token", baileysToken);
            }
            HttpResponse<String> res = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) {
                return new BaileysStatusDto(true, true, "unreachable", null, null,
                        "HTTP " + res.statusCode());
            }
            com.fasterxml.jackson.databind.JsonNode node = JSON.readTree(res.body());
            String connectionState = node.path("connectionState").asText(null);
            String linkedNumber = node.hasNonNull("linkedNumber") ? node.get("linkedNumber").asText() : null;
            String qrDataUrl = node.hasNonNull("qrDataUrl") ? node.get("qrDataUrl").asText() : null;
            return new BaileysStatusDto(true, true, connectionState, linkedNumber, qrDataUrl, null);
        } catch (Exception e) {
            log.warn("Baileys status check failed: {}", e.getMessage());
            return new BaileysStatusDto(true, true, "unreachable", null, null, e.getMessage());
        }
    }

    /**
     * Teacher-triggered "de-link my WhatsApp" action — proxies to the
     * standalone whatsapp-baileys-service's /unlink endpoint, which logs the
     * currently-linked phone out and immediately issues a fresh QR code.
     * TEACHER-ONLY: only reachable via the {@code @PreAuthorize("hasRole('HOST')")}
     * dashboard endpoint.
     */
    public BaileysStatusDto unlinkBaileys() {
        if (!baileysEnabled || isBlank(baileysUrl)) {
            return new BaileysStatusDto(false, baileysEnabled, null, null, null, "Baileys is not configured.");
        }
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(baileysUrl.replaceAll("/+$", "") + "/unlink"))
                    // See sendViaBaileys() above re: Render free-tier cold starts.
                    .timeout(Duration.ofSeconds(50))
                    .POST(HttpRequest.BodyPublishers.noBody());
            if (!isBlank(baileysToken)) {
                builder.header("x-notification-token", baileysToken);
            }
            HttpResponse<String> res = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() < 200 || res.statusCode() >= 300) {
                return new BaileysStatusDto(true, true, "unreachable", null, null,
                        "HTTP " + res.statusCode() + ": " + res.body());
            }
            log.info("Baileys unlink requested — session cleared, new QR pending.");
            // Give the service a brief moment to restart before the dashboard polls status again.
            return new BaileysStatusDto(true, true, "starting", null, null, null);
        } catch (Exception e) {
            log.warn("Baileys unlink failed: {}", e.getMessage());
            return new BaileysStatusDto(true, true, "unreachable", null, null, e.getMessage());
        }
    }

    /**
     * Teacher-facing snapshot of the optional Baileys provider.
     * @param configured   true when {@code app.whatsapp.baileys.enabled=true} AND a URL is set
     * @param enabled      raw value of the master switch (may be true even if url is blank)
     * @param connectionState "starting" | "qr" | "open" | "closed" | "unreachable" | null
     * @param linkedNumber the number currently linked (self-chat destination), or null
     * @param qrDataUrl    base64 PNG data URL of the current QR code, or null
     * @param error        best-effort error message when the status check itself failed
     */
    public record BaileysStatusDto(boolean configured, boolean enabled, String connectionState,
                                    String linkedNumber, String qrDataUrl, String error) {}

    /**
     * CallMeBot WhatsApp send (fallback). All query parameters are
     * URL-encoded so spaces and special characters in the recording URL
     * (or the message body) don't break the HTTP call.
     */
    private String sendViaCallMeBot(String toNumber, String body, String apiKey) {
        try {
            String url = "https://api.callmebot.com/whatsapp.php"
                    + "?phone="  + URLEncoder.encode(toNumber, StandardCharsets.UTF_8)
                    + "&text="   + URLEncoder.encode(body,     StandardCharsets.UTF_8)
                    + "&apikey=" + URLEncoder.encode(apiKey,   StandardCharsets.UTF_8);
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(15))
                    .GET()
                    .build();
            HttpResponse<String> res = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            String responseBody = res.body() != null ? res.body() : "";

            if (res.statusCode() == 200 && (responseBody.toLowerCase().contains("message queued")
                                            || responseBody.toLowerCase().contains("message sent"))) {
                log.info("CallMeBot send OK to {} (HTTP {})", toNumber, res.statusCode());
                return "OK queued";
            }
            String snippet = responseBody.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim();
            if (snippet.length() > 220) snippet = snippet.substring(0, 220) + "…";
            log.warn("CallMeBot send failed to {} (HTTP {}): {}", toNumber, res.statusCode(), snippet);

            if (snippet.toLowerCase().contains("apikey")) {
                return "Invalid API key. Re-do activation: send 'I allow callmebot to send me messages' to +34 644 51 95 23 on WhatsApp, then wait 2-5 minutes.";
            }
            if (snippet.toLowerCase().contains("limit")) {
                return "Rate limit reached — wait a minute and retry.";
            }
            return snippet;
        } catch (Exception e) {
            log.error("CallMeBot send exception for {}: {}", toNumber, e.getMessage());
            return "Network error: " + e.getMessage();
        }
    }

    /**
     * Bounded retries for CallMeBot (in-process substitute for BullMQ workers when
     * hitting transient network or rate-limit responses).
     */
    private String sendViaCallMeBotWithBackoff(String toNumber, String body, String apiKey) {
        int attempts = Math.max(1, callmebotMaxSendAttempts);
        long waitMs = Math.max(200L, callmebotInitialBackoffMs);
        String last = "";
        for (int i = 0; i < attempts; i++) {
            last = sendViaCallMeBot(toNumber, body, apiKey);
            if (last.startsWith("OK")) {
                return last;
            }
            if (i < attempts - 1 && isTransientCallmeBotFailure(last)) {
                log.info("CallMeBot retry {}/{} after transient failure: {}", i + 1, attempts, last);
                try {
                    Thread.sleep(waitMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
                waitMs = Math.min(waitMs * 2, 30_000L);
            } else {
                break;
            }
        }
        return last;
    }

    private static boolean isTransientCallmeBotFailure(String response) {
        if (response == null) {
            return false;
        }
        String lower = response.toLowerCase();
        return lower.contains("network error")
                || lower.contains("rate limit")
                || lower.contains("timed out")
                || lower.contains("timeout");
    }

    private String effectiveCallbackUrl() {
        if (!isBlank(configuredCallbackUrl)) return configuredCallbackUrl.trim();
        // Auto-derive from public URL when not explicitly set.
        if (!isBlank(publicUrl) && !publicUrl.contains("localhost")) {
            return publicUrl.replaceAll("/+$", "") + "/api/whatsapp/twilio-callback";
        }
        return null; // Twilio rejects http://localhost callbacks
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Builds a deep-link to the host's recording on the public dashboard. The
     * URL is intentionally simple (no spaces, no special chars from user input)
     * so URL-encoding is a no-op — but downstream code still encodes it
     * defensively before passing to CallMeBot's GET endpoint.
     */
    private String buildRecordingUrl(Recording recording) {
        return publicUrl.replaceAll("/+$", "") + "/host/recordings#rec-" + recording.getId();
    }

    /**
     * Builds the public, unauthenticated URL Twilio fetches the audio clip from
     * (served by {@code PublicRecordingController}). The UUID disk filename in
     * the path doubles as an unguessable access token.
     *
     * @return {@code null} when the clip cannot be attached: public URL not set
     *         (Twilio cannot reach localhost) or the audio format is not
     *         accepted by WhatsApp — in that case the text + link still goes out.
     */
    private String buildRecordingMediaUrl(Recording recording) {
        if (recording == null || recording.getId() == null || isBlank(recording.getFilePath())) {
            return null;
        }
        if (isBlank(publicUrl) || publicUrl.contains("localhost") || publicUrl.contains("127.0.0.1")) {
            log.info("WhatsApp: audio clip NOT attached for recording {} — app.public-url is localhost/unset. " +
                     "Set APP_PUBLIC_URL to a public https URL so Twilio can fetch the media.", recording.getId());
            return null;
        }
        String diskFileName = java.nio.file.Paths.get(recording.getFilePath()).getFileName().toString();
        if (!isWhatsappSupportedAudio(diskFileName)) {
            log.info("WhatsApp: audio clip NOT attached for recording {} — format '{}' is not supported by " +
                     "WhatsApp media (supported: m4a/mp4/ogg/mp3/aac/amr). Sending text + link only.",
                    recording.getId(), diskFileName);
            return null;
        }
        return publicUrl.replaceAll("/+$", "") + "/api/public/recordings/" + recording.getId() + "/" + diskFileName;
    }

    /**
     * Strips spaces/dashes/parentheses from the configured Twilio sender so
     * "+1 816 819 3622" becomes "+18168193622" (E.164, as Twilio requires).
     * Preserves an optional "whatsapp:" prefix.
     */
    private static String sanitizeFromNumber(String raw) {
        if (raw == null) return "";
        String s = raw.trim();
        boolean hasPrefix = s.toLowerCase(Locale.ROOT).startsWith("whatsapp:");
        if (hasPrefix) s = s.substring("whatsapp:".length());
        s = s.replaceAll("[\\s\\-()]", "");
        if (s.isEmpty()) return "";
        return hasPrefix ? "whatsapp:" + s : s;
    }

    /** WhatsApp accepts audio/aac, audio/amr, audio/mpeg, audio/mp4, audio/ogg — but NOT audio/webm or wav. */
    private static boolean isWhatsappSupportedAudio(String fileName) {
        String lower = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        return lower.endsWith(".m4a") || lower.endsWith(".mp4") || lower.endsWith(".ogg")
                || lower.endsWith(".oga") || lower.endsWith(".mp3") || lower.endsWith(".aac")
                || lower.endsWith(".amr");
    }

    /**
     * Message text. Format follows the user's "lightweight trigger" spec:
     *   "New recording from VK Meeting: {URL}"
     *
     * <p>The student name is intentionally NOT in the body — keeping the
     * message terse so WhatsApp's URL preview is the visual focal point.
     */
    private static final DateTimeFormatter RECORDING_MESSAGE_TIMESTAMP_FMT =
            DateTimeFormatter.ofPattern("dd-MMM-yyyy hh:mm a");

    /**
     * Student name + timestamp up front (before the link) so the message is
     * easy to find later with WhatsApp's own chat search — searching the
     * student's name turns up every recording sent for them, in order.
     */
    private String buildRecordingMessage(User student, Recording recording, String recordingUrl) {
        String studentName = safeName(student);
        LocalDateTime createdAt = recording != null ? recording.getCreatedAt() : null;
        String timestamp = createdAt != null ? createdAt.format(RECORDING_MESSAGE_TIMESTAMP_FMT) : "";
        StringBuilder sb = new StringBuilder("New recording — ").append(studentName);
        if (!timestamp.isEmpty()) {
            sb.append(" — ").append(timestamp);
        }
        sb.append("\n").append(recordingUrl);
        return sb.toString();
    }

    private String safeName(User u) {
        if (u == null) return "Student";
        return u.getDisplayName() != null ? u.getDisplayName() : u.getUsername();
    }

    /**
     * Strict number normaliser:
     *  - strips all whitespace, dashes, parentheses
     *  - if input starts with '+' → use as-is (after stripping)
     *  - else if exactly 10 digits → assume India and prepend +91
     *  - else prepend '+'
     *  - returns null if the result has fewer than 8 digits
     */
    private String normaliseNumber(String raw) {
        if (isBlank(raw)) return null;
        String stripped = raw.trim().replaceAll("[\\s\\-()]", "");
        String digits;
        if (stripped.startsWith("+")) {
            digits = stripped.substring(1).replaceAll("[^0-9]", "");
            if (digits.length() < 8) return null;
            return "+" + digits;
        }
        digits = stripped.replaceAll("[^0-9]", "");
        if (digits.length() == 10) return "+91" + digits;
        if (digits.length() < 8) return null;
        return "+" + digits;
    }

    private boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    /**
     * Records a notification attempt in the per-teacher ring buffer.
     * Returns the freshly-inserted entry so the caller can post-decorate it
     * with provider-specific metadata (Twilio SID, lifecycle, etc).
     */
    private NotificationStatus logStatus(User teacher, Recording recording, User student,
                                         NotificationResult result, String message) {
        if (teacher == null || teacher.getId() == null) {
            return new NotificationStatus(LocalDateTime.now(), null, null, null, result, message);
        }
        NotificationStatus status = new NotificationStatus(
                LocalDateTime.now(),
                teacher.getWhatsappNumber(),
                recording != null ? recording.getId() : null,
                student != null ? safeName(student) : null,
                result,
                message
        );
        Deque<NotificationStatus> deque = statusByTeacher.computeIfAbsent(
                teacher.getId(), k -> new ConcurrentLinkedDeque<>());
        deque.addFirst(status);
        while (deque.size() > MAX_STATUS_PER_TEACHER) {
            NotificationStatus evicted = deque.pollLast();
            if (evicted != null && evicted.providerMessageId != null) {
                sidToTeacher.remove(evicted.providerMessageId);
            }
        }
        return status;
    }

    private static NotificationLifecycle mapTwilioState(String s) {
        switch (s.toLowerCase()) {
            case "queued":      case "accepted":  return NotificationLifecycle.QUEUED;
            case "sending":                       return NotificationLifecycle.SENDING;
            case "sent":                          return NotificationLifecycle.SENT;
            case "delivered":                     return NotificationLifecycle.DELIVERED;
            case "read":                          return NotificationLifecycle.READ;
            case "failed":                        return NotificationLifecycle.FAILED;
            case "undelivered":                   return NotificationLifecycle.UNDELIVERED;
            default:                              return NotificationLifecycle.QUEUED;
        }
    }

    // ── Inner DTOs ───────────────────────────────────────────────────────────

    public enum NotificationResult { SUCCESS, FAILURE, SKIPPED }

    /** Fine-grained Twilio-aware lifecycle states surfaced in the UI. */
    public enum NotificationLifecycle {
        QUEUED("Queued"),
        SENDING("Sending"),
        SENT("Sent"),
        DELIVERED("Delivered"),
        READ("Read"),
        FAILED("Failed"),
        UNDELIVERED("Undelivered");

        private final String label;
        NotificationLifecycle(String label) { this.label = label; }
        public String label() { return label; }
        public NotificationResult toResult() {
            switch (this) {
                case FAILED: case UNDELIVERED:        return NotificationResult.FAILURE;
                case SENT: case DELIVERED: case READ: return NotificationResult.SUCCESS;
                default:                              return NotificationResult.SUCCESS; // queued counts as in-flight success
            }
        }
    }

    /** Public DTO surfaced by the dashboard's status endpoint. */
    public static class NotificationStatus {
        private final LocalDateTime timestamp;
        private LocalDateTime lastUpdated;
        private final String recipientNumber;
        private final Long recordingId;
        private final String studentName;
        private NotificationResult result;
        private String message;
        // Provider-specific decoration (populated by dispatch + callback).
        private String provider;            // "Twilio" | "CallMeBot"
        private String providerMessageId;   // Twilio MessageSid
        private NotificationLifecycle lifecycle = NotificationLifecycle.QUEUED;

        public NotificationStatus(LocalDateTime timestamp, String recipientNumber, Long recordingId,
                                  String studentName, NotificationResult result, String message) {
            this.timestamp = timestamp;
            this.lastUpdated = timestamp;
            this.recipientNumber = recipientNumber;
            this.recordingId = recordingId;
            this.studentName = studentName;
            this.result = result;
            this.message = message;
        }

        public LocalDateTime getTimestamp()        { return timestamp; }
        public LocalDateTime getLastUpdated()      { return lastUpdated; }
        public String        getRecipientNumber()  { return recipientNumber; }
        public Long          getRecordingId()      { return recordingId; }
        public String        getStudentName()      { return studentName; }
        public NotificationResult getResult()      { return result; }
        public String        getMessage()          { return message; }
        public String        getProvider()         { return provider; }
        public String        getProviderMessageId(){ return providerMessageId; }
        public NotificationLifecycle getLifecycle(){ return lifecycle; }
    }

    /** Result of {@link #dispatch} — drives test-send strings + recording row persistence. */
    private static final class DispatchOutcome {
        final boolean success;
        final String twilioSid;
        final String summary;

        private DispatchOutcome(boolean success, String twilioSid, String summary) {
            this.success = success;
            this.twilioSid = twilioSid;
            this.summary = summary;
        }

        static DispatchOutcome ok(String summary, String twilioSid) {
            return new DispatchOutcome(true, twilioSid, summary);
        }

        static DispatchOutcome fail(String summary) {
            return new DispatchOutcome(false, null, summary);
        }
    }

    /** Small internal value-class so dispatch() can branch on Twilio result. */
    private static final class TwilioSendResult {
        final boolean success;
        final String messageSid;
        final String error;
        private TwilioSendResult(boolean success, String messageSid, String error) {
            this.success = success; this.messageSid = messageSid; this.error = error;
        }
        static TwilioSendResult ok(String sid)   { return new TwilioSendResult(true,  sid,  null); }
        static TwilioSendResult fail(String err) { return new TwilioSendResult(false, null, err); }
    }

    /** Small internal value-class so dispatch() can branch on the optional Baileys result. */
    private static final class BaileysSendResult {
        final boolean success;
        final String sentTo;
        final String error;
        final boolean transient_; // true = worth retrying (network blip, 5xx); false = deterministic failure
        private BaileysSendResult(boolean success, String sentTo, String error, boolean transient_) {
            this.success = success; this.sentTo = sentTo; this.error = error; this.transient_ = transient_;
        }
        static BaileysSendResult ok(String sentTo)  { return new BaileysSendResult(true,  sentTo, null, false); }
        static BaileysSendResult fail(String err, boolean transientFailure) { return new BaileysSendResult(false, null, err, transientFailure); }
        static BaileysSendResult fail(String err)   { return new BaileysSendResult(false, null,   err, false); }
    }

    /** @return true when Twilio is the active primary provider. */
    public boolean isTwilioReady() { return twilioReady; }

    /** @return true when WhatsApp notifications are globally enabled. */
    public boolean isMasterEnabled() { return masterEnabled; }
}
