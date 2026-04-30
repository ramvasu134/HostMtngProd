package com.host.studen.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    // Configurable allowed origins - default allows same-origin only in production
    // For development, set app.websocket.allowed-origins=* in application-dev.properties
    @Value("${app.websocket.allowed-origins:}")
    private String allowedOrigins;

    @Override
    public void configureMessageBroker(MessageBrokerRegistry config) {
        config.enableSimpleBroker("/topic", "/queue");
        config.setApplicationDestinationPrefixes("/app");
        config.setUserDestinationPrefix("/user");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        var endpoint = registry.addEndpoint("/ws");

        // Default origins always allowed — covers local browser dev AND the
        // Capacitor mobile WebView (capacitor://localhost on iOS, http://localhost
        // on Android). Custom domains are appended via app.websocket.allowed-origins.
        java.util.List<String> origins = new java.util.ArrayList<>(java.util.List.of(
                "http://localhost:*",
                "https://localhost:*",
                "capacitor://localhost",
                "ionic://localhost"
        ));
        if (allowedOrigins != null && !allowedOrigins.isBlank()) {
            for (String o : allowedOrigins.split(",")) {
                String trimmed = o.trim();
                if (!trimmed.isEmpty()) origins.add(trimmed);
            }
        }
        endpoint.setAllowedOriginPatterns(origins.toArray(new String[0]));

        endpoint.withSockJS();
    }
}

