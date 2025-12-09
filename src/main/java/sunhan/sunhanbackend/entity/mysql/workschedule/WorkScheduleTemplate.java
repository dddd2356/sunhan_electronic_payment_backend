package sunhan.sunhanbackend.entity.mysql.workschedule;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "work_schedule_template")
@Getter
@Setter
@NoArgsConstructor
public class WorkScheduleTemplate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "created_by", nullable = false, length = 20)
    private String createdBy; // 템플릿 생성자

    @Column(name = "template_name", nullable = false, length = 100)
    private String templateName; // 템플릿 이름 (예: "외래 2팀", "야간 근무조")

    @Column(name = "custom_dept_name", length = 100)
    private String customDeptName; // 커스텀 부서명

    @Column(name = "member_ids_json", columnDefinition = "TEXT")
    private String memberIdsJson; // 구성원 userId 배열 JSON
    // 예: ["user001", "user002", "user003"]

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
