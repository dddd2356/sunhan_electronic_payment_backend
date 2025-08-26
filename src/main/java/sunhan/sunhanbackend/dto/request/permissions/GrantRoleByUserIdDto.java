package sunhan.sunhanbackend.dto.request.permissions;

import lombok.*;

/**
 * 개별 사용자 권한 부여/제거 요청 DTO
 */
@Data
@NoArgsConstructor
public class GrantRoleByUserIdDto {
    private String targetUserId;
}