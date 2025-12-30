package sunhan.sunhanbackend.dto.response;

import lombok.Builder;
import lombok.Data;

//관리자 페이지 유저 통계
@Data
@Builder
public class AdminStatsDto {
    private long totalUsers;           // 전체 사용자 수 (활성 + 비활성)
    private long activeUsers;          // 활성 사용자 수 (useFlag='1')
    private long inactiveUsers;        // 비활성 사용자 수 (useFlag='0')
    private int totalDepartments;
}
