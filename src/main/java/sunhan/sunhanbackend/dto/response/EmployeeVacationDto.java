package sunhan.sunhanbackend.dto.response;

import lombok.Builder;
import lombok.Getter;

//휴가 통계 페이지
@Getter
@Builder
public class EmployeeVacationDto {
    private String userId;
    private String userName;
    private String jobLevel;
    private String jobType;
    private Integer totalDays;
    private Integer usedDays;
    private Integer remainingDays;
    private Double usageRate;
}