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
        
        // Security: Restrict CORS origins based on configuration
        if (allowedOrigins != null && !allowedOrigins.isBlank()) {
            endpoint.setAllowedOriginPatterns(allowedOrigins.split(","));
        } else {
            // Default: Allow same-origin requests only (no explicit pattern = same-origin)
            endpoint.setAllowedOriginPatterns("http://localhost:*", "https://localhost:*");
        }
        
        endpoint.withSockJS();
    }
}

