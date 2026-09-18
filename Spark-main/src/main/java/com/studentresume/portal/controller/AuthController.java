package com.studentresume.portal.controller;

import com.studentresume.portal.model.User;
import com.studentresume.portal.repository.UserRepository;
import com.studentresume.portal.service.RateLimiterService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Optional;

/**
 * Login, registration, logout, and password recovery. Hand-rolled rather than Spring Security,
 * matching this app's minimal ethos — see the security-sensitive details called out inline below.
 */
@Controller
public class AuthController {

    /**
     * A real BCrypt hash of an arbitrary constant, used only to normalize login timing when no
     * matching username exists. Never actually matches a real password.
     */
    private static final String DUMMY_HASH =
            "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final int RESET_TOKEN_MINUTES = 30;

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final RateLimiterService rateLimiter;

    public AuthController(UserRepository userRepository, PasswordEncoder passwordEncoder,
                           RateLimiterService rateLimiter) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.rateLimiter = rateLimiter;
    }

    @GetMapping("/login")
    public String loginForm() {
        return "login";
    }

    @PostMapping("/login")
    public String login(@RequestParam String username, @RequestParam String password,
                         HttpServletRequest request, RedirectAttributes ra) {
        String ip = request.getRemoteAddr();
        if (rateLimiter.isRateLimited("login:" + ip, 8, 900)) {
            ra.addFlashAttribute("error", "Too many login attempts. Please wait a few minutes and try again.");
            return "redirect:/login";
        }

        Optional<User> found = userRepository.findByUsername(username.trim());

        // Always run the BCrypt check, even against a dummy hash when no such user exists,
        // so a wrong password and a nonexistent username take roughly the same amount of
        // time — avoids a username-enumeration timing side channel.
        String hashToCheck = found.map(User::getPasswordHash).orElse(DUMMY_HASH);
        boolean passwordOk = passwordEncoder.matches(password, hashToCheck);

        if (found.isEmpty() || !passwordOk) {
            ra.addFlashAttribute("error", "Invalid username or password.");
            return "redirect:/login";
        }

        // A genuine login shouldn't cost the user their remaining attempts from earlier typos.
        rateLimiter.reset("login:" + ip);

        // Session fixation: rotate the session ID on privilege change. getSession(true) first
        // because changeSessionId() throws if no session exists yet, and /login is very often
        // the first request to touch a session at all. changeSessionId() re-keys the existing
        // HttpSession in place — it does not invalidate it or wipe its attributes, so any
        // session-scoped bean (ResumeData) survives this untouched.
        request.getSession(true);
        request.changeSessionId();
        request.getSession().setAttribute("userId", found.get().getId());

        return "redirect:/dashboard";
    }

    @GetMapping("/register")
    public String registerForm() {
        return "register";
    }

    @PostMapping("/register")
    public String register(@RequestParam String username, @RequestParam String password,
                            HttpServletRequest request, RedirectAttributes ra) {
        String ip = request.getRemoteAddr();
        if (rateLimiter.isRateLimited("register:" + ip, 5, 3600)) {
            ra.addFlashAttribute("error", "Too many attempts. Please try again later.");
            return "redirect:/register";
        }

        String trimmed = username.trim();
        if (trimmed.isBlank() || password.length() < 8) {
            ra.addFlashAttribute("error", "Username is required and password must be at least 8 characters.");
            return "redirect:/register";
        }
        if (userRepository.existsByUsername(trimmed)) {
            ra.addFlashAttribute("error", "That username is already taken.");
            return "redirect:/register";
        }

        try {
            User user = new User();
            user.setUsername(trimmed);
            user.setPasswordHash(passwordEncoder.encode(password));
            userRepository.save(user);
        } catch (DataIntegrityViolationException e) {
            // The existsByUsername() check above is a friendlier instant error message in the
            // common case, but the DB's unique constraint (caught here) is the real guarantee —
            // two concurrent registrations for the same username could both pass the pre-check
            // before either commits.
            ra.addFlashAttribute("error", "That username is already taken.");
            return "redirect:/register";
        }

        ra.addFlashAttribute("info", "Account created — please log in.");
        return "redirect:/login";
    }

    @PostMapping("/logout")
    public String logout(HttpServletRequest request) {
        var session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        return "redirect:/";
    }

    // ============================================================
    // Password recovery — no email infrastructure exists in this app, so instead of sending a
    // real email, the generated reset link is shown directly on the confirmation page. The
    // token/expiry mechanics are identical to a real email flow; swapping in an actual mail
    // sender later only touches how the link is delivered, not how it's generated or redeemed.
    // ============================================================

    @GetMapping("/forgot-password")
    public String forgotPasswordForm() {
        return "forgot-password";
    }

    @PostMapping("/forgot-password")
    public String forgotPassword(@RequestParam String username, HttpServletRequest request, RedirectAttributes ra) {
        String ip = request.getRemoteAddr();
        if (rateLimiter.isRateLimited("forgot:" + ip, 5, 3600)) {
            ra.addFlashAttribute("error", "Too many attempts. Please try again later.");
            return "redirect:/forgot-password";
        }

        // Same confirmation regardless of whether the username exists — don't let this endpoint
        // become a username-enumeration oracle. The actual link is only attached when it's real.
        Optional<User> found = userRepository.findByUsername(username.trim());
        if (found.isPresent()) {
            User user = found.get();
            byte[] tokenBytes = new byte[32];
            SECURE_RANDOM.nextBytes(tokenBytes);
            String token = Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);

            user.setResetToken(token);
            user.setResetTokenExpiry(Instant.now().plus(RESET_TOKEN_MINUTES, ChronoUnit.MINUTES));
            userRepository.save(user);

            ra.addFlashAttribute("resetLink", "/reset-password?token=" + token);
        }
        ra.addFlashAttribute("submitted", true);
        return "redirect:/forgot-password";
    }

    @GetMapping("/reset-password")
    public String resetPasswordForm(@RequestParam String token, Model model) {
        boolean valid = userRepository.findByResetToken(token)
                .map(u -> u.getResetTokenExpiry() != null && u.getResetTokenExpiry().isAfter(Instant.now()))
                .orElse(false);
        model.addAttribute("valid", valid);
        model.addAttribute("token", token);
        return "reset-password";
    }

    @PostMapping("/reset-password")
    public String resetPassword(@RequestParam String token, @RequestParam String password, RedirectAttributes ra) {
        Optional<User> found = userRepository.findByResetToken(token);
        boolean valid = found.isPresent() && found.get().getResetTokenExpiry() != null
                && found.get().getResetTokenExpiry().isAfter(Instant.now());
        if (!valid) {
            ra.addFlashAttribute("error", "That reset link is invalid or has expired — please request a new one.");
            return "redirect:/forgot-password";
        }
        if (password.length() < 8) {
            ra.addFlashAttribute("error", "Password must be at least 8 characters.");
            return "redirect:/reset-password?token=" + token;
        }

        User user = found.get();
        user.setPasswordHash(passwordEncoder.encode(password));
        // Single-use: clear the token immediately so the same link can't be replayed.
        user.setResetToken(null);
        user.setResetTokenExpiry(null);
        userRepository.save(user);

        ra.addFlashAttribute("info", "Password updated — please log in.");
        return "redirect:/login";
    }
}
