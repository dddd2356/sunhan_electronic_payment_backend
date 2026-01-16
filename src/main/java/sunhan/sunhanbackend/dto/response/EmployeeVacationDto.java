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
    // ✅ 수정: Integer → Double
    private Double annualCarryover;
    private Double annualRegular;
    private Double annualTotal;
    private Double annualUsed;
    private Double annualRemaining;
    private Double annualUsageRate;

    // 하위 호환
    @Deprecated
    private Double totalDays;
    @Deprecated
    private Double usedDays;
    @Deprecated
    private Double remainingDays;
    @Deprecated
    private Double usageRate;
}