package vn.edu.examgrading.identity.dto;
import java.util.UUID;
import vn.edu.examgrading.identity.domain.UserAccount;
import vn.edu.examgrading.identity.domain.UserRole;
public record UserResponse(UUID userId, String username, String fullName, String email, UserRole role) {
    public static UserResponse from(UserAccount user) { return new UserResponse(user.id(), user.username(), user.fullName(), user.email(), user.role()); }
}
