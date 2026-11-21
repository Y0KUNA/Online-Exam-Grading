package vn.edu.examgrading.identity.controller;

import jakarta.validation.Valid;
import java.util.UUID;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import vn.edu.examgrading.identity.dto.*;
import vn.edu.examgrading.identity.domain.UserRole;
import vn.edu.examgrading.identity.repository.UserAccountRepository;
import vn.edu.examgrading.identity.service.IdentityAuthService;

@RestController
@RequestMapping("/api/v1")
public class AuthController {
    private final IdentityAuthService auth;
    private final UserAccountRepository users;
    public AuthController(IdentityAuthService auth, UserAccountRepository users) { this.auth = auth; this.users = users; }
    @PostMapping("/auth/login") public LoginResponse login(@Valid @RequestBody LoginRequest request) { return auth.login(request); }
    @PostMapping("/auth/register/grader")
    public ResponseEntity<UserCredentialsResponse> registerGrader(@Valid @RequestBody GraderRegistrationRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(auth.register(new RegisterUserRequest(
            request.username(), request.fullName(), request.email(), UserRole.GRADER, request.password())));
    }
    @GetMapping("/users/me") @SuppressWarnings("null") public UserResponse me(@AuthenticationPrincipal Jwt jwt) { return UserResponse.from(auth.me(UUID.fromString(jwt.getSubject()))); }
    @PostMapping("/users/me/change-password") @ResponseStatus(HttpStatus.NO_CONTENT) @SuppressWarnings("null")
    public void changePassword(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody ChangePasswordRequest request) { auth.changePassword(UUID.fromString(jwt.getSubject()), request.currentPassword(), request.newPassword()); }
    @PostMapping("/users") @PreAuthorize("hasRole('EXAM_OFFICE')")
    public ResponseEntity<UserCredentialsResponse> register(@Valid @RequestBody RegisterUserRequest request) { return ResponseEntity.status(HttpStatus.CREATED).body(auth.register(request)); }
    @GetMapping("/users") @PreAuthorize("hasRole('EXAM_OFFICE')")
    public List<UserResponse> listGraders(@RequestParam(defaultValue = "GRADER") UserRole role) {
        if (role != UserRole.GRADER) throw new org.springframework.web.server.ResponseStatusException(HttpStatus.BAD_REQUEST, "Only GRADER accounts can be listed here");
        return users.findByRoleAndActiveTrueOrderByFullNameAsc(UserRole.GRADER).stream().map(UserResponse::from).toList();
    }
    @PostMapping("/users/{userId}/reset-password") @PreAuthorize("hasRole('EXAM_OFFICE')")
    public UserCredentialsResponse resetPassword(@PathVariable @org.springframework.lang.NonNull UUID userId) { return auth.resetPassword(userId); }
}
