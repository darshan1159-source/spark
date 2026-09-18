package com.studentresume.portal;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.client.RestTemplate;

@SpringBootApplication
public class StudentResumePortalApplication {

    public static void main(String[] args) {
        SpringApplication.run(StudentResumePortalApplication.class, args);
    }

    /**
     * Single RestTemplate bean used by CvParsingService and AssistantService to call Ollama.
     * A plain {@code new RestTemplate()} has NO timeout at all (connect and read both default
     * to "wait forever" at the OS socket level) — if Ollama hangs or the model stalls mid
     * generation, the request thread blocks indefinitely instead of eventually failing into
     * the existing "AI unavailable" handling both callers already have.
     */
    @Bean
    public RestTemplate restTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5_000);   // Ollama not even accepting a connection — fail fast
        factory.setReadTimeout(120_000);    // generation can legitimately take 60-90s+ on this hardware
        return new RestTemplate(factory);
    }

    /** BCrypt only — no full Spring Security starter, matching this app's hand-rolled auth. */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
