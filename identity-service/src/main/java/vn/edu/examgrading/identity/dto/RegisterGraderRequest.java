package vn.edu.examgrading.identity.dto;

import jakarta.validation.constraints.NotBlank;

/** Payload intentionally has no role field: public registration always creates a grader. */
public record RegisterGraderRequest(@NotBlank String username, @NotBlank String fullName, String email, @NotBlank String password) { }
