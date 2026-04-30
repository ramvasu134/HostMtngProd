package com.host.studen.controller.api;

import com.host.studen.service.WhatsAppNotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import jakarta.servlet.http.HttpServletRequest;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.TreeMap;

/**
 * Public endpoint that Twilio POSTs delivery-receipt callbacks to.
 *
 * <p>Twilio includes an {@code X-Twilio-Signature} header on every callback.
 * To prevent spoofed status updates, we recompute the HMAC-SHA1 signature
 * from the full request URL + the sorted POST parameters and reject anything
 * that doesn't match. (See <a href="https://www.twilio.com/docs/usage/webhooks/webhooks-security">Twilio webhook security</a>.)
 *
 * <p>If {@code TWILIO_AUTH_TOKEN} isn't configured (typical in dev), signature
 * verification is skipped with a warning so local testing still works.
 */
@RestController
@RequestMapping("/api/whatsapp")
public class WhatsAppWebhookController {

    private static final Logger log = LoggerFactory.getLogger(WhatsAppWebhookController.class);

    @Autowired
    private WhatsAppNotificationService whatsAppNotificationService;

    @Value("${app.twilio.auth-token:}")
    private String twilioAuthToken;

    @Value("${app.twilio.verify-signature:true}")
    private boolean verifySignature;

    /**
     * Receives Twilio's status callbacks. Twilio sends standard HTML form
     * URL-encoded fields:
     * <ul>
     *   <li>{@code MessageSid}    — Twilio's per-message ID</li>
     *   <li>{@code MessageStatus} — queued | sent | delivered | read | failed | undelivered</li>
     *   <li>{@code ErrorCode}     — numeric error (only on failure)</li>
     *   <li>{@code ErrorMessage}  — human-readable error (only on failure)</li>
     * </ul>
     *
     * Returns 204 (No Content) so Twilio doesn't retry.
     */
    @PostMapping(value = "/twilio-callback",
                 consumes = {MediaType.APPLICATION_FORM_URLENCODED_VALUE, MediaType.ALL_VALUE})
    public ResponseEntity<Void> twilioCallback(
            @RequestParam(value = "MessageSid",    required = false) String messageSid,
            @RequestParam(value = "MessageStatus", required = false) String messageStatus,
            @RequestParam(value = "ErrorCode",     required = false) String errorCode,
            @RequestParam(value = "ErrorMessage",  required = false) String errorMessage,
            @RequestHeader(value = "X-Twilio-Signature", required = false) String signature,
            HttpServletRequest request) {

        if (verifySignature && !isBlank(twilioAuthToken)) {
            if (!verifyTwilioSignature(request, signature, twilioAuthToken)) {
                log.warn("Twilio callback REJECTED: bad signature (sid={}, state={})",
                        messageSid, messageStatus);
                return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
            }
        } else {
            log.debug("Twilio callback signature verification skipped (no auth token / disabled)");
        }

        if (messageSid == null || messageStatus == null) {
            log.warn("Twilio callback ignored: missing MessageSid or MessageStatus");
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        }

        log.info("Twilio callback: sid={} status={} errorCode={} errorMessage={}",
                messageSid, messageStatus, errorCode, errorMessage);

        whatsAppNotificationService.applyTwilioStatusCallback(
                messageSid, messageStatus, errorCode, errorMessage);

        return ResponseEntity.noContent().build();
    }

    /**
     * Recomputes Twilio's HMAC-SHA1 signature.
     * Algorithm: HMAC-SHA1 of (full request URL + concatenation of every
     * "key+value" pair in alphabetical key order), keyed by the auth token,
     * base64-encoded.
     */
    private static boolean verifyTwilioSignature(HttpServletRequest request,
                                                 String headerSig, String authToken) {
        if (headerSig == null || headerSig.isBlank()) return false;
        try {
            String url = reconstructRequestUrl(request);
            // Sort form params by key
            Map<String, String[]> params = request.getParameterMap();
            TreeMap<String, String> sorted = new TreeMap<>();
            for (Map.Entry<String, String[]> e : params.entrySet()) {
                if (e.getValue() != null && e.getValue().length > 0) {
                    sorted.put(e.getKey(), e.getValue()[0]);
                }
            }
            StringBuilder sb = new StringBuilder(url);
            for (Map.Entry<String, String> e : sorted.entrySet()) {
                sb.append(e.getKey()).append(e.getValue());
            }

            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(authToken.getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
            byte[] hashed = mac.doFinal(sb.toString().getBytes(StandardCharsets.UTF_8));
            String expected = Base64.getEncoder().encodeToString(hashed);
            return constantTimeEquals(expected, headerSig.trim());
        } catch (Exception e) {
            log.error("Twilio signature verification crashed: {}", e.getMessage(), e);
            return false;
        }
    }

    private static String reconstructRequestUrl(HttpServletRequest request) {
        StringBuffer u = request.getRequestURL();
        if (request.getQueryString() != null && !request.getQueryString().isEmpty()) {
            u.append('?').append(request.getQueryString());
        }
        return u.toString();
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null || a.length() != b.length()) return false;
        int diff = 0;
        for (int i = 0; i < a.length(); i++) diff |= a.charAt(i) ^ b.charAt(i);
        return diff == 0;
    }

    private static boolean isBlank(String s) { return s == null || s.trim().isEmpty(); }
}
