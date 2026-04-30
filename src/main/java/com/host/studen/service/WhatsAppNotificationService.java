package com.host.studen.service;

import com.host.studen.model.Recording;
import com.host.studen.model.User;
import com.twilio.Twilio;
import com.twilio.exception.ApiException;
import com.twilio.rest.api.v2010.account.Message;
import com.twilio.type.PhoneNumber;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Sends WhatsApp messages to teachers when a new recording is saved.
 *
 * <h2>Provider strategy (Twilio-primary as of v2)</h2>
 * <ol>
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

    private boolean twilioReady = false;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    /** Per-teacher ring-buffer of recent send attempts (newest first). */
    private final Map<Long, Deque<NotificationStatus>> statusByTeacher = new ConcurrentHashMap<>();

    /** Twilio MessageSid → owning teacherId (so callbacks find the right log row). */
    private final Map<String, Long> sidToTeacher = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
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
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Async hook called by RecordingService AFTER a recording has been
     * persisted. Builds the message with a clickable link to the recording,
     * dispatches it, and records the result in the per-teacher status log.
     */
    @Async
    public void notifyTeacherOnRecording(Recording recording, User student, User teacher) {
        try {
            // ── Silent-skip preconditions (per "plug-and-play" spec) ─────────
            // None of these conditions are user-facing failures, so we don't
            // pollute the dashboard's "Recent Notifications" panel with rows
            // for every recording when notifications simply weren't requested.
            if (!masterEnabled) {
                log.debug("WhatsApp silent-skip: globally disabled (recording {})", safeId(recording));
                return;
            }
            if (teacher == null) {
                log.debug("WhatsApp silent-skip: teacher null (recording {})", safeId(recording));
                return;
            }
            if (!teacher.isWhatsappNotificationsEnabled()) {
                log.debug("WhatsApp silent-skip: teacher '{}' has notifications off", teacher.getUsername());
                return;
            }
            String to = normaliseNumber(teacher.getWhatsappNumber());
            if (to == null) {
                log.debug("WhatsApp silent-skip: teacher '{}' has no/invalid number ('{}')",
                        teacher.getUsername(), teacher.getWhatsappNumber());
                return;
            }

            // From here on, we DO log to the status box because the teacher
            // explicitly opted in and a real send is being attempted.
            String recordingUrl = buildRecordingUrl(recording);
            String body = buildRecordingMessage(student, recordingUrl);
            dispatch(teacher, to, body, recording, student);
        } catch (Exception e) {
            log.error("WhatsApp: unexpected error notifying teacher on recording {}: {}",
                    safeId(recording), e.getMessage(), e);
            logStatus(teacher, recording, student, NotificationResult.FAILURE,
                    "Unexpected error: " + e.getMessage());
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
        return dispatch(teacher, to, body, null, null);
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

        return dispatch(teacher, to, body, null, null);
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
        Long teacherId = sidToTeacher.get(messageSid);
        if (teacherId == null) {
            log.debug("Twilio callback for unknown SID {} (state={}). Ignoring.", messageSid, messageState);
            return;
        }
        Deque<NotificationStatus> deque = statusByTeacher.get(teacherId);
        if (deque == null) return;

        NotificationLifecycle newState = mapTwilioState(messageState);
        for (NotificationStatus s : deque) {
            if (messageSid.equals(s.getProviderMessageId())) {
                s.lifecycle = newState;
                s.result = newState.toResult();
                if (newState == NotificationLifecycle.FAILED || newState == NotificationLifecycle.UNDELIVERED) {
                    String detail = !isBlank(errorMessage)
                            ? errorMessage + (isBlank(errorCode) ? "" : " [" + errorCode + "]")
                            : ("Twilio reports state=" + messageState + (isBlank(errorCode) ? "" : " (code " + errorCode + ")"));
                    s.message = detail;
                } else {
                    s.message = "Twilio: " + newState.label();
                }
                s.lastUpdated = LocalDateTime.now();
                log.info("Twilio callback: SID={} → {} (teacher {})", messageSid, newState, teacherId);
                return;
            }
        }
    }

    // ── Provider selection & dispatch ────────────────────────────────────────

    /**
     * Chooses Twilio first (if configured), falls back to CallMeBot. Returns a
     * human-readable string suitable for the test-send UI. Side-effects: writes
     * a row into the per-teacher status log so the dashboard reflects state.
     */
    private String dispatch(User teacher, String to, String body,
                            Recording recording, User student) {
        // 1) Twilio first when admin has configured it
        if (twilioReady) {
            TwilioSendResult tr = sendViaTwilio(to, body);
            if (tr.success) {
                // Surface Twilio acceptance as "Sent" immediately. The async webhook
                // upgrades it to Delivered → Read as the recipient's device confirms.
                NotificationStatus s = logStatus(teacher, recording, student,
                        NotificationResult.SUCCESS,
                        "Twilio accepted — awaiting delivery receipt");
                s.providerMessageId = tr.messageSid;
                s.provider = "Twilio";
                s.lifecycle = NotificationLifecycle.SENT;
                if (tr.messageSid != null && teacher.getId() != null) {
                    sidToTeacher.put(tr.messageSid, teacher.getId());
                }
                return "Test message sent successfully to " + to + " via Twilio (SID " + tr.messageSid + ")";
            }
            // Twilio failed → try CallMeBot if the teacher has a key, else surface the error
            if (!isBlank(teacher.getWhatsappApiKey())) {
                log.warn("Twilio failed for teacher '{}', trying CallMeBot. Reason: {}",
                        teacher.getUsername(), tr.error);
            } else {
                logStatus(teacher, recording, student, NotificationResult.FAILURE,
                        "Twilio: " + tr.error);
                return "Twilio send failed: " + tr.error;
            }
        }

        // 2) CallMeBot fallback — per-teacher API key
        if (!isBlank(teacher.getWhatsappApiKey())) {
            String r = sendViaCallMeBot(to, body, teacher.getWhatsappApiKey());
            if (r.startsWith("OK")) {
                NotificationStatus s = logStatus(teacher, recording, student,
                        NotificationResult.SUCCESS, "CallMeBot accepted");
                // CallMeBot has no real delivery receipt — acceptance is the final
                // state we ever see, so label it as "Sent" too.
                s.lifecycle = NotificationLifecycle.SENT;
                s.provider = "CallMeBot";
                return "Test message sent successfully to " + to + " via CallMeBot";
            }
            logStatus(teacher, recording, student, NotificationResult.FAILURE,
                    "CallMeBot: " + r);
            return "CallMeBot failed: " + r;
        }

        logStatus(teacher, recording, student, NotificationResult.FAILURE,
                "WhatsApp service is unavailable — please contact your administrator " +
                "(server-side Twilio credentials are not configured).");
        return "WhatsApp is currently unavailable on this server. Please contact your administrator.";
    }

    /** Twilio WhatsApp send — single API call. */
    private TwilioSendResult sendViaTwilio(String toNumber, String body) {
        try {
            // Use var so we don't have to depend on Twilio's exact creator class name
            // (which has moved across major versions of the SDK).
            var creator = Message.creator(
                    new PhoneNumber("whatsapp:" + toNumber),
                    new PhoneNumber(fromNumber.startsWith("whatsapp:") ? fromNumber : "whatsapp:" + fromNumber),
                    body
            );
            // Wire delivery receipts back to our webhook (optional — only when public URL is set).
            String callback = effectiveCallbackUrl();
            if (!isBlank(callback)) {
                creator.setStatusCallback(URI.create(callback));
            }
            Message msg = creator.create();
            log.info("Twilio send OK to {} (SID: {}, status={})", toNumber, msg.getSid(), msg.getStatus());
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
     * Message text. Format follows the user's "lightweight trigger" spec:
     *   "New recording from VK Meeting: {URL}"
     *
     * <p>The student name is intentionally NOT in the body — keeping the
     * message terse so WhatsApp's URL preview is the visual focal point.
     */
    private String buildRecordingMessage(User student, String recordingUrl) {
        return "New recording from VK Meeting: " + recordingUrl;
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

    /** @return true when Twilio is the active primary provider. */
    public boolean isTwilioReady() { return twilioReady; }

    /** @return true when WhatsApp notifications are globally enabled. */
    public boolean isMasterEnabled() { return masterEnabled; }
}
