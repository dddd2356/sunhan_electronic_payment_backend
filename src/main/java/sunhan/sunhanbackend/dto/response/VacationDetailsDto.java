package sunhan.sunhanbackend.dto.response;

import lombok.Data;

@Data
public class VacationDetailsDto {
    private Integer year;
    private Double annualCarryoverDays;  // 연차 이월
    private Double annualRegularDays;    // 연차 정상
    // 경조/특별은 차감하지 않으므로 설정 필드 없음
}
