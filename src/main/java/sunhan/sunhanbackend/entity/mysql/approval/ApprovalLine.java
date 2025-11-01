package sunhan.sunhanbackend.entity.mysql.approval;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import sunhan.sunhanbackend.enums.approval.DocumentType;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "approval_line")
@Getter
@Setter
@NoArgsConstructor
public class ApprovalLine {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name; // 결재라인 이름 (예: "일반 직원 휴가 결재라인")

    private String description; // 설명

    @Column(name = "document_type")
    @Enumerated(EnumType.STRING)
    private DocumentType documentType; // LEAVE_APPLICATION, EMPLOYMENT_CONTRACT 등

    @Column(name = "created_by")
    private String createdBy; // 생성자 userId

    @Column(name = "is_active")
    private Boolean isActive = true; // 활성화 여부

    @Column(name = "is_deleted", nullable = false)
    private Boolean isDeleted = false; // 삭제 여부(DB에는 남아있음)

    @OneToMany(mappedBy = "approvalLine", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("stepOrder ASC")
    private List<ApprovalStep> steps = new ArrayList<>();

    @CreationTimestamp
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
