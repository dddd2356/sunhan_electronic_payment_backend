package sunhan.sunhanbackend.dto.response;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class DepartmentSummaryDto {
    private String deptCode;
    private String deptName;
    private Integer totalEmployees;
    private Double avgUsageRate;
}
