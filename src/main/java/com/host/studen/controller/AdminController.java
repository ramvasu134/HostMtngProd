package com.host.studen.controller;

import com.host.studen.model.User;
import com.host.studen.security.CustomUserDetails;
import com.host.studen.service.AdminService;
import com.host.studen.service.RecordingCleanupService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;

/**
 * Controller for Admin dashboard and teacher management
 */
@Controller
@RequestMapping("/admin")
@PreAuthorize("hasRole('ADMIN')")
public class AdminController {

    @Autowired
    private AdminService adminService;

    /**
     * Admin Dashboard
     */
    @GetMapping("/dashboard")
    public String dashboard(Model model, @AuthenticationPrincipal CustomUserDetails userDetails) {
        AdminService.AdminDashboardStats stats = adminService.getDashboardStats();
        List<User> teachers = adminService.getAllTeachers();

        model.addAttribute("admin", userDetails.getUser());
        model.addAttribute("stats", stats);
        model.addAttribute("teachers", teachers);
        return "admin/dashboard";
    }

    /**
     * View all teachers
     */
    @GetMapping("/teachers")
    public String listTeachers(Model model) {
        List<User> teachers = adminService.getAllTeachers();
        model.addAttribute("teachers", teachers);
        return "admin/teachers";
    }

    /**
     * Create teacher form
     */
    @GetMapping("/teachers/new")
    public String createTeacherForm(Model model) {
        model.addAttribute("avatarOptions", adminService.getAvatarOptions("newteacher"));
        return "admin/teacher-form";
    }

    /**
     * Create new teacher
     */
    @PostMapping("/teachers")
    public String createTeacher(
            @RequestParam String teacherName,
            @RequestParam String username,
            @RequestParam String password,
            @RequestParam String displayName,
            @RequestParam(required = false) String email,
            @RequestParam(required = false) String phone,
            RedirectAttributes redirectAttributes) {
        try {
            User teacher = adminService.createTeacher(
                    teacherName, username, password, displayName, email, phone);
            
            redirectAttributes.addFlashAttribute("success", 
                    "Teacher '" + displayName + "' created successfully! Username: " + username);
            redirectAttributes.addFlashAttribute("credentials", 
                    "Login credentials - Username: " + username + ", Password: " + password);
            
            return "redirect:/admin/teachers";
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", "Error creating teacher: " + e.getMessage());
            return "redirect:/admin/teachers/new";
        }
    }

    /**
     * Edit teacher form
     */
    @GetMapping("/teachers/{id}/edit")
    public String editTeacherForm(@PathVariable Long id, Model model, RedirectAttributes redirectAttributes) {
        return adminService.getTeacherById(id)
                .map(teacher -> {
                    AdminService.TeacherStats stats = adminService.getTeacherStats(id);
                    model.addAttribute("teacher", teacher);
                    model.addAttribute("stats", stats);
                    model.addAttribute("avatarOptions", adminService.getAvatarOptions(teacher.getUsername()));
                    return "admin/teacher-edit";
                })
                .orElseGet(() -> {
                    redirectAttributes.addFlashAttribute("error", "Teacher not found");
                    return "redirect:/admin/teachers";
                });
    }

    /**
     * Update teacher
     */
    @PostMapping("/teachers/{id}")
    public String updateTeacher(
            @PathVariable Long id,
            @RequestParam(required = false) String displayName,
            @RequestParam(required = false) String email,
            @RequestParam(required = false) String phone,
            @RequestParam(required = false) String teacherLogo,
            @RequestParam(required = false) Boolean active,
            RedirectAttributes redirectAttributes) {
        try {
            adminService.updateTeacher(id, displayName, email, phone, teacherLogo, active);
            redirectAttributes.addFlashAttribute("success", "Teacher updated successfully!");
            return "redirect:/admin/teachers/" + id + "/edit";
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", "Error updating teacher: " + e.getMessage());
            return "redirect:/admin/teachers/" + id + "/edit";
        }
    }

    /**
     * Reset teacher password
     */
    @PostMapping("/teachers/{id}/reset-password")
    public String resetPassword(
            @PathVariable Long id,
            @RequestParam String newPassword,
            RedirectAttributes redirectAttributes) {
        try {
            adminService.resetTeacherPassword(id, newPassword);
            redirectAttributes.addFlashAttribute("success", "Password reset successfully!");
            redirectAttributes.addFlashAttribute("newPassword", "New password: " + newPassword);
            return "redirect:/admin/teachers/" + id + "/edit";
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", "Error resetting password: " + e.getMessage());
            return "redirect:/admin/teachers/" + id + "/edit";
        }
    }

    /**
     * Toggle teacher status
     */
    @PostMapping("/teachers/{id}/toggle-status")
    public String toggleStatus(@PathVariable Long id, RedirectAttributes redirectAttributes) {
        try {
            adminService.toggleTeacherStatus(id);
            redirectAttributes.addFlashAttribute("success", "Teacher status updated!");
            return "redirect:/admin/teachers";
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", "Error: " + e.getMessage());
            return "redirect:/admin/teachers";
        }
    }

    /**
     * Delete teacher
     */
    @PostMapping("/teachers/{id}/delete")
    public String deleteTeacher(@PathVariable Long id, RedirectAttributes redirectAttributes) {
        try {
            adminService.deleteTeacher(id);
            redirectAttributes.addFlashAttribute("success", "Teacher and all associated data deleted!");
            return "redirect:/admin/teachers";
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", "Error deleting teacher: " + e.getMessage());
            return "redirect:/admin/teachers";
        }
    }

    /**
     * Recording cleanup page
     */
    @GetMapping("/cleanup")
    public String cleanupPage(Model model) {
        RecordingCleanupService.RecordingStats stats = 
                adminService.triggerCleanup() != null ? null : null;
        // Just get stats without cleanup
        model.addAttribute("stats", adminService.getDashboardStats());
        return "admin/cleanup";
    }

    /**
     * Trigger manual cleanup
     */
    @PostMapping("/cleanup/trigger")
    public String triggerCleanup(RedirectAttributes redirectAttributes) {
        try {
            RecordingCleanupService.CleanupResult result = adminService.triggerCleanup();
            redirectAttributes.addFlashAttribute("success", 
                    String.format("Cleanup completed! Deleted %d recordings, freed %s MB",
                            result.deletedCount(), result.getFreedMB()));
            return "redirect:/admin/dashboard";
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", "Error during cleanup: " + e.getMessage());
            return "redirect:/admin/dashboard";
        }
    }
}

