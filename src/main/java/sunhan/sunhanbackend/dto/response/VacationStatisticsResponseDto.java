package sunhan.sunhanbackend.dto.response;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

//휴가 통계 페이지
@Getter
@Builder
public class VacationStatisticsResponseDto {
    private String deptCode;
    private String deptName;
    private Integer totalEmployees;
    private Double avgUsageRate;
    private Double totalVacationDays;
    private Double totalUsedDays;
    private Double totalRemainingDays;
    private List<EmployeeVacationDto> employees;
}
