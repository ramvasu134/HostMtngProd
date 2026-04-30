package com.host.studen.controller.api;

import com.host.studen.dto.MeetingRequest;
import com.host.studen.model.*;
import com.host.studen.security.CustomUserDetails;
import com.host.studen.service.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/host")
public class HostApiController {

    @Autowired
    private UserService userService;

    @Autowired
    private MeetingService meetingService;

    @Autowired
    private TranscriptService transcriptService;

    @Autowired
    private ScheduleService scheduleService;

    @Autowired
    private NotificationService notificationService;

    @Autowired
    private RecordingService recordingService;

    @Autowired
    private WhatsAppNotificationService whatsAppNotificationService;

    // ===== Recordings =====

    @GetMapping("/recordings")
    public ResponseEntity<?> getRecordings(@AuthenticationPrincipal CustomUserDetails userDetails) {
        try {
            User host = userDetails.getUser();
            List<User> students = userService.findStudentsByTeacherName(host.getTeacherName());
            List<Map<String, Object>> result = new ArrayList<>();
            for (User student : students) {
                List<Recording> recs = recordingService.findByUser(student);
                if (recs.isEmpty()) continue;
                List<Map<String, Object>> recList = new ArrayList<>();
                for (Recording rec : recs) {
                    Map<String, Object> r = new HashMap<>();
                    r.put("id", rec.getId());
                    r.put("fileName", rec.getFileName());
                    r.put("durationSeconds", rec.getDurationSeconds());
                    r.put("fileSize", rec.getFileSize());
                    r.put("createdAt", rec.getCreatedAt() != null ? rec.getCreatedAt().format(DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm")) : "");
                    r.put("playUrl", "/api/meeting/recording/" + rec.getId() + "/play");
                    r.put("downloadUrl", "/api/meeting/recording/" + rec.getId() + "/download");
                    List<Transcript> trs = transcriptService.findByRecording(rec);
                    String combinedTranscript = trs.stream()
                            .map(Transcript::getContent)
                            .filter(Objects::nonNull)
                            .map(String::trim)
                            .filter(s -> !s.isEmpty())
                            .collect(Collectors.joining(" "));
                    r.put("transcript", combinedTranscript.isEmpty() ? null : combinedTranscript);
                    r.put("transcriptId", trs.isEmpty() ? null : trs.get(0).getId());
                    recList.add(r);
                }
                Map<String, Object> group = new HashMap<>();
                group.put("studentName", student.getDisplayName());
                group.put("recordings", recList);
                result.add(group);
            }
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", e.getMessage()));
        }
    }

    // ===== Student Management =====

    @PutMapping("/students/{id}")
    public ResponseEntity<?> updateStudent(@PathVariable Long id,
                                           @RequestBody Map<String, String> request,
                                           @AuthenticationPrincipal CustomUserDetails userDetails) {
        try {
            String displayName = request.get("displayName");
            String newPassword = request.get("newPassword");

            if (displayName != null && !displayName.trim().isEmpty()) {
                userService.updateStudentName(id, displayName.trim());
            }
            if (newPassword != null && !newPassword.trim().isEmpty()) {
                userService.resetStudentPassword(id, newPassword.trim());
            }
            if ((displayName == null || displayName.trim().isEmpty()) &&
                    (newPassword == null || newPassword.trim().isEmpty())) {
                return ResponseEntity.badRequest().body(Map.of("success", false, "message", "Nothing to update"));
            }
            return ResponseEntity.ok(Map.of("success", true, "message", "Student updated"));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", e.getMessage()));
        }
    }

    @GetMapping("/students/{id}/credentials")
    public ResponseEntity<?> getStudentCredentials(@PathVariable Long id,
                                                   @AuthenticationPrincipal CustomUserDetails userDetails) {
        try {
            Optional<User> studentOpt = userService.findById(id);
            if (studentOpt.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("success", false, "message", "Student not found"));
            }
            User student = studentOpt.get();
            User teacher = userDetails.getUser();
            // Security: only teacher who owns this student can view credentials
            if (!student.getTeacherName().equals(teacher.getTeacherName())) {
                return ResponseEntity.status(403).body(Map.of("success", false, "message", "Access denied"));
            }
            
            // SECURITY: Never expose passwords in API responses
            // Instead, indicate that password can be reset
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "username", student.getUsername(),
                    "displayName", student.getDisplayName(),
                    "passwordHint", "Password is stored securely. Use 'Reset Password' to set a new one."
            ));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", e.getMessage()));
        }
    }

    @PostMapping("/students/{id}/block")
    public ResponseEntity<?> blockStudent(@PathVariable Long id,
                                          @AuthenticationPrincipal CustomUserDetails userDetails) {
        try {
            userService.blockStudent(id);
            return ResponseEntity.ok(Map.of("success", true, "message", "Student blocked"));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", e.getMessage()));
        }
    }

    @PostMapping("/students/{id}/unblock")
    public ResponseEntity<?> unblockStudent(@PathVariable Long id,
                                            @AuthenticationPrincipal CustomUserDetails userDetails) {
        try {
            userService.unblockStudent(id);
            return ResponseEntity.ok(Map.of("success", true, "message", "Student unblocked"));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", e.getMessage()));
        }
    }

    @PostMapping("/students/{id}/mute")
    public ResponseEntity<?> muteStudent(@PathVariable Long id,
                                         @AuthenticationPrincipal CustomUserDetails userDetails) {
        return ResponseEntity.ok(Map.of("success", true, "message", "Mute signal sent"));
    }

    @PostMapping("/students/{id}/call")
    public ResponseEntity<?> callStudent(@PathVariable Long id,
                                         @AuthenticationPrincipal CustomUserDetails userDetails) {
        try {
            Optional<User> studentOpt = userService.findById(id);
            if (studentOpt.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("success", false, "message", "Student not found"));
            }
            User student = studentOpt.get();
            User teacher = userDetails.getUser();
            
            // Verify student belongs to this teacher
            if (!student.getTeacherName().equals(teacher.getTeacherName())) {
                return ResponseEntity.badRequest().body(Map.of("success", false, "message", "Access denied"));
            }
            
            // Create a call notification for the student
            notificationService.createNotification(student, "📞 Incoming Call",
                    "Your teacher " + teacher.getDisplayName() + " is calling you. Please join the meeting.",
                    NotificationType.MEETING_STARTED);
            
            return ResponseEntity.ok(Map.of("success", true, "message", "Call notification sent to " + student.getDisplayName()));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", e.getMessage()));
        }
    }

    @DeleteMapping("/students/{id}")
    public ResponseEntity<?> deleteStudent(@PathVariable Long id,
                                           @AuthenticationPrincipal CustomUserDetails userDetails) {
        try {
            userService.deleteStudent(id);
            return ResponseEntity.ok(Map.of("success", true, "message", "Student deleted"));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", e.getMessage()));
        }
    }

    // ===== Quick Meeting (Start Meeting from dashboard) =====

    @PostMapping("/quick-meeting/start")
    public ResponseEntity<?> startQuickMeeting(@AuthenticationPrincipal CustomUserDetails userDetails) {
        try {
            User host = userDetails.getUser();

            // ── Guard: must have at least one active schedule ──────────────
            List<Schedule> schedules = scheduleService.findActiveByTeacher(host);
            if (schedules.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", "Please create a schedule first before starting a meeting. Go to Schedules → New Schedule."
                ));
            }

            // If teacher already has a live meeting, return it
            List<Meeting> liveMeetings = meetingService.findLiveMeetings();
            for (Meeting m : liveMeetings) {
                if (m.getHost().getId().equals(host.getId())) {
                    return ResponseEntity.ok(Map.of(
                            "success", true,
                            "meetingCode", m.getMeetingCode(),
                            "meetingId", m.getId(),
                            "title", m.getTitle()
                    ));
                }
            }

            // Create a new quick meeting
            MeetingRequest req = new MeetingRequest();
            req.setTitle("Live Session – " +
                    DateTimeFormatter.ofPattern("dd MMM, HH:mm").format(LocalDateTime.now()));
            req.setRecordingEnabled(true);
            req.setChatEnabled(true);
            req.setMaxParticipants(50);

            Meeting meeting = meetingService.createMeeting(req, host);
            Meeting started = meetingService.startMeeting(meeting.getId(), host);

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "meetingCode", started.getMeetingCode(),
                    "meetingId", started.getId(),
                    "title", started.getTitle()
            ));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", e.getMessage()));
        }
    }

    @PostMapping("/quick-meeting/end")
    public ResponseEntity<?> endQuickMeeting(@RequestBody Map<String, Object> request,
                                              @AuthenticationPrincipal CustomUserDetails userDetails) {
        try {
            Long meetingId = Long.parseLong(request.get("meetingId").toString());
            meetingService.endMeeting(meetingId, userDetails.getUser());
            return ResponseEntity.ok(Map.of("success", true, "message", "Meeting ended successfully"));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", e.getMessage()));
        }
    }

    // ===== Transcript Management =====

    @GetMapping("/transcripts")
    public ResponseEntity<?> getAllTranscripts(@AuthenticationPrincipal CustomUserDetails userDetails) {
        try {
            User teacher = userDetails.getUser();
            List<User> students = userService.findStudentsByTeacherName(teacher.getTeacherName());
            
            List<Map<String, Object>> allTranscripts = new ArrayList<>();
            for (User student : students) {
                List<Transcript> transcripts = transcriptService.findByUser(student);
                for (Transcript t : transcripts) {
                    Map<String, Object> item = new HashMap<>();
                    item.put("id", t.getId());
                    item.put("content", t.getContent());
                    item.put("speakerName", t.getSpeakerName());
                    item.put("studentName", student.getDisplayName());
                    item.put("recordingId", t.getRecording() != null ? t.getRecording().getId() : null);
                    item.put("startTime", t.getStartTimeSeconds());
                    item.put("endTime", t.getEndTimeSeconds());
                    item.put("createdAt", t.getCreatedAt() != null ? t.getCreatedAt().toString() : null);
                    allTranscripts.add(item);
                }
            }
            return ResponseEntity.ok(allTranscripts);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", e.getMessage()));
        }
    }

    @GetMapping("/transcripts/recording/{recordingId}")
    public ResponseEntity<?> getTranscriptsByRecording(@PathVariable Long recordingId) {
        try {
            List<Transcript> transcripts = transcriptService.findByRecordingId(recordingId);
            List<Map<String, Object>> result = transcripts.stream().map(t -> {
                Map<String, Object> item = new HashMap<>();
                item.put("id", t.getId());
                item.put("content", t.getContent());
                item.put("speakerName", t.getSpeakerName());
                item.put("startTime", t.getStartTimeSeconds());
                item.put("endTime", t.getEndTimeSeconds());
                item.put("createdAt", t.getCreatedAt() != null ? t.getCreatedAt().toString() : null);
                return item;
            }).collect(Collectors.toList());
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", e.getMessage()));
        }
    }

    @PostMapping("/transcripts")
    public ResponseEntity<?> createTranscript(@RequestBody Map<String, Object> request,
                                              @AuthenticationPrincipal CustomUserDetails userDetails) {
        try {
            Long recordingId = Long.parseLong(request.get("recordingId").toString());
            String content = (String) request.get("content");
            String speakerName = (String) request.get("speakerName");
            Integer startTime = request.get("startTime") != null ? Integer.parseInt(request.get("startTime").toString()) : null;
            Integer endTime = request.get("endTime") != null ? Integer.parseInt(request.get("endTime").toString()) : null;

            Recording recording = recordingService.findById(recordingId)
                    .orElseThrow(() -> new RuntimeException("Recording not found"));

            Transcript transcript = transcriptService.createTranscript(
                    recording, recording.getRecordedBy(), content, speakerName, startTime, endTime);

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "Transcript created",
                    "id", transcript.getId()
            ));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", e.getMessage()));
        }
    }

    @DeleteMapping("/transcripts/{id}")
    public ResponseEntity<?> deleteTranscript(@PathVariable Long id) {
        try {
            transcriptService.deleteTranscript(id);
            return ResponseEntity.ok(Map.of("success", true, "message", "Transcript deleted"));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", e.getMessage()));
        }
    }

    @GetMapping("/transcripts/search")
    public ResponseEntity<?> searchTranscripts(@RequestParam String query) {
        try {
            List<Transcript> transcripts = transcriptService.searchByContent(query);
            List<Map<String, Object>> result = transcripts.stream().map(t -> {
                Map<String, Object> item = new HashMap<>();
                item.put("id", t.getId());
                item.put("content", t.getContent());
                item.put("speakerName", t.getSpeakerName());
                item.put("createdAt", t.getCreatedAt() != null ? t.getCreatedAt().toString() : null);
                return item;
            }).collect(Collectors.toList());
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", e.getMessage()));
        }
    }

    // ===== Schedule Management =====

    @GetMapping("/schedules")
    public ResponseEntity<?> getSchedules(@AuthenticationPrincipal CustomUserDetails userDetails) {
        try {
            User teacher = userDetails.getUser();
            List<Schedule> schedules = scheduleService.findByTeacher(teacher);
            List<Map<String, Object>> result = schedules.stream().map(s -> {
                Map<String, Object> item = new HashMap<>();
                item.put("id", s.getId());
                item.put("title", s.getTitle());
                item.put("description", s.getDescription());
                item.put("startTime", s.getScheduledStartTime().toString());
                item.put("endTime", s.getScheduledEndTime() != null ? s.getScheduledEndTime().toString() : null);
                item.put("active", s.isActive());
                item.put("notificationSent", s.isNotificationSent());
                item.put("createdAt", s.getCreatedAt().toString());
                return item;
            }).collect(Collectors.toList());
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", e.getMessage()));
        }
    }

    @PostMapping("/schedules")
    public ResponseEntity<?> createSchedule(@RequestBody Map<String, String> request,
                                            @AuthenticationPrincipal CustomUserDetails userDetails) {
        try {
            User teacher = userDetails.getUser();
            String title = request.get("title");
            String description = request.get("description");
            LocalDateTime startTime = LocalDateTime.parse(request.get("startTime"), DateTimeFormatter.ISO_LOCAL_DATE_TIME);
            LocalDateTime endTime = request.get("endTime") != null ?
                    LocalDateTime.parse(request.get("endTime"), DateTimeFormatter.ISO_LOCAL_DATE_TIME) : null;

            Schedule schedule = scheduleService.createSchedule(teacher, title, description, startTime, endTime);

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "Schedule created and students notified",
                    "id", schedule.getId()
            ));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", e.getMessage()));
        }
    }

    @PutMapping("/schedules/{id}")
    public ResponseEntity<?> updateSchedule(@PathVariable Long id,
                                            @RequestBody Map<String, String> request) {
        try {
            String title = request.get("title");
            String description = request.get("description");
            LocalDateTime startTime = LocalDateTime.parse(request.get("startTime"), DateTimeFormatter.ISO_LOCAL_DATE_TIME);
            LocalDateTime endTime = request.get("endTime") != null ?
                    LocalDateTime.parse(request.get("endTime"), DateTimeFormatter.ISO_LOCAL_DATE_TIME) : null;

            scheduleService.updateSchedule(id, title, description, startTime, endTime);
            return ResponseEntity.ok(Map.of("success", true, "message", "Schedule updated"));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", e.getMessage()));
        }
    }

    @DeleteMapping("/schedules/{id}")
    public ResponseEntity<?> deleteSchedule(@PathVariable Long id) {
        try {
            scheduleService.deleteSchedule(id);
            return ResponseEntity.ok(Map.of("success", true, "message", "Schedule deleted"));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", e.getMessage()));
        }
    }

    // ===== WhatsApp Notification Settings =====

    /** Returns the current teacher's WhatsApp notification settings (always fresh from DB). */
    @GetMapping("/whatsapp-settings")
    public ResponseEntity<?> getWhatsappSettings(@AuthenticationPrincipal CustomUserDetails userDetails) {
        try {
            User teacher = userService.findById(userDetails.getUserId())
                    .orElseThrow(() -> new RuntimeException("Teacher not found"));
            Map<String, Object> resp = new HashMap<>();
            resp.put("whatsappNumber", teacher.getWhatsappNumber() != null ? teacher.getWhatsappNumber() : "");
            resp.put("whatsappApiKey", teacher.getWhatsappApiKey() != null ? teacher.getWhatsappApiKey() : "");
            resp.put("whatsappNotificationsEnabled", teacher.isWhatsappNotificationsEnabled());
            return ResponseEntity.ok(resp);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", e.getMessage()));
        }
    }

    /**
     * Saves the teacher's WhatsApp number, CallMeBot API key, and notification preference.
     * Body: {
     *   "whatsappNumber": "9876543210",
     *   "whatsappApiKey": "1234567",
     *   "whatsappNotificationsEnabled": true
     * }
     *
     * Number normalisation: 10 digits → +91XXXXXXXXXX, otherwise prepends '+'.
     */
    @PutMapping("/whatsapp-settings")
    public ResponseEntity<?> updateWhatsappSettings(@RequestBody Map<String, Object> body,
                                                     @AuthenticationPrincipal CustomUserDetails userDetails) {
        try {
            User teacher = userService.findById(userDetails.getUserId())
                    .orElseThrow(() -> new RuntimeException("Teacher not found"));

            String rawNumber = body.containsKey("whatsappNumber")
                    ? String.valueOf(body.get("whatsappNumber")).trim() : null;
            String rawApiKey = body.containsKey("whatsappApiKey")
                    ? String.valueOf(body.get("whatsappApiKey")).trim() : null;
            Boolean enabled = body.containsKey("whatsappNotificationsEnabled")
                    ? Boolean.parseBoolean(String.valueOf(body.get("whatsappNotificationsEnabled")))
                    : null;

            if (rawNumber != null) {
                if (rawNumber.isEmpty() || "null".equalsIgnoreCase(rawNumber)) {
                    teacher.setWhatsappNumber(null);
                } else {
                    String normalised = normaliseNumber(rawNumber);
                    if (normalised == null) {
                        return ResponseEntity.badRequest().body(Map.of(
                                "success", false,
                                "message", "Invalid number. Examples: 9876543210 or +919876543210"));
                    }
                    teacher.setWhatsappNumber(normalised);
                }
            }
            if (rawApiKey != null) {
                if (rawApiKey.isEmpty() || "null".equalsIgnoreCase(rawApiKey)) {
                    teacher.setWhatsappApiKey(null);
                } else {
                    // Strip whitespace and any non-alphanumeric characters except common ones
                    String key = rawApiKey.replaceAll("\\s+", "");
                    teacher.setWhatsappApiKey(key);
                }
            }
            if (enabled != null) {
                teacher.setWhatsappNotificationsEnabled(enabled);
            }

            User saved = userService.save(teacher);

            Map<String, Object> resp = new HashMap<>();
            resp.put("success", true);
            resp.put("message", "WhatsApp settings saved ✓");
            resp.put("whatsappNumber", saved.getWhatsappNumber() != null ? saved.getWhatsappNumber() : "");
            resp.put("whatsappApiKey", saved.getWhatsappApiKey() != null ? saved.getWhatsappApiKey() : "");
            resp.put("whatsappNotificationsEnabled", saved.isWhatsappNotificationsEnabled());
            return ResponseEntity.ok(resp);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", e.getMessage()));
        }
    }

    /** Same normaliser logic as the service, kept separate to validate at API edge. */
    private String normaliseNumber(String raw) {
        if (raw == null || raw.trim().isEmpty()) return null;
        String stripped = raw.trim().replaceAll("[\\s\\-()]", "");
        String digits;
        if (stripped.startsWith("+")) {
            digits = stripped.substring(1).replaceAll("[^0-9]", "");
            if (digits.length() < 8) return null;
            return "+" + digits;
        }
        digits = stripped.replaceAll("[^0-9]", "");
        if (digits.length() == 10) return "+91" + digits;
        if (digits.length() < 8) return null;
        return "+" + digits;
    }

    /**
     * Sends a test WhatsApp message using the number currently saved in DB.
     * Teacher should save their number first, then click Send Test.
     */
    @PostMapping("/whatsapp-settings/test")
    public ResponseEntity<?> testWhatsappMessage(@AuthenticationPrincipal CustomUserDetails userDetails) {
        try {
            User teacher = userService.findById(userDetails.getUserId())
                    .orElseThrow(() -> new RuntimeException("Teacher not found"));
            String result = whatsAppNotificationService.sendTestMessage(teacher);
            boolean success = result.startsWith("Test message sent");
            return ResponseEntity.ok(Map.of("success", success, "message", result));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", e.getMessage()));
        }
    }

    /**
     * Dynamic "Send Now" endpoint — used by the dashboard to dispatch a
     * notification to any number the teacher types in, on demand. The body
     * is minimal: <code>{"recipient":"+919...","url":"https://..."}</code>.
     *
     * <p>The phone number is sanitised server-side (spaces, dashes,
     * parentheses, missing country code are all handled), and the service
     * uses the single global Twilio gateway when configured, falling back
     * to the per-teacher CallMeBot key only if the admin hasn't set up
     * Twilio.
     */
    @PostMapping("/whatsapp-settings/send-now")
    public ResponseEntity<?> sendNow(@AuthenticationPrincipal CustomUserDetails userDetails,
                                     @RequestBody Map<String, String> body) {
        try {
            User teacher = userService.findById(userDetails.getUserId())
                    .orElseThrow(() -> new RuntimeException("Teacher not found"));

            String recipient = body != null ? body.getOrDefault("recipient", "") : "";
            String url       = body != null ? body.getOrDefault("url", "")        : "";

            String result = whatsAppNotificationService.sendNow(teacher, recipient, url);
            boolean success = result.startsWith("Test message sent");
            return ResponseEntity.ok(Map.of("success", success, "message", result));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", e.getMessage()));
        }
    }

    /**
     * Returns the last N WhatsApp send attempts for the current teacher,
     * newest first. Used by the dashboard's Notifications status box so the
     * host can see real-time success / failure (e.g. invalid API key,
     * rate limit, network error, etc.).
     */
    @GetMapping("/whatsapp-settings/status")
    public ResponseEntity<?> whatsappNotificationStatus(@AuthenticationPrincipal CustomUserDetails userDetails) {
        try {
            Long teacherId = userDetails.getUserId();
            DateTimeFormatter ts = DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm:ss");
            List<Map<String, Object>> rows = whatsAppNotificationService.getRecentStatuses(teacherId).stream()
                    .map(s -> {
                        Map<String, Object> r = new HashMap<>();
                        r.put("timestamp",       s.getTimestamp() != null ? s.getTimestamp().format(ts) : "");
                        r.put("lastUpdated",     s.getLastUpdated() != null ? s.getLastUpdated().format(ts) : "");
                        r.put("recipientNumber", s.getRecipientNumber() != null ? s.getRecipientNumber() : "");
                        r.put("recordingId",     s.getRecordingId());
                        r.put("studentName",     s.getStudentName() != null ? s.getStudentName() : "");
                        r.put("result",          s.getResult() != null ? s.getResult().name() : "");
                        r.put("lifecycle",       s.getLifecycle() != null ? s.getLifecycle().name() : "");
                        r.put("provider",        s.getProvider() != null ? s.getProvider() : "");
                        r.put("messageSid",      s.getProviderMessageId() != null ? s.getProviderMessageId() : "");
                        r.put("message",         s.getMessage() != null ? s.getMessage() : "");
                        return r;
                    })
                    .collect(Collectors.toList());
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "statuses", rows,
                    "twilioReady", whatsAppNotificationService.isTwilioReady()
            ));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", e.getMessage()));
        }
    }
}

