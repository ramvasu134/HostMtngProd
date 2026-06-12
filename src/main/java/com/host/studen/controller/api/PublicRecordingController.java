package com.host.studen.controller.api;

import com.host.studen.model.Recording;
import com.host.studen.service.RecordingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.UrlResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Public (unauthenticated) audio endpoint so Twilio/WhatsApp can fetch
 * recording clips as message media. Security model: the URL contains both the
 * recording id AND the random UUID disk filename — the UUID is unguessable and
 * never listed anywhere public, so it works as a per-recording access token.
 *
 * <p>Registered as permitAll in {@code SecurityConfig}.
 */
@RestController
@RequestMapping("/api/public/recordings")
public class PublicRecordingController {

    private static final Logger log = LoggerFactory.getLogger(PublicRecordingController.class);

    @Autowired
    private RecordingService recordingService;

    @GetMapping("/{id}/{fileName:.+}")
    public ResponseEntity<UrlResource> media(@PathVariable Long id, @PathVariable String fileName) {
        try {
            Recording recording = recordingService.findById(id).orElse(null);
            if (recording == null || recording.getStatus() == Recording.RecordingStatus.DELETED) {
                return ResponseEntity.notFound().build();
            }

            Path path = recordingService.getRecordingPath(recording);
            // The UUID filename must match exactly — it is the access token.
            if (!path.getFileName().toString().equals(fileName) || !Files.exists(path)) {
                return ResponseEntity.notFound().build();
            }

            UrlResource resource = new UrlResource(path.toUri());
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(resolveContentType(recording, fileName)))
                    .contentLength(Files.size(path))
                    .body(resource);
        } catch (Exception e) {
            log.warn("Public recording media fetch failed for id {}: {}", id, e.getMessage());
            return ResponseEntity.internalServerError().build();
        }
    }

    /** Plain audio content type (no ";codecs=..." suffix — Twilio/WhatsApp want the bare type). */
    private static String resolveContentType(Recording recording, String fileName) {
        String stored = recording.getContentType();
        if (stored != null && !stored.isBlank()) {
            int semi = stored.indexOf(';');
            String bare = (semi > 0 ? stored.substring(0, semi) : stored).trim();
            if (bare.startsWith("audio/") || bare.startsWith("video/")) {
                return bare;
            }
        }
        String lower = fileName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".m4a") || lower.endsWith(".mp4")) return "audio/mp4";
        if (lower.endsWith(".ogg") || lower.endsWith(".oga")) return "audio/ogg";
        if (lower.endsWith(".mp3")) return "audio/mpeg";
        if (lower.endsWith(".aac")) return "audio/aac";
        if (lower.endsWith(".amr")) return "audio/amr";
        if (lower.endsWith(".wav")) return "audio/wav";
        return "audio/webm";
    }
}
