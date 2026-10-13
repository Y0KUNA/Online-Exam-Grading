package vn.edu.examgrading.identity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import vn.edu.examgrading.identity.domain.UserRole;

public record RegisterUserRequest(@NotBlank String username, @NotBlank String fullName, String email, @NotNull UserRole role, String password) { }
