package com.host.studen.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Cross-Origin Resource Sharing for the API and WebSocket layer.
 *
 * The mobile (Capacitor) app loads the remote Render-hosted page inside a
 * native WebView whose XHR/fetch origin is {@code capacitor://localhost} on
 * iOS and {@code http://localhost} on Android. Without explicit CORS allow-
 * listing, every API call from the wrapped mobile app is rejected.
 *
 * In addition to those two native origins, we honour any value set in
 * {@code app.cors.allowed-origins} so production deployments can add their
 * own domains without code changes.
 */
@Configuration
public class CorsConfig {

    @Value("${app.cors.allowed-origins:}")
    private String additionalAllowedOrigins;

    /** Default origins always permitted — covers Capacitor on Android & iOS. */
    private static final List<String> DEFAULT_MOBILE_ORIGINS = Arrays.asList(
            "capacitor://localhost",
            "ionic://localhost",
            "http://localhost",
            "http://localhost:*",
            "https://localhost",
            "https://localhost:*"
    );

    @Bean
    public UrlBasedCorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration cfg = new CorsConfiguration();

        List<String> originPatterns = new ArrayList<>(DEFAULT_MOBILE_ORIGINS);
        if (additionalAllowedOrigins != null && !additionalAllowedOrigins.isBlank()) {
            for (String o : additionalAllowedOrigins.split(",")) {
                String trimmed = o.trim();
                if (!trimmed.isEmpty()) originPatterns.add(trimmed);
            }
        }

        cfg.setAllowedOriginPatterns(originPatterns);
        cfg.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS", "HEAD"));
        cfg.setAllowedHeaders(List.of("*"));
        cfg.setExposedHeaders(Arrays.asList("Authorization", "Content-Disposition"));
        cfg.setAllowCredentials(true);
        cfg.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", cfg);
        source.registerCorsConfiguration("/ws/**", cfg);
        return source;
    }
}
