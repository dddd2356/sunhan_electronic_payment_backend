package sunhan.sunhanbackend.dto.request.permissions;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 조건별 권한 부여 요청 DTO
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class GrantRoleByConditionDto {
    private String jobLevel;
    private String deptCode;
}