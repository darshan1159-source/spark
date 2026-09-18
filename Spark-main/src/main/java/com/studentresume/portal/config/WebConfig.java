package com.studentresume.portal.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Registers {@link AuthInterceptor} deny-by-default: everything requires login except this
 * short, explicit public allowlist. Any endpoint added later is automatically protected
 * unless deliberately excluded here — the opposite of enumerating protected paths, which
 * would silently ship a forgotten new controller unauthenticated.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final AuthInterceptor authInterceptor;

    public WebConfig(AuthInterceptor authInterceptor) {
        this.authInterceptor = authInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authInterceptor)
                .excludePathPatterns(
                        "/", "/login", "/register", "/logout",
                        "/forgot-password", "/reset-password",
                        "/css/**", "/js/**", "/avatars/**", "/favicon.ico", "/error"
                );
    }

    /**
     * Serves uploaded profile pictures straight from disk (outside the classpath, since files
     * written under src/main/resources/static wouldn't persist in a packaged jar). Avatars are
     * low-sensitivity and filenames include a random token (see AccountController), so serving
     * them unauthenticated — like the existing /css and /js — is an acceptable trade-off against
     * hand-rolling an authenticated file-streaming endpoint for what's just a profile picture.
     */
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/avatars/**")
                .addResourceLocations("file:./uploads/avatars/");
    }
}
