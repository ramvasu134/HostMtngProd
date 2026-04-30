package com.host.studen.service;

import com.host.studen.model.Recording;
import com.host.studen.model.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import jakarta.annotation.PostConstruct;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Sends recording-ready events to the standalone Node notification service.
 *
 * The call is fire-and-forget from the caller perspective:
 * - this service method runs asynchronously
 * - failures are retried and only logged (no request thread crash)
 */
@Service
public class ExternalNotificationService {

    private static final Logger log = LoggerFactory.getLogger(ExternalNotificationService.class);
    private boolean externalNotificationReady;

    @Value("${app.notification.external.enabled:false}")
    private boolean externalNotificationEnabled;

    @Value("${app.notification.external.url:}")
    private String externalNotificationUrl;

    @Value("${app.notification.external.token:}")
    private String externalNotificationToken;

    @Value("${app.notification.external.max-attempts:3}")
    private int maxAttempts;

    @Value("${app.notification.external.retry-delay-ms:2000}")
    private long retryDelayMs;

    @Value("${app.public-url:http://localhost:8080}")
    private String publicUrl;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .build();

    @PostConstruct
    public void init() {
        if (!externalNotificationEnabled) {
            externalNotificationReady = false;
            log.info("External notification service is disabled (app.notification.external.enabled=false).");
            return;
        }
        if (isBlank(externalNotificationUrl)) {
            externalNotificationReady = false;
            log.warn("External notification auto-disabled: URL is missing. Set NOTIFICATION_EXTERNAL_URL to enable.");
            return;
        }
        try {
            URI parsedUri = URI.create(externalNotificationUrl.trim());
            if (isBlank(parsedUri.getScheme()) || isBlank(parsedUri.getHost())) {
                externalNotificationReady = false;
                log.warn("External notification auto-disabled: URL is invalid (missing scheme/host): {}", externalNotificationUrl);
                return;
            }
            externalNotificationUrl = parsedUri.toString();
            externalNotificationReady = true;
            log.info("External notification service is ready: {}", externalNotificationUrl);
        } catch (Exception e) {
            externalNotificationReady = false;
            log.warn("External notification auto-disabled: invalid URL '{}': {}", externalNotificationUrl, e.getMessage());
        }
    }

    @Async
    public void notifyRecordingReady(User teacher, Recording recording) {
        try {
            if (!externalNotificationReady) {
                return;
            }
            if (teacher == null || recording == null) {
                log.debug("External notification skipped: teacher/recording missing");
                return;
            }
            if (!teacher.isWhatsappNotificationsEnabled()) {
                return;
            }
            if (isBlank(teacher.getWhatsappNumber())) {
                return;
            }
            String recordingUrl = publicUrl.replaceAll("/+$", "") + "/host/recordings#rec-" + recording.getId();
            String payload = buildJsonPayload(teacher.getWhatsappNumber(), recordingUrl);
            sendWithRetry(payload, teacher.getWhatsappNumber(), recording.getId());
        } catch (Exception e) {
            log.error("External notification failed unexpectedly for recording {}: {}",
                    recording != null ? recording.getId() : "?", e.getMessage(), e);
        }
    }

    private void sendWithRetry(String jsonPayload, String phoneNumber, Long recordingId) {
        for (int attempt = 1; attempt <= Math.max(1, maxAttempts); attempt++) {
            try {
                HttpRequest.Builder builder = HttpRequest.newBuilder()
                        .uri(URI.create(externalNotificationUrl))
                        .timeout(Duration.ofSeconds(15))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(jsonPayload, StandardCharsets.UTF_8));

                if (!isBlank(externalNotificationToken)) {
                    builder.header("x-notification-token", externalNotificationToken);
                }

                HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
                int statusCode = response.statusCode();
                if (statusCode >= 200 && statusCode < 300) {
                    log.info("External notification accepted for recording {} on attempt {}", recordingId, attempt);
                    return;
                }

                log.warn("External notification attempt {}/{} failed for recording {} (status {}): {}",
                        attempt, maxAttempts, recordingId, statusCode, safeBody(response.body()));
                if (attempt < maxAttempts) {
                    sleepQuietly(retryDelayMs);
                }
            } catch (Exception e) {
                log.warn("External notification attempt {}/{} failed for {}: {}",
                        attempt, maxAttempts, phoneNumber, e.getMessage());
                if (attempt < maxAttempts) {
                    sleepQuietly(retryDelayMs);
                }
            }
        }
    }

    private static String buildJsonPayload(String phoneNumber, String recordingUrl) {
        return "{"
                + "\"phoneNumber\":\"" + escapeJson(phoneNumber) + "\","
                + "\"recordingUrl\":\"" + escapeJson(recordingUrl) + "\""
                + "}";
    }

    private static String escapeJson(String value) {
        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"");
    }

    private static String safeBody(String body) {
        if (body == null) return "";
        String trimmed = body.replaceAll("\\s+", " ").trim();
        return trimmed.length() > 200 ? trimmed.substring(0, 200) + "..." : trimmed;
    }

    private static void sleepQuietly(long milliseconds) {
        try {
            Thread.sleep(milliseconds);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
