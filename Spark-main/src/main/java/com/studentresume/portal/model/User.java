package com.studentresume.portal.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * A registered account. Deliberately {@code @Getter @Setter}, not Lombok {@code @Data} —
 * {@code @Data}'s generated equals/hashCode/toString over all fields is a known footgun on
 * JPA entities (triggers lazy-loads via logging, breaks on Hibernate proxies).
 */
@Entity
@Table(name = "users")
@Getter
@Setter
@NoArgsConstructor
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String username;

    @Column(nullable = false)
    private String passwordHash;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    /** Filename under the avatar storage directory (see AccountController), not a full path. Null = no avatar uploaded. */
    @Column
    private String avatarFilename;

    /**
     * Single-use password-reset token and its expiry. Null/cleared once used or once a newer
     * reset request supersedes it — only one active reset token per account at a time.
     */
    @Column
    private String resetToken;

    @Column
    private Instant resetTokenExpiry;
}
