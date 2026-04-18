package com.host.studen.config;

import com.host.studen.security.CustomAuthenticationProvider;
import com.host.studen.security.CustomAuthenticationSuccessHandler;
import com.host.studen.security.CustomUserDetailsService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.builders.AuthenticationManagerBuilder;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.header.writers.XXssProtectionHeaderWriter;

import java.util.Arrays;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    @Autowired
    private CustomUserDetailsService userDetailsService;

    @Autowired
    private CustomAuthenticationSuccessHandler successHandler;

    @Autowired
    private CustomAuthenticationProvider customAuthenticationProvider;

    @Autowired
    private Environment environment;

    @Bean
    public AuthenticationManager authenticationManager(HttpSecurity http) throws Exception {
        AuthenticationManagerBuilder authenticationManagerBuilder =
                http.getSharedObject(AuthenticationManagerBuilder.class);
        // Use custom authentication provider for handling teacherName + username login
        authenticationManagerBuilder.authenticationProvider(customAuthenticationProvider);
        return authenticationManagerBuilder.build();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        // Check if we're in dev profile (H2 console needs frame options disabled)
        boolean isDevProfile = Arrays.asList(environment.getActiveProfiles()).contains("dev");
        
        http
            .csrf(csrf -> csrf
                // CSRF protection disabled for API and WebSocket endpoints
                // WebSocket uses its own STOMP-level authentication
                .ignoringRequestMatchers("/api/**", "/ws/**")
            )
            .authorizeHttpRequests(auth -> auth
                // Public endpoints
                .requestMatchers("/", "/login", "/admin/login", "/register", "/css/**", "/js/**",
                        "/images/**", "/favicon.ico", "/error").permitAll()
                // H2 console - only in dev profile
                .requestMatchers("/h2-console/**").permitAll()
                // Actuator health endpoints - public for load balancer health checks
                .requestMatchers("/actuator/health/**", "/actuator/health").permitAll()
                // Other actuator endpoints require authentication
                .requestMatchers("/actuator/**").authenticated()
                // Auth API public
                .requestMatchers("/api/auth/**").permitAll()
                // Role-based access
                .requestMatchers("/admin/**").hasRole("ADMIN")
                .requestMatchers("/host/**").hasRole("HOST")
                .requestMatchers("/student/**").hasRole("STUDENT")
                .requestMatchers("/meeting/**").authenticated()
                .requestMatchers("/api/**").authenticated()
                .anyRequest().authenticated()
            )
            .formLogin(form -> form
                .loginPage("/login")
                .loginProcessingUrl("/login")
                .usernameParameter("username")
                .passwordParameter("password")
                .successHandler(successHandler)
                .failureUrl("/login?error=true")
                .permitAll()
            )
            .logout(logout -> logout
                .logoutUrl("/logout")
                .logoutSuccessUrl("/login?logout=true")
                .invalidateHttpSession(true)
                .deleteCookies("JSESSIONID")
                .permitAll()
            )
            .headers(headers -> {
                // Security headers
                headers.xssProtection(xss -> xss.headerValue(XXssProtectionHeaderWriter.HeaderValue.ENABLED_MODE_BLOCK));
                headers.contentTypeOptions(opts -> {});
                
                if (isDevProfile) {
                    // Allow H2 console iframe in dev
                    headers.frameOptions(frame -> frame.sameOrigin());
                } else {
                    // Deny framing in production
                    headers.frameOptions(frame -> frame.deny());
                }
            });

        return http.build();
    }
}

