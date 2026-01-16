package sunhan.sunhanbackend.dto.response;

import lombok.Builder;
import lombok.Data;
import sunhan.sunhanbackend.enums.HalfDayType;

import java.time.LocalDate;

@Data
@Builder
public class DailyUsageDto {
    private LocalDate date;
    private HalfDayType halfDayType;  // ALL_DAY, MORNING, AFTERNOON
    private double days;               // 1.0 or 0.5
}