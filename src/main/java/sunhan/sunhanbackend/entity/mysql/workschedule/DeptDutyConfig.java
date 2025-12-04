package sunhan.sunhanbackend.entity.mysql.workschedule;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

// 부서별 당직 설정 엔티티
@Entity
@Table(name = "dept_duty_config")
@Getter
@Setter
@NoArgsConstructor
public class DeptDutyConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "schedule_id", nullable = false, unique = true)
    private Long scheduleId;

    @Enumerated(EnumType.STRING)
    @Column(name = "duty_mode", nullable = false)
    private DutyMode dutyMode = DutyMode.NIGHT_SHIFT;

    @Column(name = "display_name", nullable = false)
    private String displayName = "나이트";

    @Column(name = "cell_symbol", nullable = false)
    private String cellSymbol = "N";

    // 당직 모드 세부 설정
    @Column(name = "use_weekday")
    private Boolean useWeekday = false;

    @Column(name = "use_friday")
    private Boolean useFriday = false;

    @Column(name = "use_saturday")
    private Boolean useSaturday = false;

    @Column(name = "use_holiday_sunday")
    private Boolean useHolidaySunday = false;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public enum DutyMode {
        NIGHT_SHIFT,    // 나이트 (단순 카운트)
        ON_CALL_DUTY    // 당직 (요일별 구분)
    }
}