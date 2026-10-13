package vn.edu.examgrading.identity.dto;

public record UserCredentialsResponse(UserResponse user, String temporaryPassword, boolean mustChangePassword) {
}
