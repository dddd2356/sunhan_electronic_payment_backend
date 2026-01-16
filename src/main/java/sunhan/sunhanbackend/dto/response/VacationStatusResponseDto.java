package sunhan.sunhanbackend.dto.response;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class VacationStatusResponseDto {
    private String userId;
    private String userName;
    private String deptName;
    private Integer year;

    // 연차만 (경조/특별은 차감 없으므로 제외)
    private Double annualCarryoverDays;
    private Double annualRegularDays;
    private Double annualTotalDays;
    // 사용 정보 추가
    private Double usedCarryoverDays;      // 이월 사용
    private Double usedRegularDays;        // 정상 사용
    private Double annualUsedDays;
    private Double annualRemainingDays;

    // 하위 호환
    @Deprecated
    private Double totalVacationDays;
    @Deprecated
    private Double usedVacationDays;
    @Deprecated
    private Double remainingVacationDays;
}