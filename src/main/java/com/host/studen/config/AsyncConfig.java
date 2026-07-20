package com.host.studen.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.lang.reflect.Method;
import java.util.concurrent.Executor;

/**
 * Dedicated thread pool for per-recording notification fan-out
 * ({@code WhatsAppNotificationService.notifyTeacherOnRecording} and
 * {@code ExternalNotificationService.notifyRecordingReady}).
 *
 * <h2>Why this exists</h2>
 * Without a named executor, Spring Boot's {@code @Async} falls back to the
 * default {@code applicationTaskExecutor}, which only has <b>8</b> core
 * threads and an <b>unbounded queue</b> — meaning it never grows past 8
 * concurrently, it just silently queues everything else behind them.
 * Each notification task can legitimately block for up to ~50s (Baileys
 * cold-start) or through several retry-with-backoff attempts. With a
 * classroom of only 4 students (each recording chunk fires 2 async tasks),
 * that pool fills up completely and every notification after the 4th
 * student sits queued, appearing to the teacher as "recordings just never
 * arrive" — this was exactly the reported bug ("2-3 recordings send, the
 * 3rd/4th don't").
 *
 * <p>Sized generously for the "50 participants, zero tolerance" requirement:
 * 50 students × 2 notification tasks each = 100 potential concurrent tasks
 * in the worst case (everyone's recording chunk lands in the same instant).
 */
@Configuration
public class AsyncConfig implements AsyncConfigurer {

    private static final Logger log = LoggerFactory.getLogger(AsyncConfig.class);

    public static final String NOTIFICATION_EXECUTOR = "notificationExecutor";

    @Bean(NOTIFICATION_EXECUTOR)
    public Executor notificationExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(24);
        executor.setMaxPoolSize(80);
        executor.setQueueCapacity(300);
        executor.setThreadNamePrefix("notify-");
        // Never silently drop a notification task once the pool+queue is
        // saturated — run it on the caller thread instead so it still
        // completes (just slower), rather than throwing a RejectedExecutionException.
        executor.setRejectedExecutionHandler(new java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }

    /**
     * Fallback executor for any other {@code @Async} method in the app that
     * doesn't specify an explicit executor name — keeps default Spring Boot
     * sizing behavior for everything unrelated to notifications, but logs
     * uncaught exceptions instead of swallowing them.
     */
    @Override
    public Executor getAsyncExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(8);
        executor.setMaxPoolSize(40);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("async-");
        executor.setRejectedExecutionHandler(new java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }

    @Override
    public org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
        return (Throwable ex, Method method, Object... params) ->
                log.error("Uncaught async exception in {}: {}", method.getName(), ex.getMessage(), ex);
    }
}
