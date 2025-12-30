package sunhan.sunhanbackend.dto.response;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class VacationStatusResponseDto {
    private String userId;
    private String userName;
    private String deptName;
    private Double totalVacationDays; // Double
    private Double usedVacationDays; // Double
    private Double remainingVacationDays; // Double
}