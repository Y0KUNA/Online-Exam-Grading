package vn.edu.examgrading.identity.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "users")
public class UserAccount {
    @Id @Column(name = "user_id") private UUID id;
    @Column(nullable = false) private String username;
    @Column(name = "password_hash", nullable = false) private String passwordHash;
    @Column(name = "full_name", nullable = false) private String fullName;
    private String email;
    @Enumerated(EnumType.STRING) @Column(nullable = false) private UserRole role;
    @Column(name = "is_active", nullable = false) private boolean active = true;
    @Column(name = "last_login_at") private Instant lastLoginAt;
    @Column(name = "failed_login_count", nullable = false) private int failedLoginCount;
    @Column(name = "locked_until") private Instant lockedUntil;
    @Column(name = "must_change_password", nullable = false) private boolean mustChangePassword;

    protected UserAccount() { }
    public UserAccount(String username, String passwordHash, String fullName, String email, UserRole role, boolean mustChangePassword) {
        this.id = UUID.randomUUID(); this.username = username; this.passwordHash = passwordHash; this.fullName = fullName;
        this.email = email; this.role = role; this.mustChangePassword = mustChangePassword;
    }
    public UUID id() { return id; } public String username() { return username; } public String passwordHash() { return passwordHash; }
    public String fullName() { return fullName; } public String email() { return email; } public UserRole role() { return role; }
    public boolean active() { return active; } public boolean mustChangePassword() { return mustChangePassword; }
    public boolean locked(Instant now) { return lockedUntil != null && lockedUntil.isAfter(now); }
    public void loginSucceeded(Instant now) { failedLoginCount = 0; lockedUntil = null; lastLoginAt = now; }
    public void loginFailed(Instant now, int maxFailures, int lockMinutes) {
        failedLoginCount++;
        if (maxFailures > 0 && failedLoginCount >= maxFailures) lockedUntil = now.plusSeconds(lockMinutes * 60L);
    }
    public void changePassword(String hash) { passwordHash = hash; mustChangePassword = false; failedLoginCount = 0; lockedUntil = null; }
    public void resetPassword(String hash, boolean requireChange) { passwordHash = hash; mustChangePassword = requireChange; failedLoginCount = 0; lockedUntil = null; }
    public void setActive(boolean active) { this.active = active; }
}
