package com.studentresume.portal.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;

/**
 * Deny-by-default route guard: anything not explicitly excluded in {@link WebConfig} requires
 * {@code session.getAttribute("userId") != null}. Response handling is request-type-aware,
 * since a bare redirect breaks two real call shapes in this app:
 *
 * <ul>
 *   <li>htmx requests (every BuilderController/AssistantController call sends
 *       {@code HX-Request: true}) — htmx's XHR transparently follows a raw 302, and the
 *       resulting full login.html document would get swapped into whatever fragment target
 *       the request specified, corrupting the page with no visible error. Sending
 *       {@code HX-Redirect} as a response header instead makes htmx do a real top-level
 *       navigation.</li>
 *   <li>plain {@code fetch()} calls under {@code /api/**} — a redirect response would just be
 *       silently followed and then fail to parse as JSON; a 401 JSON body is handled cleanly
 *       by the existing client-side error handling.</li>
 * </ul>
 */
@Component
public class AuthInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        HttpSession session = request.getSession(false);
        if (session != null && session.getAttribute("userId") != null) {
            return true;
        }

        if ("true".equals(request.getHeader("HX-Request"))) {
            response.setHeader("HX-Redirect", "/login");
            response.setStatus(HttpServletResponse.SC_OK);
        } else if (request.getRequestURI().startsWith("/api/")) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"Please log in.\"}");
        } else {
            response.sendRedirect(request.getContextPath() + "/login");
        }
        return false;
    }
}
