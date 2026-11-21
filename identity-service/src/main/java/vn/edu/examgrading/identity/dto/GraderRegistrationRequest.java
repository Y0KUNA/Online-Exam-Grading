package vn.edu.examgrading.identity.dto;

import jakarta.validation.constraints.NotBlank;

/** Public registration payload: callers cannot choose an elevated role. */
public record GraderRegistrationRequest(
        @NotBlank String username,
        @NotBlank String fullName,
        String email,
        @NotBlank String password) { }
