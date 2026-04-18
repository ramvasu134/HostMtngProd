package com.host.studen.service;

import com.host.studen.model.*;
import com.host.studen.repository.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Service for Admin operations - managing teachers and system-wide settings
 */
@Service
public class AdminService {

    private static final Logger log = LoggerFactory.getLogger(AdminService.class);

    // Default avatars for teachers (cartoon-style)
    private static final List<String> DEFAULT_AVATARS = List.of(
        "https://api.dicebear.com/7.x/avataaars/svg?seed=teacher1&backgroundColor=b6e3f4",
        "https://api.dicebear.com/7.x/avataaars/svg?seed=teacher2&backgroundColor=c0aede",
        "https://api.dicebear.com/7.x/avataaars/svg?seed=teacher3&backgroundColor=d1d4f9",
        "https://api.dicebear.com/7.x/avataaars/svg?seed=teacher4&backgroundColor=ffd5dc",
        "https://api.dicebear.com/7.x/avataaars/svg?seed=teacher5&backgroundColor=ffdfbf",
        "https://api.dicebear.com/7.x/bottts/svg?seed=bot1&backgroundColor=b6e3f4",
        "https://api.dicebear.com/7.x/bottts/svg?seed=bot2&backgroundColor=c0aede",
        "https://api.dicebear.com/7.x/fun-emoji/svg?seed=fun1&backgroundColor=d1d4f9",
        "https://api.dicebear.com/7.x/fun-emoji/svg?seed=fun2&backgroundColor=ffd5dc",
        "https://api.dicebear.com/7.x/lorelei/svg?seed=lore1&backgroundColor=ffdfbf"
    );

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private MeetingRepository meetingRepository;

    @Autowired
    private RecordingRepository recordingRepository;

    @Autowired
    private ScheduleRepository scheduleRepository;

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private RecordingCleanupService cleanupService;

    // ==================== Teacher Management ====================

    /**
     * Get all teachers (HOST role users)
     */
    public List<User> getAllTeachers() {
        return userRepository.findByRole(Role.HOST);
    }

    /**
     * Get teacher by ID
     */
    public Optional<User> getTeacherById(Long id) {
        return userRepository.findById(id)
                .filter(u -> u.getRole() == Role.HOST);
    }

    /**
     * Create a new teacher with a random avatar
     */
    @Transactional
    public User createTeacher(String teacherName, String username, String password, 
                               String displayName, String email, String phone) {
        // Check if username exists
        if (userRepository.existsByUsername(username)) {
            throw new RuntimeException("Username already exists: " + username);
        }

        // Generate random avatar
        String avatar = getRandomAvatar(username);

        User teacher = new User();
        teacher.setTeacherName(teacherName.toUpperCase());
        teacher.setUsername(username);
        teacher.setPassword(passwordEncoder.encode(password));
        teacher.setRawPassword(password);  // Store raw password for sharing
        teacher.setDisplayName(displayName);
        teacher.setRole(Role.HOST);
        teacher.setEmail(email);
        teacher.setPhone(phone);
        teacher.setActive(true);
        teacher.setTeacherLogo(avatar);

        User saved = userRepository.save(teacher);
        log.info("Admin created new teacher: {} (username: {})", displayName, username);
        return saved;
    }

    /**
     * Update teacher details
     */
    @Transactional
    public User updateTeacher(Long teacherId, String displayName, String email, 
                               String phone, String teacherLogo, Boolean active) {
        User teacher = userRepository.findById(teacherId)
                .orElseThrow(() -> new RuntimeException("Teacher not found"));

        if (teacher.getRole() != Role.HOST) {
            throw new RuntimeException("User is not a teacher");
        }

        if (displayName != null && !displayName.isBlank()) {
            teacher.setDisplayName(displayName);
        }
        if (email != null) {
            teacher.setEmail(email);
        }
        if (phone != null) {
            teacher.setPhone(phone);
        }
        if (teacherLogo != null && !teacherLogo.isBlank()) {
            teacher.setTeacherLogo(teacherLogo);
        }
        if (active != null) {
            teacher.setActive(active);
        }

        User updated = userRepository.save(teacher);
        log.info("Admin updated teacher: {}", teacherId);
        return updated;
    }

    /**
     * Reset teacher password
     */
    @Transactional
    public void resetTeacherPassword(Long teacherId, String newPassword) {
        User teacher = userRepository.findById(teacherId)
                .orElseThrow(() -> new RuntimeException("Teacher not found"));

        if (teacher.getRole() != Role.HOST) {
            throw new RuntimeException("User is not a teacher");
        }

        teacher.setPassword(passwordEncoder.encode(newPassword));
        teacher.setRawPassword(newPassword);  // Store raw password for sharing
        userRepository.save(teacher);
        log.info("Admin reset password for teacher: {}", teacherId);
    }

    /**
     * Delete teacher and all associated data
     */
    @Transactional
    public void deleteTeacher(Long teacherId) {
        User teacher = userRepository.findById(teacherId)
                .orElseThrow(() -> new RuntimeException("Teacher not found"));

        if (teacher.getRole() != Role.HOST) {
            throw new RuntimeException("User is not a teacher");
        }

        String teacherName = teacher.getTeacherName();

        // Delete all students of this teacher
        List<User> students = userRepository.findByTeacherNameAndRole(teacherName, Role.STUDENT);
        for (User student : students) {
            // Delete student's recordings, transcripts, notifications
            notificationRepository.deleteByUser(student);
            userRepository.delete(student);
        }

        // Delete teacher's meetings
        List<Meeting> meetings = meetingRepository.findByHost(teacher);
        meetingRepository.deleteAll(meetings);

        // Delete teacher's schedules
        List<Schedule> schedules = scheduleRepository.findByTeacher(teacher);
        scheduleRepository.deleteAll(schedules);

        // Delete teacher's notifications
        notificationRepository.deleteByUser(teacher);

        // Delete the teacher
        userRepository.delete(teacher);
        log.info("Admin deleted teacher: {} and all associated data", teacherId);
    }

    /**
     * Toggle teacher active status
     */
    @Transactional
    public void toggleTeacherStatus(Long teacherId) {
        User teacher = userRepository.findById(teacherId)
                .orElseThrow(() -> new RuntimeException("Teacher not found"));

        if (teacher.getRole() != Role.HOST) {
            throw new RuntimeException("User is not a teacher");
        }

        teacher.setActive(!teacher.isActive());
        userRepository.save(teacher);
        log.info("Admin toggled teacher {} status to: {}", teacherId, teacher.isActive());
    }

    // ==================== Statistics ====================

    /**
     * Get dashboard statistics for admin
     */
    public AdminDashboardStats getDashboardStats() {
        long totalTeachers = userRepository.findByRole(Role.HOST).size();
        long activeTeachers = userRepository.findByRole(Role.HOST).stream()
                .filter(User::isActive).count();
        long totalStudents = userRepository.findByRole(Role.STUDENT).size();
        long totalMeetings = meetingRepository.count();
        long liveMeetings = meetingRepository.findByStatus(MeetingStatus.LIVE).size();
        long totalRecordings = recordingRepository.count() - 
                recordingRepository.countByStatus(Recording.RecordingStatus.DELETED);

        RecordingCleanupService.RecordingStats recordingStats = cleanupService.getRecordingStats();

        return new AdminDashboardStats(
                totalTeachers, activeTeachers, totalStudents,
                totalMeetings, liveMeetings, totalRecordings,
                recordingStats.pendingCleanup(), recordingStats.retentionDays()
        );
    }

    /**
     * Get teacher statistics
     */
    public TeacherStats getTeacherStats(Long teacherId) {
        User teacher = userRepository.findById(teacherId)
                .orElseThrow(() -> new RuntimeException("Teacher not found"));

        String teacherName = teacher.getTeacherName();
        List<User> students = userRepository.findByTeacherNameAndRole(teacherName, Role.STUDENT);
        List<Meeting> meetings = meetingRepository.findByHost(teacher);

        long totalStudents = students.size();
        long activeStudents = students.stream().filter(User::isActive).count();
        long totalMeetings = meetings.size();
        long totalRecordings = 0;
        for (User student : students) {
            totalRecordings += recordingRepository.findByRecordedByAndStatusNotOrderByCreatedAtDesc(
                    student, Recording.RecordingStatus.DELETED).size();
        }

        return new TeacherStats(teacher, totalStudents, activeStudents, totalMeetings, totalRecordings);
    }

    // ==================== Utilities ====================

    /**
     * Get a random avatar URL for a new teacher
     */
    public String getRandomAvatar(String seed) {
        // Use seed for consistent but unique avatar
        int index = Math.abs(seed.hashCode()) % DEFAULT_AVATARS.size();
        return DEFAULT_AVATARS.get(index).replace("seed=", "seed=" + seed);
    }

    /**
     * Get all available avatar styles
     */
    public List<String> getAvatarOptions(String username) {
        return List.of(
            "https://api.dicebear.com/7.x/avataaars/svg?seed=" + username + "&backgroundColor=b6e3f4",
            "https://api.dicebear.com/7.x/avataaars/svg?seed=" + username + "2&backgroundColor=c0aede",
            "https://api.dicebear.com/7.x/bottts/svg?seed=" + username + "&backgroundColor=d1d4f9",
            "https://api.dicebear.com/7.x/fun-emoji/svg?seed=" + username + "&backgroundColor=ffd5dc",
            "https://api.dicebear.com/7.x/lorelei/svg?seed=" + username + "&backgroundColor=ffdfbf",
            "https://api.dicebear.com/7.x/personas/svg?seed=" + username + "&backgroundColor=b6e3f4",
            "https://api.dicebear.com/7.x/notionists/svg?seed=" + username + "&backgroundColor=c0aede",
            "https://api.dicebear.com/7.x/adventurer/svg?seed=" + username + "&backgroundColor=d1d4f9"
        );
    }

    /**
     * Trigger manual recording cleanup
     */
    public RecordingCleanupService.CleanupResult triggerCleanup() {
        return cleanupService.triggerManualCleanup();
    }

    // ==================== DTOs ====================

    public record AdminDashboardStats(
            long totalTeachers,
            long activeTeachers,
            long totalStudents,
            long totalMeetings,
            long liveMeetings,
            long totalRecordings,
            long pendingCleanup,
            int retentionDays
    ) {}

    public record TeacherStats(
            User teacher,
            long totalStudents,
            long activeStudents,
            long totalMeetings,
            long totalRecordings
    ) {}
}

