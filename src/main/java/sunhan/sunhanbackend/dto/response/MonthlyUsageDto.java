package sunhan.sunhanbackend.dto.response;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class MonthlyUsageDto {
    private List<DailyUsageDto> details;  // 해당 월의 일별 사용 상세
    private double monthTotal;             // 월 합계
}