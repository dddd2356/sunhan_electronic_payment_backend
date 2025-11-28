package sunhan.sunhanbackend.dto.workschedule;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 근무 데이터 업데이트 요청 DTO
 */
@Data
@NoArgsConstructor
public class WorkDataUpdateDto {
    private Long entryId;
    private Map<String, String> workData; // {"1": "D", "2": "N", "3": "연", "4": "회의", ...}
}

