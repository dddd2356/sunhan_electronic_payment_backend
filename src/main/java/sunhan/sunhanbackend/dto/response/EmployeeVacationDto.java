package sunhan.sunhanbackend.dto.response;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDate;

//휴가 통계 페이지
@Getter
@Builder
public class EmployeeVacationDto {
    private String userId;
    private String userName;
    private String deptCode;
    private String jobLevel;
    private String jobType;
    private LocalDate startDate;
    private Integer totalDays;
    private Integer usedDays;
    private Integer remainingDays;
    private Double usageRate;
}