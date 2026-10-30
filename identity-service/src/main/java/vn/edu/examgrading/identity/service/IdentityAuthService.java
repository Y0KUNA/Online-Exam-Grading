package vn.edu.examgrading.identity.service;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.edu.examgrading.identity.domain.UserAccount;
import vn.edu.examgrading.identity.domain.UserRole;
import vn.edu.examgrading.identity.dto.LoginRequest;
import vn.edu.examgrading.identity.dto.LoginResponse;
import vn.edu.examgrading.identity.dto.RegisterUserRequest;
import vn.edu.examgrading.identity.dto.RegisterGraderRequest;
import vn.edu.examgrading.identity.dto.UserCredentialsResponse;
import vn.edu.examgrading.identity.dto.UserResponse;
import vn.edu.examgrading.identity.repository.UserAccountRepository;

@Service
public class IdentityAuthService {
    private static final String INVALID_CREDENTIALS = "Invalid credentials";
    private static final SecureRandom RANDOM = new SecureRandom();
    private final UserAccountRepository users; private final PasswordEncoder passwordEncoder; private final JwtEncoder jwtEncoder;
    private final String issuer; private final int ttlMinutes; private final boolean localEnabled; private final int maxFailures; private final int lockMinutes;
    public IdentityAuthService(UserAccountRepository users, PasswordEncoder passwordEncoder, JwtEncoder jwtEncoder,
        @Value("${app.auth.issuer}") String issuer, @Value("${app.auth.ttl-minutes}") int ttlMinutes,
        @Value("${app.auth.local-enabled}") boolean localEnabled, @Value("${app.auth.login-max-failures}") int maxFailures,
        @Value("${app.auth.login-lock-minutes}") int lockMinutes) {
        this.users = users; this.passwordEncoder = passwordEncoder; this.jwtEncoder = jwtEncoder; this.issuer = issuer;
        this.ttlMinutes = ttlMinutes; this.localEnabled = localEnabled; this.maxFailures = maxFailures; this.lockMinutes = lockMinutes;
    }
    @Transactional public LoginResponse login(LoginRequest request) {
        if (!localEnabled) throw new InvalidCredentialsException();
        if (request == null || request.username() == null || request.password() == null) throw new IllegalArgumentException("username and password are required");
        Instant now = Instant.now();
        UserAccount user = users.findByUsernameIgnoreCase(request.username()).orElseThrow(InvalidCredentialsException::new);
        if (!user.active() || user.locked(now) || !passwordEncoder.matches(request.password(), user.passwordHash())) {
            user.loginFailed(now, maxFailures, lockMinutes); throw new InvalidCredentialsException();
        }
        user.loginSucceeded(now);
        Instant expiry = now.plusSeconds(ttlMinutes * 60L);
        JwtClaimsSet claims = JwtClaimsSet.builder().issuer(issuer).subject(user.id().toString()).issuedAt(now).expiresAt(expiry)
            .id(UUID.randomUUID().toString()).claim("role", user.role().name()).build();
        return new LoginResponse(jwtEncoder.encode(JwtEncoderParameters.from(claims)).getTokenValue(), "Bearer", ttlMinutes * 60L, user.mustChangePassword());
    }
    @Transactional public UserCredentialsResponse register(RegisterUserRequest request) {
        if (request == null) throw new IllegalArgumentException("request is required");
        if (request.role() == null) throw new IllegalArgumentException("role is required");
        String username = normalizeText(request.username());
        String fullName = normalizeText(request.fullName());
        String email = blankToNull(request.email());
        if (users.existsByUsernameIgnoreCase(username)) throw new DuplicateUserException("USERNAME_TAKEN");
        if (email != null && users.existsByEmailIgnoreCase(email)) throw new DuplicateUserException("EMAIL_TAKEN");
        String password = request.password() == null ? null : request.password().trim();
        boolean mustChangePassword = false;
        String temporaryPassword = null;
        if (password == null || password.isBlank()) {
            temporaryPassword = generateTemporaryPassword();
            password = temporaryPassword;
            mustChangePassword = true;
        } else if (password.length() < 8) {
            throw new IllegalArgumentException("password must contain at least 8 characters");
        }
        UserAccount created = users.save(new UserAccount(username, passwordEncoder.encode(password), fullName, email, request.role(), mustChangePassword));
        return new UserCredentialsResponse(UserResponse.from(created), temporaryPassword, created.mustChangePassword());
    }
    /** Public self-service registration is deliberately limited to graders. */
    @Transactional public UserCredentialsResponse registerGrader(RegisterGraderRequest request) {
        if (request == null) throw new IllegalArgumentException("request is required");
        return register(new RegisterUserRequest(request.username(), request.fullName(), request.email(), UserRole.GRADER, request.password()));
    }
    @Transactional public UserCredentialsResponse resetPassword(UUID userId) {
        UserAccount user = users.findById(userId).orElseThrow(UserNotFoundException::new);
        String temporaryPassword = generateTemporaryPassword();
        user.resetPassword(passwordEncoder.encode(temporaryPassword), true);
        return new UserCredentialsResponse(UserResponse.from(user), temporaryPassword, true);
    }
    @Transactional public void bootstrapSingleton(UserRole role, String username, String password, String fullName) {
        if ((role != UserRole.EXAM_OFFICE && role != UserRole.COUNCIL)
            || username == null || username.isBlank() || password == null || password.length() < 8 || users.existsByRole(role)) return;
        String name = fullName == null || fullName.isBlank() ? username.trim() : fullName.trim();
        users.save(new UserAccount(username.trim(), passwordEncoder.encode(password), name, null, role, true));
    }
    @Transactional public void changePassword(UUID id, String currentPassword, String newPassword) {
        if (newPassword == null || newPassword.length() < 8) throw new IllegalArgumentException("password must contain at least 8 characters");
        UserAccount user = users.findById(id).orElseThrow(UserNotFoundException::new);
        if (!passwordEncoder.matches(currentPassword, user.passwordHash())) throw new InvalidCredentialsException();
        user.changePassword(passwordEncoder.encode(newPassword));
    }
    public UserAccount me(UUID id) { return users.findById(id).orElseThrow(UserNotFoundException::new); }
    private String blankToNull(String value) { return value == null || value.isBlank() ? null : value.trim(); }
    private String normalizeText(String value) {
        String normalized = blankToNull(value);
        if (normalized == null || normalized.isBlank()) throw new IllegalArgumentException("required field is missing");
        return normalized;
    }
    private String generateTemporaryPassword() {
        String chars = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789!@#$%";
        StringBuilder password = new StringBuilder();
        for (int i = 0; i < 16; i++) password.append(chars.charAt(RANDOM.nextInt(chars.length())));
        return password.toString();
    }
    public static final class InvalidCredentialsException extends RuntimeException { public InvalidCredentialsException() { super(INVALID_CREDENTIALS); } }
    public static final class DuplicateUserException extends RuntimeException { public DuplicateUserException(String code) { super(code); } }
    public static final class UserNotFoundException extends RuntimeException { public UserNotFoundException() { super("USER_NOT_FOUND"); } }
}
