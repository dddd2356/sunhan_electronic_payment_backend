package sunhan.sunhanbackend.dto.workschedule;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 엔트리 상세 응답 DTO
 */
@Data
@NoArgsConstructor
public class WorkScheduleEntryDetailDto {
    private Long id;
    private String userId;
    private String userName;
    private String positionName;
    private Integer displayOrder;

    // 근무 데이터
    private Map<String, String> workData;

    // 나이트 통계
    private Integer nightDutyRequired;      // 의무 개수 (수동 입력)
    private Integer nightDutyActual;        // 실제 개수 (자동 계산)
    private Integer nightDutyAdditional;    // 추가 개수 (actual - required)
    private String nightDutyDisplay;        // 표시 문자열 ("4/4" 또는 "." 또는 "5/4 (+1)")

    // OFF 개수
    private Integer offCount;

    // 휴가 통계
    private Integer vacationTotal;          // 총 휴가수 (from UserEntity)
    private Integer vacationUsedThisMonth;  // 이달 사용수 (자동 계산: "연" 개수)
    private Integer vacationUsedTotal;      // 사용 총계 (from UserEntity)
    private Integer vacationRemaining;      // 잔여 (total - usedTotal)

    // 비고
    private String remarks;

    /**
     * 나이트 표시 문자열 생성
     * - 의무 개수 == 실제 개수 -> "."
     * - 의무 개수 != 실제 개수 -> "5/4 (+1)" 또는 "3/4 (-1)"
     */
    public void generateNightDutyDisplay() {
        if (nightDutyRequired == null || nightDutyActual == null) {
            this.nightDutyDisplay = "-";
            return;
        }

        if (nightDutyRequired.equals(nightDutyActual)) {
            this.nightDutyDisplay = ".";
        } else {
            int diff = nightDutyActual - nightDutyRequired;
            String sign = diff > 0 ? "+" : "";
            this.nightDutyDisplay = String.format("%d/%d (%s%d)",
                    nightDutyActual, nightDutyRequired, sign, diff);
        }
    }

    /**
     * 잔여 휴가 계산
     */
    public void calculateVacationRemaining() {
        if (vacationTotal != null && vacationUsedTotal != null) {
            this.vacationRemaining = vacationTotal - vacationUsedTotal;
        } else {
            this.vacationRemaining = 0;
        }
    }
}
