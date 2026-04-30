package com.host.studen.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import jakarta.annotation.PostConstruct;
import java.util.Map;

/**
 * Simple language detection service for transcripts.
 * Detects common Indian language scripts in transcript text.
 */
@Service
public class LanguageTranslationService {

    private static final Logger log = LoggerFactory.getLogger(LanguageTranslationService.class);

    @Value("${app.translation.enabled:true}")
    private boolean translationEnabled;

    @PostConstruct
    public void init() {
        log.info("Language Detection Service initialized");
    }

    /**
     * Detects language based on character ranges
     */
    public String detectLanguage(String text) {
        if (text == null || text.isEmpty()) {
            return "en";
        }

        // Check for Telugu script (U+0C00 - U+0C7F)
        if (containsCharRange(text, 0x0C00, 0x0C7F)) {
            return "te";
        }
        
        // Check for Tamil script (U+0B80 - U+0BFF)
        if (containsCharRange(text, 0x0B80, 0x0BFF)) {
            return "ta";
        }
        
        // Check for Kannada script (U+0C80 - U+0CFF)
        if (containsCharRange(text, 0x0C80, 0x0CFF)) {
            return "kn";
        }
        
        // Check for Malayalam script (U+0D00 - U+0D7F)
        if (containsCharRange(text, 0x0D00, 0x0D7F)) {
            return "ml";
        }
        
        // Check for Devanagari/Hindi (U+0900 - U+097F)
        if (containsCharRange(text, 0x0900, 0x097F)) {
            return "hi";
        }
        
        // Default to English
        return "en";
    }

    private boolean containsCharRange(String text, int start, int end) {
        if (text == null) return false;
        int matchCount = 0;
        int totalScriptChars = 0;
        
        for (char c : text.toCharArray()) {
            if (c >= start && c <= end) {
                matchCount++;
                totalScriptChars++;
            } else if (Character.isLetter(c)) {
                totalScriptChars++;
            }
        }
        
        //If 40% or more of letters are from this script, assume this language
        return totalScriptChars > 0 && matchCount > (totalScriptChars * 0.4);
    }

    /**
     * Gets language name from code
     */
    public String getLanguageName(String langCode) {
        return switch (langCode) {
            case "te" -> "Telugu";
            case "ta" -> "Tamil";
            case "kn" -> "Kannada";
            case "ml" -> "Malayalam";
            case "hi" -> "Hindi";
            case "en" -> "English";
            default -> "Unknown";
        };
    }

    /**
     * Enhance transcript with language info
     */
    public String enhanceTranscriptText(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        
        String langCode = detectLanguage(text);
        if ("en".equals(langCode)) {
            return text;
        }
        
        String langName = getLanguageName(langCode);
        log.info("Detected {} transcript: {}...", langName, 
                text.substring(0, Math.min(50, text.length())));
        return text;
    }

    /**
     * Check if text is non-English
     */
    public boolean isNonEnglish(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        String langCode = detectLanguage(text);
        return !"en".equals(langCode);
    }

    /**
     * Detect and translate (returns original for now - requires external API for full translation)
     */
    public Map<String, Object> detectAndTranslate(String text) {
        String langCode = detectLanguage(text);
        String langName = getLanguageName(langCode);
        
        return Map.of(
                "language", langCode,
                "languageName", langName,
                "originalText", text,
                "translatedText", text,
                "requiresTranslation", !"en".equals(langCode)
        );
    }
}

