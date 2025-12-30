package sunhan.sunhanbackend.dto.response;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class VacationHistoryResponseDto {
    private Long id;
    private String startDate;
    private String endDate;
    private Integer days;
    private String reason;
    private String status;
    private String createdDate;
    private String leaveType;
}