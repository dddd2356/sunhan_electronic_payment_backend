package sunhan.sunhanbackend.dto.request;

import lombok.Data;

@Data
public class ResetPasswordRequest {
    private String targetUserId;
    private String newPassword;
}