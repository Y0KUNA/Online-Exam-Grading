package vn.edu.examgrading.identity.dto;
public record LoginResponse(String accessToken, String tokenType, long expiresIn, boolean mustChangePassword) { }
