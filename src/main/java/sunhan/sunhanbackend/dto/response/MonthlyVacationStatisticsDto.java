package sunhan.sunhanbackend.dto.response;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDate;
import java.util.Map;

@Getter
@Builder
public class MonthlyVacationStatisticsDto {
    private String userId;
    private String userName;
    private String deptCode;
    private LocalDate startDate;
    private Integer totalDays;
    private Map<String, Double> monthlyUsage; // "2026-01" -> 2.5일
    private Double totalUsed;
    private Double remaining;
}