package sunhan.sunhanbackend.entity.mysql;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(
        name = "user_annual_vacation_history",
        uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "year"})
)
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserAnnualVacationHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private String userId;

    @Column(name = "year", nullable = false)
    private Integer year;

    // ✅ 여기에 연차 데이터 저장
    @Column(name = "carryover_days")
    private Double carryoverDays = 0.0;

    @Column(name = "regular_days")
    private Double regularDays = 15.0;

    @Column(name = "used_carryover_days")
    private Double usedCarryoverDays = 0.0;

    @Column(name = "used_regular_days")
    private Double usedRegularDays = 0.0;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    // ✅ 여기에 계산 메서드 존재
    /**
     * 총 연차 (이월 + 정상)
     */
    public Double getTotalDays() {
        return (carryoverDays != null ? carryoverDays : 0.0) +
                (regularDays != null ? regularDays : 15.0);
    }

    /**
     * 사용한 총 연차
     */
    public Double getUsedDays() {
        return (usedCarryoverDays != null ? usedCarryoverDays : 0.0) +
                (usedRegularDays != null ? usedRegularDays : 0.0);
    }

    /**
     * 남은 연차
     */
    public Double getRemainingDays() {
        return getTotalDays() - getUsedDays();
    }
}