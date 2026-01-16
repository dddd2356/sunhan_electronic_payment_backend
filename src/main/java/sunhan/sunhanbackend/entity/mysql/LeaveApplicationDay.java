package sunhan.sunhanbackend.entity.mysql;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import sunhan.sunhanbackend.enums.HalfDayType;

import java.time.LocalDate;

@Entity
@Table(name = "leave_application_day")
@Data
@NoArgsConstructor
public class LeaveApplicationDay {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "leave_application_id", nullable = false)
    private LeaveApplication leaveApplication;

    @Column(name = "date", nullable = false)
    private LocalDate date;

    @Enumerated(EnumType.STRING)
    @Column(name = "half_day_type", nullable = false)
    private HalfDayType halfDayType;  // ALL_DAY, MORNING, AFTERNOON

    @Column(name = "days")
    private Double days;  // 1.0 or 0.5

    public LeaveApplicationDay(LocalDate date, HalfDayType halfDayType, Double days) {
        this.date = date;
        this.halfDayType = halfDayType;
        this.days = days;
    }
}