package sunhan.sunhanbackend.entity.mysql.position;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * 부서별 직책 엔티티
 */
@Entity
@Table(name = "position",
        uniqueConstraints = {
                @UniqueConstraint(columnNames = {"dept_code", "position_name"})
        },
        indexes = {
                @Index(name = "idx_position_dept", columnList = "dept_code"),
                @Index(name = "idx_position_order", columnList = "dept_code, display_order")
        }
)
@Getter
@Setter
@NoArgsConstructor
public class Position {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "dept_code", nullable = false, length = 10)
    private String deptCode; // 부서 코드

    @Column(name = "position_name", nullable = false, length = 50)
    private String positionName; // 직책명 (예: "수간호사", "간호사", "보조")

    @Column(name = "display_order", nullable = false)
    private Integer displayOrder = 0; // 표시 순서 (작을수록 상위)

    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true; // 사용 여부

    @Column(name = "created_by", length = 20)
    private String createdBy; // 생성자 userId

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public Position(String deptCode, String positionName, Integer displayOrder, String createdBy) {
        this.deptCode = deptCode;
        this.positionName = positionName;
        this.displayOrder = displayOrder != null ? displayOrder : 0;
        this.createdBy = createdBy;
        this.isActive = true;
    }
}