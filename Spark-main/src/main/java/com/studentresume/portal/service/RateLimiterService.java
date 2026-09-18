package com.studentresume.portal.service;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * A small in-memory, per-key sliding-window rate limiter. Hand-rolled rather than pulling in
 * bucket4j/resilience4j, matching the rest of this app's minimal-dependency auth code — and an
 * in-memory counter avoids adding write contention on the same SQLite file the rest of the app's
 * persistence already shares (SQLite has single-writer semantics).
 *
 * <p>Not distributed — resets on restart and doesn't coordinate across multiple app instances.
 * Fine for a single-instance deployment; would need a shared store (e.g. Redis) beyond that.
 */
@Service
public class RateLimiterService {

    private final ConcurrentHashMap<String, Deque<Instant>> attempts = new ConcurrentHashMap<>();

    /**
     * Records an attempt for {@code key} and reports whether the caller is currently over the
     * limit — i.e. call this once per attempt (successful or not) and check the return value
     * *before* doing the expensive/sensitive work.
     *
     * @return true if this key has exceeded {@code maxAttempts} within the last {@code windowSeconds}
     */
    public boolean isRateLimited(String key, int maxAttempts, int windowSeconds) {
        Instant now = Instant.now();
        Instant windowStart = now.minusSeconds(windowSeconds);

        Deque<Instant> log = attempts.computeIfAbsent(key, k -> new ConcurrentLinkedDeque<>());
        log.add(now);

        // Trim anything older than the window — keeps each deque bounded and self-cleaning
        // without needing a separate scheduled sweep.
        while (true) {
            Instant oldest = log.peekFirst();
            if (oldest == null || !oldest.isBefore(windowStart)) break;
            log.pollFirst();
        }

        return log.size() > maxAttempts;
    }

    /** Clears a key's attempt history — call on a successful login so a legitimate user isn't penalized by earlier failed attempts. */
    public void reset(String key) {
        attempts.remove(key);
    }
}
