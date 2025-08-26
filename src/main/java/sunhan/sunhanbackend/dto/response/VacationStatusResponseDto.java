package sunhan.sunhanbackend.dto.response;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class VacationStatusResponseDto {
    private String userId;
    private String userName;
    private Integer totalVacationDays;
    private Integer usedVacationDays;
    private Integer remainingVacationDays;
}