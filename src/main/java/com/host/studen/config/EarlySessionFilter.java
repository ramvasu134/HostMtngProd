package com.host.studen.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Eagerly initialises the HTTP session and enlarges the response buffer for
 * HTML page requests.
 *
 * <p>Why this exists:
 * <ol>
 *   <li>Several Thymeleaf templates (notably {@code login.html}) embed a
 *   sizeable {@code <style>} block, so the rendered HTML easily exceeds
 *   Tomcat's default 8&nbsp;KB response buffer. When that buffer is flushed
 *   mid-page, the response becomes "committed" — at which point any later
 *   call that needs to set a header (for instance, the CSRF token's first
 *   access creating a new {@code JSESSIONID} cookie) fails with
 *   {@code IllegalStateException: Cannot create a session after the response
 *   has been committed}.</li>
 *   <li>By doing two cheap things at the very start of the chain — touching
 *   the session and bumping the buffer to 32&nbsp;KB — we prevent that race
 *   without changing any application logic.</li>
 * </ol>
 *
 * <p>Static assets, API endpoints and the WebSocket handshake are skipped so
 * the JSON / WebSocket clients (including the wrapped Capacitor mobile app)
 * stay stateless.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class EarlySessionFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (shouldPrepare(request)) {
            // Bump from default 8 KB to 32 KB so big Thymeleaf pages don't
            // trigger a mid-render flush before the form/CSRF tags resolve.
            try {
                response.setBufferSize(32 * 1024);
            } catch (IllegalStateException ignored) {
                // Already committed by an earlier filter — nothing to do.
            }

            // Force-create the session so the JSESSIONID cookie header is
            // attached before Tomcat flushes the body buffer.
            request.getSession(true);
        }
        chain.doFilter(request, response);
    }

    private static boolean shouldPrepare(HttpServletRequest request) {
        if (!"GET".equalsIgnoreCase(request.getMethod())) {
            return false;
        }
        String path = request.getRequestURI();
        if (path == null) return false;
        // Skip static + API + WebSocket + actuator paths.
        if (path.startsWith("/css/") || path.startsWith("/js/") || path.startsWith("/images/")
                || path.startsWith("/favicon") || path.equals("/error")
                || path.startsWith("/api/") || path.startsWith("/ws") || path.startsWith("/actuator/")
                || path.startsWith("/h2-console/")) {
            return false;
        }
        // Apply only when the client is asking for HTML (or any-content default).
        String accept = request.getHeader("Accept");
        if (accept == null || accept.isEmpty()) return true;
        return accept.contains("text/html") || accept.contains("*/*");
    }
}
