package ai.gradpath.domain;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A revocable server-side sign-in session. Only the SHA-256 hash of the random cookie token is stored,
 * so a database leak does not reveal usable tokens. Deleting the row signs that browser out.
 */
@Entity
@Table(name = "auth_sessions")
public class AuthSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;
    /** Database composition: deleting the account revokes its sessions via the foreign-key cascade. */
    @Column(name = "user_id", nullable = false)
    private Integer userId;
    @Column(name = "token_hash", length = 64, nullable = false, unique = true)
    private String tokenHash;
    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = Csv.utcNow();
    @Column(nullable = false)
    private boolean persistent = true;
    @Column(name = "last_used_at")
    private LocalDateTime lastUsedAt;

    public AuthSession() { }

    public AuthSession(Integer userId, String tokenHash, LocalDateTime expiresAt, boolean persistent) {
        this.userId = userId;
        this.tokenHash = tokenHash;
        this.expiresAt = expiresAt;
        this.persistent = persistent;
        this.lastUsedAt = createdAt;
    }

    public boolean isExpired(LocalDateTime now) {
        return !expiresAt.isAfter(now);
    }

    public Integer getId() { return id; }
    public Integer getUserId() { return userId; }
    public String getTokenHash() { return tokenHash; }
    public LocalDateTime getExpiresAt() { return expiresAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public boolean isPersistent() { return persistent; }
    public LocalDateTime getLastUsedAt() { return lastUsedAt; }
    public void setLastUsedAt(LocalDateTime lastUsedAt) { this.lastUsedAt = lastUsedAt; }
}
