package com.host.studen.service;

import com.host.studen.model.Recording;
import com.host.studen.model.Transcript;
import com.host.studen.repository.RecordingRepository;
import com.host.studen.repository.TranscriptRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Scheduled service for automatic cleanup of old recordings.
 * Runs daily and deletes recordings older than the configured retention period.
 */
@Service
public class RecordingCleanupService {

    private static final Logger log = LoggerFactory.getLogger(RecordingCleanupService.class);

    @Value("${app.recording.retention-days:7}")
    private int retentionDays;

    @Value("${app.recording.cleanup.enabled:true}")
    private boolean cleanupEnabled;

    @Autowired
    private RecordingRepository recordingRepository;

    @Autowired
    private TranscriptRepository transcriptRepository;

    /**
     * Scheduled job that runs daily at 2 AM to cleanup old recordings.
     * Cron: second minute hour day-of-month month day-of-week
     */
    @Scheduled(cron = "${app.recording.cleanup.cron:0 0 2 * * ?}")
    @Transactional
    public void cleanupOldRecordings() {
        if (!cleanupEnabled) {
            log.info("Recording cleanup is disabled. Skipping...");
            return;
        }

        log.info("Starting scheduled recording cleanup. Retention period: {} days", retentionDays);
        
        LocalDateTime cutoffDate = LocalDateTime.now().minusDays(retentionDays);
        List<Recording> oldRecordings = recordingRepository.findOldRecordings(
                cutoffDate, Recording.RecordingStatus.DELETED);

        if (oldRecordings.isEmpty()) {
            log.info("No old recordings found to cleanup.");
            return;
        }

        int deletedCount = 0;
        int errorCount = 0;
        long freedBytes = 0;

        for (Recording recording : oldRecordings) {
            try {
                freedBytes += recording.getFileSize();
                deleteRecordingCompletely(recording);
                deletedCount++;
            } catch (Exception e) {
                errorCount++;
                log.error("Failed to delete recording {}: {}", recording.getId(), e.getMessage());
            }
        }

        log.info("Recording cleanup completed. Deleted: {}, Errors: {}, Freed: {} MB",
                deletedCount, errorCount, freedBytes / (1024 * 1024));
    }

    /**
     * Delete a recording completely (file + transcripts + database record)
     */
    private void deleteRecordingCompletely(Recording recording) {
        Long recordingId = recording.getId();
        
        // 1. Delete associated transcripts
        List<Transcript> transcripts = transcriptRepository.findByRecordingId(recordingId);
        if (!transcripts.isEmpty()) {
            transcriptRepository.deleteAll(transcripts);
            log.debug("Deleted {} transcripts for recording {}", transcripts.size(), recordingId);
        }

        // 2. Delete the file from disk
        try {
            Path filePath = Paths.get(recording.getFilePath());
            if (Files.exists(filePath)) {
                Files.delete(filePath);
                log.debug("Deleted file: {}", filePath);
            }
        } catch (IOException e) {
            log.warn("Could not delete recording file {}: {}", recording.getFilePath(), e.getMessage());
        }

        // 3. Mark as deleted in database (or delete completely)
        recording.setStatus(Recording.RecordingStatus.DELETED);
        recordingRepository.save(recording);
        
        log.info("Recording {} cleaned up (age: {} days)", 
                recordingId, 
                java.time.temporal.ChronoUnit.DAYS.between(recording.getCreatedAt(), LocalDateTime.now()));
    }

    /**
     * Manual cleanup trigger (can be called from admin API)
     */
    @Transactional
    public CleanupResult triggerManualCleanup() {
        log.info("Manual cleanup triggered");
        
        LocalDateTime cutoffDate = LocalDateTime.now().minusDays(retentionDays);
        List<Recording> oldRecordings = recordingRepository.findOldRecordings(
                cutoffDate, Recording.RecordingStatus.DELETED);

        int deletedCount = 0;
        int errorCount = 0;
        long freedBytes = 0;

        for (Recording recording : oldRecordings) {
            try {
                freedBytes += recording.getFileSize();
                deleteRecordingCompletely(recording);
                deletedCount++;
            } catch (Exception e) {
                errorCount++;
                log.error("Failed to delete recording {}: {}", recording.getId(), e.getMessage());
            }
        }

        return new CleanupResult(deletedCount, errorCount, freedBytes);
    }

    /**
     * Get statistics about recordings
     */
    public RecordingStats getRecordingStats() {
        long totalRecordings = recordingRepository.count();
        long deletedRecordings = recordingRepository.countByStatus(Recording.RecordingStatus.DELETED);
        long activeRecordings = totalRecordings - deletedRecordings;
        
        LocalDateTime cutoffDate = LocalDateTime.now().minusDays(retentionDays);
        List<Recording> oldRecordings = recordingRepository.findOldRecordings(
                cutoffDate, Recording.RecordingStatus.DELETED);
        
        long pendingCleanup = oldRecordings.size();
        long pendingCleanupBytes = oldRecordings.stream()
                .mapToLong(Recording::getFileSize)
                .sum();

        return new RecordingStats(activeRecordings, deletedRecordings, pendingCleanup, 
                pendingCleanupBytes, retentionDays);
    }

    /**
     * Result of cleanup operation
     */
    public record CleanupResult(int deletedCount, int errorCount, long freedBytes) {
        public String getFreedMB() {
            return String.format("%.2f", freedBytes / (1024.0 * 1024.0));
        }
    }

    /**
     * Recording statistics
     */
    public record RecordingStats(long activeRecordings, long deletedRecordings, 
                                  long pendingCleanup, long pendingCleanupBytes, int retentionDays) {
        public String getPendingCleanupMB() {
            return String.format("%.2f", pendingCleanupBytes / (1024.0 * 1024.0));
        }
    }
}

