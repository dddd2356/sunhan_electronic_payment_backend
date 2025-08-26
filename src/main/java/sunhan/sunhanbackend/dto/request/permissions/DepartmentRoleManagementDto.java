package sunhan.sunhanbackend.dto.request.permissions;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 부서별 권한 관리 요청 DTO
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class DepartmentRoleManagementDto {
    private String deptCode;
    private String action; // "GRANT" or "REVOKE"
    private String targetJobLevel; // 특정 JobLevel만 대상으로 할 경우
}