package com.studentresume.portal.service;

import com.studentresume.portal.model.User;
import com.studentresume.portal.repository.UserRepository;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Service;

/**
 * Resolves the session's {@code userId} attribute into a {@link User} entity. Centralizes
 * what would otherwise be the same cast-and-lookup duplicated across every controller that
 * needs "who is currently logged in."
 */
@Service
public class CurrentUserProvider {

    private final UserRepository userRepository;

    public CurrentUserProvider(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /**
     * @throws IllegalStateException if there's no logged-in user — should be unreachable in
     *         practice, since every route that calls this sits behind {@code AuthInterceptor}.
     */
    public User require(HttpSession session) {
        Long userId = (session == null) ? null : (Long) session.getAttribute("userId");
        if (userId == null) {
            throw new IllegalStateException("No authenticated user in session");
        }
        return userRepository.findById(userId)
                .orElseThrow(() -> new IllegalStateException("Session user no longer exists"));
    }
}
