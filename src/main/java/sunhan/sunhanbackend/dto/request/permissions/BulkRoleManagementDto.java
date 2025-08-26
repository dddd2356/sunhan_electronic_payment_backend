package sunhan.sunhanbackend.dto.request.permissions;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 일괄 권한 관리 요청 DTO
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class BulkRoleManagementDto {
    private String[] targetUserIds;
    private String action; // "GRANT" or "REVOKE"
}