package com.host.studen.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Web MVC configuration for serving static resources including
 * recordings, uploads, and profile photos.
 * 
 * SECURITY NOTE: These paths expose file system directories.
 * Ensure proper authentication is enforced at the controller/security layer.
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private static final Logger log = LoggerFactory.getLogger(WebMvcConfig.class);

    @Value("${app.recording.dir:./recordings}")
    private String recordingDir;

    @Value("${app.upload.dir:./uploads}")
    private String uploadDir;

    @Value("${app.profile-photos.dir:./profile-photos}")
    private String profilePhotosDir;

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // Recordings directory
        Path recordingPath = createDirectoryIfNotExists(recordingDir);
        registry.addResourceHandler("/recordings/**")
                .addResourceLocations("file:" + recordingPath.toString() + "/")
                .setCachePeriod(3600);  // 1 hour cache

        // Uploads directory
        Path uploadPath = createDirectoryIfNotExists(uploadDir);
        registry.addResourceHandler("/uploads/**")
                .addResourceLocations("file:" + uploadPath.toString() + "/")
                .setCachePeriod(3600);

        // Profile photos directory
        Path profilePhotosPath = createDirectoryIfNotExists(profilePhotosDir);
        registry.addResourceHandler("/profile-photos/**")
                .addResourceLocations("file:" + profilePhotosPath.toString() + "/")
                .setCachePeriod(86400);  // 24 hour cache for profile photos
    }

    /**
     * Creates directory if it doesn't exist and returns the absolute path.
     */
    private Path createDirectoryIfNotExists(String dirPath) {
        Path path = Paths.get(dirPath).toAbsolutePath().normalize();
        try {
            if (!Files.exists(path)) {
                Files.createDirectories(path);
                log.info("Created directory: {}", path);
            }
        } catch (IOException e) {
            log.warn("Could not create directory {}: {}", path, e.getMessage());
        }
        return path;
    }
}

