package sunhan.sunhanbackend.entity.mysql.approval;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import sunhan.sunhanbackend.enums.approval.ApprovalProcessStatus;
import sunhan.sunhanbackend.enums.approval.DocumentType;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "document_approval_process")
@Getter
@Setter
@NoArgsConstructor
public class DocumentApprovalProcess {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "document_id", nullable = false)
    private Long documentId; // 문서 ID (LeaveApplication.id 등)

    @Column(name = "document_type", nullable = false)
    @Enumerated(EnumType.STRING)
    private DocumentType documentType;

    @Column(name = "applicant_id", nullable = false)
    private String applicantId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "approval_line_id", nullable = false)
    private ApprovalLine approvalLine;

    @Column(name = "current_step_order")
    private Integer currentStepOrder; // 현재 진행 중인 단계

    @Column(name = "status")
    @Enumerated(EnumType.STRING)
    private ApprovalProcessStatus status; // IN_PROGRESS, APPROVED, REJECTED, CANCELLED

    @OneToMany(mappedBy = "approvalProcess", cascade = CascadeType.ALL)
    @OrderBy("stepOrder ASC")
    private List<ApprovalStepHistory> stepHistories = new ArrayList<>();

    @CreationTimestamp
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}