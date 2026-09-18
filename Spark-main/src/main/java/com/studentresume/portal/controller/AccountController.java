package com.studentresume.portal.controller;

import com.studentresume.portal.model.Resume;
import com.studentresume.portal.model.User;
import com.studentresume.portal.repository.ResumeRepository;
import com.studentresume.portal.repository.UserRepository;
import com.studentresume.portal.service.CurrentUserProvider;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Set;

/**
 * Account settings: change username/password, upload a profile picture, delete the account.
 * Every action re-verifies the current password before making a destructive or credential
 * change — the session being logged in isn't sufficient proof of intent for these.
 */
@Controller
public class AccountController {

    private static final Path AVATAR_DIR = Path.of("uploads", "avatars");
    private static final long MAX_AVATAR_BYTES = 5L * 1024 * 1024; // 5MB — tighter than the app-wide 10MB multipart ceiling
    private static final Set<String> ALLOWED_AVATAR_TYPES = Set.of("image/jpeg", "image/png", "image/webp", "image/gif");
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final UserRepository userRepository;
    private final ResumeRepository resumeRepository;
    private final CurrentUserProvider currentUserProvider;
    private final PasswordEncoder passwordEncoder;

    public AccountController(UserRepository userRepository, ResumeRepository resumeRepository,
                              CurrentUserProvider currentUserProvider, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.resumeRepository = resumeRepository;
        this.currentUserProvider = currentUserProvider;
        this.passwordEncoder = passwordEncoder;
    }

    @GetMapping("/account")
    public String account(HttpSession session, Model model) {
        model.addAttribute("user", currentUserProvider.require(session));
        return "account";
    }

    @PostMapping("/account/username")
    public String changeUsername(@RequestParam String username, @RequestParam String currentPassword,
                                  HttpSession session, RedirectAttributes ra) {
        User user = currentUserProvider.require(session);

        if (!passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            ra.addFlashAttribute("error", "Current password is incorrect.");
            return "redirect:/account";
        }

        String trimmed = username.trim();
        if (trimmed.isBlank()) {
            ra.addFlashAttribute("error", "Username can't be blank.");
            return "redirect:/account";
        }
        if (!trimmed.equals(user.getUsername()) && userRepository.existsByUsername(trimmed)) {
            ra.addFlashAttribute("error", "That username is already taken.");
            return "redirect:/account";
        }

        try {
            user.setUsername(trimmed);
            userRepository.save(user);
        } catch (DataIntegrityViolationException e) {
            ra.addFlashAttribute("error", "That username is already taken.");
            return "redirect:/account";
        }

        ra.addFlashAttribute("info", "Username updated.");
        return "redirect:/account";
    }

    @PostMapping("/account/password")
    public String changePassword(@RequestParam String currentPassword, @RequestParam String newPassword,
                                  HttpSession session, RedirectAttributes ra) {
        User user = currentUserProvider.require(session);

        if (!passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            ra.addFlashAttribute("error", "Current password is incorrect.");
            return "redirect:/account";
        }
        if (newPassword.length() < 8) {
            ra.addFlashAttribute("error", "New password must be at least 8 characters.");
            return "redirect:/account";
        }

        user.setPasswordHash(passwordEncoder.encode(newPassword));
        userRepository.save(user);

        ra.addFlashAttribute("info", "Password updated.");
        return "redirect:/account";
    }

    @PostMapping("/account/avatar")
    public String uploadAvatar(@RequestParam MultipartFile avatar, HttpSession session, RedirectAttributes ra) throws IOException {
        User user = currentUserProvider.require(session);

        if (avatar.isEmpty()) {
            ra.addFlashAttribute("error", "Please choose an image first.");
            return "redirect:/account";
        }
        if (avatar.getSize() > MAX_AVATAR_BYTES) {
            ra.addFlashAttribute("error", "That image is too large — please pick one under 5MB.");
            return "redirect:/account";
        }
        String contentType = avatar.getContentType();
        if (contentType == null || !ALLOWED_AVATAR_TYPES.contains(contentType)) {
            ra.addFlashAttribute("error", "Please upload a JPEG, PNG, WebP, or GIF image.");
            return "redirect:/account";
        }
        // The Content-Type header above is entirely client-declared — a request can claim
        // "image/png" for any bytes at all. Confirm the file's actual signature matches before
        // trusting it enough to store and serve back (unauthenticated, per WebConfig) as that type.
        if (!matchesDeclaredImageType(avatar, contentType)) {
            ra.addFlashAttribute("error", "That file doesn't look like a valid JPEG, PNG, WebP, or GIF — please try a different image.");
            return "redirect:/account";
        }

        Files.createDirectories(AVATAR_DIR);

        String extension = switch (contentType) {
            case "image/png" -> "png";
            case "image/webp" -> "webp";
            case "image/gif" -> "gif";
            default -> "jpg";
        };
        byte[] suffixBytes = new byte[9];
        SECURE_RANDOM.nextBytes(suffixBytes);
        String suffix = Base64.getUrlEncoder().withoutPadding().encodeToString(suffixBytes);
        String filename = user.getId() + "-" + suffix + "." + extension;

        Files.copy(avatar.getInputStream(), AVATAR_DIR.resolve(filename));

        // Clean up the previous file so uploads don't accumulate orphaned images forever.
        String previous = user.getAvatarFilename();
        if (previous != null) {
            Files.deleteIfExists(AVATAR_DIR.resolve(previous));
        }

        user.setAvatarFilename(filename);
        userRepository.save(user);

        ra.addFlashAttribute("info", "Profile picture updated.");
        return "redirect:/account";
    }

    /**
     * Confirms {@code file}'s actual byte signature matches {@code declaredContentType}, rather
     * than trusting the client-supplied header alone. WebP needs two non-adjacent checks (a
     * "RIFF" container header, then a "WEBP" tag 4 bytes later) — every other type here is a
     * single fixed prefix.
     */
    private boolean matchesDeclaredImageType(MultipartFile file, String declaredContentType) throws IOException {
        byte[] head;
        try (var is = file.getInputStream()) {
            head = is.readNBytes(12);
        }
        return switch (declaredContentType) {
            case "image/jpeg" -> startsWith(head, 0xFF, 0xD8, 0xFF);
            case "image/png"  -> startsWith(head, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A);
            case "image/gif"  -> startsWith(head, 'G', 'I', 'F', '8');
            case "image/webp" -> startsWith(head, 'R', 'I', 'F', 'F')
                    && head.length >= 12
                    && head[8] == 'W' && head[9] == 'E' && head[10] == 'B' && head[11] == 'P';
            default -> false;
        };
    }

    private boolean startsWith(byte[] actual, int... expected) {
        if (actual.length < expected.length) return false;
        for (int i = 0; i < expected.length; i++) {
            if ((actual[i] & 0xFF) != (expected[i] & 0xFF)) return false;
        }
        return true;
    }

    /**
     * {@code @Transactional} so the two DB deletes below commit or roll back together — without
     * it, a failure between them (e.g. a resume delete succeeding but the user delete throwing
     * on some constraint) would leave a user row with no resumes but no clean failure either.
     * The avatar file delete deliberately happens LAST, after both DB deletes have gone through
     * without throwing: a file and a database row can't be dropped in one atomic operation, so
     * ordering it last means a mid-method failure leaves the file intact (recoverable / harmless)
     * rather than an already-deleted file pointing at a user row that failed to disappear.
     */
    @Transactional
    @PostMapping("/account/delete")
    public String deleteAccount(@RequestParam String currentPassword, HttpSession session,
                                 HttpServletRequest request, RedirectAttributes ra) throws IOException {
        User user = currentUserProvider.require(session);

        if (!passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            ra.addFlashAttribute("error", "Current password is incorrect.");
            return "redirect:/account";
        }

        List<Resume> resumes = resumeRepository.findByUserIdOrderByUpdatedAtDesc(user.getId());
        resumeRepository.deleteAll(resumes);
        userRepository.delete(user);

        if (user.getAvatarFilename() != null) {
            Files.deleteIfExists(AVATAR_DIR.resolve(user.getAvatarFilename()));
        }

        request.getSession(false).invalidate();

        ra.addFlashAttribute("info", "Your account has been deleted.");
        return "redirect:/";
    }
}
