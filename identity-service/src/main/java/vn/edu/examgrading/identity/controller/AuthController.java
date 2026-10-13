package vn.edu.examgrading.identity.controller;

import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import vn.edu.examgrading.identity.dto.*;
import vn.edu.examgrading.identity.service.IdentityAuthService;

@RestController
@RequestMapping("/api/v1")
public class AuthController {
    private final IdentityAuthService auth;
    public AuthController(IdentityAuthService auth) { this.auth = auth; }
    @PostMapping("/auth/login") public LoginResponse login(@Valid @RequestBody LoginRequest request) { return auth.login(request); }
    @GetMapping("/users/me") public UserResponse me(@AuthenticationPrincipal Jwt jwt) { return UserResponse.from(auth.me(UUID.fromString(jwt.getSubject()))); }
    @PostMapping("/users/me/change-password") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void changePassword(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody ChangePasswordRequest request) { auth.changePassword(UUID.fromString(jwt.getSubject()), request.currentPassword(), request.newPassword()); }
    @PostMapping("/users") @PreAuthorize("hasRole('EXAM_OFFICE')")
    public ResponseEntity<UserCredentialsResponse> register(@Valid @RequestBody RegisterUserRequest request) { return ResponseEntity.status(HttpStatus.CREATED).body(auth.register(request)); }
    @PostMapping("/users/{userId}/reset-password") @PreAuthorize("hasRole('EXAM_OFFICE')")
    public UserCredentialsResponse resetPassword(@PathVariable UUID userId) { return auth.resetPassword(userId); }
}
