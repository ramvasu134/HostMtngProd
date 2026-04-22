package com.host.studen.controller.api;

import com.host.studen.model.Role;
import com.host.studen.model.User;
import com.host.studen.service.AdminService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

/**
 * REST API Controller for Admin operations
 */
@RestController
@RequestMapping("/api/admin")
@PreAuthorize("hasRole('ADMIN')")
public class AdminApiController {

    @Autowired
    private AdminService adminService;

    /**
     * Get all teachers
     */
    @GetMapping("/teachers")
    public ResponseEntity<?> getAllTeachers() {
        try {
            List<User> teachers = adminService.getAllTeachers();
            List<Map<String, Object>> teacherList = teachers.stream()
                    .map(t -> {
                        Map<String, Object> teacherMap = new HashMap<>();
                        teacherMap.put("id", t.getId());
                        teacherMap.put("displayName", t.getDisplayName());
                        teacherMap.put("username", t.getUsername());
                        teacherMap.put("teacherName", t.getTeacherName() != null ? t.getTeacherName() : "");
                        teacherMap.put("email", t.getEmail() != null ? t.getEmail() : "");
                        teacherMap.put("phone", t.getPhone() != null ? t.getPhone() : "");
                        teacherMap.put("active", t.isActive());
                        teacherMap.put("createdAt", t.getCreatedAt() != null ? t.getCreatedAt().toString() : "");
                        return teacherMap;
                    })
                    .collect(Collectors.toList());

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("teachers", teacherList);
            response.put("total", teachers.size());

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of(
                    "success", false,
                    "message", "Error fetching teachers: " + e.getMessage()
            ));
        }
    }

    /**
     * Create a new teacher
     */
    @PostMapping("/teachers")
    public ResponseEntity<?> createTeacher(@RequestBody Map<String, String> request) {
        try {
            String teacherName = request.get("teacherName");
            String username = request.get("username");
            String password = request.get("password");
            String displayName = request.get("displayName");
            String email = request.getOrDefault("email", "");
            String phone = request.getOrDefault("phone", "");

            if (teacherName == null || username == null || password == null || displayName == null) {
                return ResponseEntity.badRequest().body(Map.of(
                        "success", false,
                        "message", "Missing required fields"
                ));
            }

            User teacher = adminService.createTeacher(
                    teacherName, username, password, displayName, email, phone);

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "Teacher created successfully",
                    "teacher", Map.of(
                            "id", teacher.getId(),
                            "displayName", teacher.getDisplayName(),
                            "username", teacher.getUsername(),
                            "teacherName", teacher.getTeacherName()
                    )
            ));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of(
                    "success", false,
                    "message", "Error creating teacher: " + e.getMessage()
            ));
        }
    }

    /**
     * Update teacher details
     */
    @PutMapping("/teachers/{id}")
    public ResponseEntity<?> updateTeacher(@PathVariable Long id, @RequestBody Map<String, Object> request) {
        try {
            String displayName = (String) request.getOrDefault("displayName", "");
            String email = (String) request.getOrDefault("email", "");
            String phone = (String) request.getOrDefault("phone", "");
            Boolean active = (Boolean) request.getOrDefault("active", true);

            User updated = adminService.updateTeacher(id, displayName, email, phone, null, active);

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "Teacher updated successfully",
                    "teacher", Map.of(
                            "id", updated.getId(),
                            "displayName", updated.getDisplayName(),
                            "email", updated.getEmail(),
                            "phone", updated.getPhone(),
                            "active", updated.isActive()
                    )
            ));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of(
                    "success", false,
                    "message", "Error updating teacher: " + e.getMessage()
            ));
        }
    }

    /**
     * Delete teacher
     */
    @DeleteMapping("/teachers/{id}")
    public ResponseEntity<?> deleteTeacher(@PathVariable Long id) {
        try {
            adminService.deleteTeacher(id);
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "Teacher and all associated data deleted successfully"
            ));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of(
                    "success", false,
                    "message", "Error deleting teacher: " + e.getMessage()
            ));
        }
    }

    /**
     * Get teacher credentials (username and raw password) for sharing
     * Only accessible by admin users
     */
    @GetMapping("/teachers/{id}/credentials")
    public ResponseEntity<?> getTeacherCredentials(@PathVariable Long id) {
        try {
            Optional<User> teacherOpt = adminService.getTeacherById(id);
            
            if (teacherOpt.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of(
                        "success", false,
                        "message", "Teacher not found"
                ));
            }
            
            User teacher = teacherOpt.get();
            
            // Security check: only return credentials for HOST role users
            if (teacher.getRole() != Role.HOST) {
                return ResponseEntity.status(403).body(Map.of(
                        "success", false,
                        "message", "Access denied - not a teacher"
                ));
            }
            
            String rawPassword = teacher.getRawPassword();
            boolean hasPassword = rawPassword != null && !rawPassword.isEmpty();
            
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "teacherId", teacher.getId(),
                    "displayName", teacher.getDisplayName(),
                    "username", teacher.getUsername(),
                    "teacherCode", teacher.getTeacherName(),
                    "password", hasPassword ? rawPassword : "",
                    "hasPassword", hasPassword,
                    "email", teacher.getEmail() != null ? teacher.getEmail() : "",
                    "phone", teacher.getPhone() != null ? teacher.getPhone() : ""
            ));
            
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of(
                    "success", false,
                    "message", "Error fetching credentials: " + e.getMessage()
            ));
        }
    }

    /**
     * Reset teacher password
     */
    @PostMapping("/teachers/{id}/reset-password")
    public ResponseEntity<?> resetPassword(@PathVariable Long id, @RequestBody Map<String, String> request) {
        try {
            String newPassword = request.get("newPassword");
            if (newPassword == null || newPassword.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of(
                        "success", false,
                        "message", "New password is required"
                ));
            }

            adminService.resetTeacherPassword(id, newPassword);
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "Password reset successfully",
                    "newPassword", newPassword
            ));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of(
                    "success", false,
                    "message", "Error resetting password: " + e.getMessage()
            ));
        }
    }

    /**
     * Toggle teacher active status
     */
    @PostMapping("/teachers/{id}/toggle-status")
    public ResponseEntity<?> toggleStatus(@PathVariable Long id) {
        try {
            adminService.toggleTeacherStatus(id);
            Optional<User> updatedTeacher = adminService.getTeacherById(id);
            boolean isActive = updatedTeacher.map(User::isActive).orElse(false);

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "Teacher status updated",
                    "active", isActive
            ));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of(
                    "success", false,
                    "message", "Error toggling status: " + e.getMessage()
            ));
        }
    }
}
