package com.studentresume.portal.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * Backs the background CV-parsing flow (see {@code ResumeParsingOrchestrator}). A small, bounded
 * pool — Ollama itself only processes one request at a time per model (confirmed via {@code
 * ollama ps} showing {@code -np 1}), so a bigger pool here wouldn't parallelize LLM throughput,
 * just move the queueing from this app to Ollama's own layer instead.
 *
 * <p>{@code CallerRunsPolicy}: if the pool AND its queue both fill (~26 concurrent in-flight
 * parses), the next upload runs synchronously on the request thread instead of being dropped —
 * degrades back to today's old blocking behavior for that one unlucky request rather than losing
 * the upload outright. Astronomically unlikely at this app's scale; a deliberate tradeoff, not
 * an oversight.
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    @Bean
    public ThreadPoolTaskExecutor resumeParsingExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(20);
        executor.setThreadNamePrefix("resume-parse-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }
}
