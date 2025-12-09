package sunhan.sunhanbackend.entity.mysql.workschedule;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 근무현황표의 각 직원별 상세 정보
 */
@Entity
@Table(name = "work_schedule_entry",
        indexes = {
                @Index(name = "idx_entry_schedule", columnList = "work_schedule_id"),
                @Index(name = "idx_entry_user", columnList = "user_id")
        }
)
@Getter
@Setter
@NoArgsConstructor
public class WorkScheduleEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "work_schedule_id", nullable = false)
    private WorkSchedule workSchedule;

    @Column(name = "user_id", nullable = false, length = 20)
    private String userId; // 직원 ID

    @Column(name = "user_name", length = 100)
    private String userName; // 엔트리에 저장된 사용자 이름 (퇴사/이동 시에도 표시용)

    @Column(name = "position_id")
    private Long positionId; // 직책 ID (Position 테이블 참조)

    @Column(name = "display_order", nullable = false)
    private Integer displayOrder = 0; // 표시 순서

    @Column(name = "work_data_json", columnDefinition = "TEXT")
    private String workDataJson; // 근무 데이터 JSON
    // 예: {"1": "D", "2": "N", "3": "E", "4": "Off", "5": "연", "6": "회의", ...}
    // D(Day), N(Night), E(Evening), Off, 연(연차), 또는 자유 텍스트 가능

    @Column(name = "night_duty_required")
    private Integer nightDutyRequired = 0; // 의무 나이트 개수

    @Column(name = "night_duty_actual")
    private Integer nightDutyActual = 0; // 실제 나이트 개수

    @Column(name = "night_duty_additional")
    private Integer nightDutyAdditional = 0; // 추가 나이트 개수

    // 당직 모드용 세부 카운트 (JSON)
    @Column(name = "duty_detail_json", columnDefinition = "TEXT")
    private String dutyDetailJson;
    // 예: {"평일": 3, "금요일": 1, "토요일": 1, "공휴일 및 일요일": 2}

    @Column(name = "off_count")
    private Integer offCount = 0; // OFF 개수

    @Column(name = "vacation_used_this_month")
    private Double vacationUsedThisMonth = 0.0; // ✅ Integer → Double

    @Column(name = "vacation_total")
    private Double vacationTotal = 0.0; // ✅ Integer → Double

    @Column(name = "vacation_used_total")
    private Double vacationUsedTotal = 0.0; // ✅ Integer → Double

    @Column(name = "remarks", columnDefinition = "TEXT")
    private String remarks; // 개인별 비고

    @Column(name = "dept_code", length = 10)
    private String deptCode; // 해당 직원의 원 소속 부서 (커스텀 시 필요)

    @Column(name = "is_deleted", nullable = false)
    private Boolean isDeleted = false; // 논리적 삭제 플래그 (삭제 대신 플래그 사용)

    public WorkScheduleEntry(WorkSchedule workSchedule, String userId, Integer displayOrder) {
        this.workSchedule = workSchedule;
        this.userId = userId;
        this.displayOrder = displayOrder != null ? displayOrder : 0;
    }
}