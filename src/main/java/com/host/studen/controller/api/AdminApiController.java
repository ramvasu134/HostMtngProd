package com.host.studen.controller.api;

import com.host.studen.model.Role;
import com.host.studen.model.User;
import com.host.studen.service.AdminService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.Optional;

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
}

