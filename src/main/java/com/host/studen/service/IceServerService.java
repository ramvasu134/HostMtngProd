package com.host.studen.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Supplies WebRTC {@code iceServers} configuration to meeting pages.
 *
 * <h2>Why this exists</h2>
 * The app previously hardcoded a STUN-only ICE list client-side. STUN alone
 * cannot establish a peer connection when either side is behind a
 * symmetric/restrictive NAT (common on school Wi-Fi and mobile data) — the
 * connection just silently fails with no audio, which matched the reported
 * "some students have no audio" bug. A TURN relay is required to guarantee
 * connectivity ("zero tolerance") regardless of network type.
 *
 * <p>To stay at zero ongoing cost, this integrates with the Open Relay
 * Project (metered.ca) free TURN tier (20 GB/month free, no card required).
 * If the admin hasn't signed up yet, this gracefully falls back to the
 * original STUN-only list so nothing breaks — TURN is purely additive.
 *
 * <p>Credentials are cached for an hour since Metered rotates them roughly
 * daily and this avoids hitting their REST API on every single participant
 * join (which would happen 50x per meeting at full scale otherwise).
 */
@Service
public class IceServerService {

    private static final Logger log = LoggerFactory.getLogger(IceServerService.class);
    private static final Duration CACHE_TTL = Duration.ofHours(1);

    @Value("${app.turn.app-name:}")
    private String turnAppName;

    @Value("${app.turn.api-key:}")
    private String turnApiKey;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .build();
    private final ObjectMapper mapper = new ObjectMapper();

    private final AtomicReference<List<Map<String, Object>>> cached = new AtomicReference<>();
    private volatile Instant cachedAt = Instant.EPOCH;

    private static final List<Map<String, Object>> STUN_FALLBACK = List.of(
            Map.of("urls", "stun:stun.l.google.com:19302"),
            Map.of("urls", "stun:stun1.l.google.com:19302"),
            Map.of("urls", "stun:stun2.l.google.com:19302"),
            Map.of("urls", "stun:stun3.l.google.com:19302"),
            Map.of("urls", "stun:stun4.l.google.com:19302")
    );

    public List<Map<String, Object>> getIceServers() {
        if (isBlank(turnAppName) || isBlank(turnApiKey)) {
            return STUN_FALLBACK;
        }
        List<Map<String, Object>> snapshot = cached.get();
        if (snapshot != null && Instant.now().isBefore(cachedAt.plus(CACHE_TTL))) {
            return snapshot;
        }
        synchronized (this) {
            snapshot = cached.get();
            if (snapshot != null && Instant.now().isBefore(cachedAt.plus(CACHE_TTL))) {
                return snapshot;
            }
            List<Map<String, Object>> fetched = fetchFromMetered();
            if (fetched != null && !fetched.isEmpty()) {
                cached.set(fetched);
                cachedAt = Instant.now();
                return fetched;
            }
            // Fetch failed — serve last-known-good cache if we have one, else STUN-only.
            return snapshot != null ? snapshot : STUN_FALLBACK;
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> fetchFromMetered() {
        try {
            String url = "https://" + turnAppName.trim() + ".metered.live/api/v1/turn/credentials?apiKey=" + turnApiKey.trim();
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();
            HttpResponse<String> res = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() < 200 || res.statusCode() >= 300) {
                log.warn("TURN credential fetch failed (HTTP {}): {}", res.statusCode(), res.body());
                return null;
            }
            JsonNode arr = mapper.readTree(res.body());
            List<Map<String, Object>> result = mapper.convertValue(arr, List.class);
            log.info("Fetched {} ICE server entries from Open Relay (metered.ca)", result.size());
            return result;
        } catch (Exception e) {
            log.warn("TURN credential fetch error: {}", e.getMessage());
            return null;
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
