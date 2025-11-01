package sunhan.sunhanbackend.entity.mysql.approval;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import sunhan.sunhanbackend.enums.approval.ApprovalAction;

import java.time.LocalDateTime;

@Entity
@Table(name = "approval_step_history")
@Getter
@Setter
@NoArgsConstructor
public class ApprovalStepHistory {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "approval_process_id", nullable = false)
    private DocumentApprovalProcess approvalProcess;

    @Column(name = "step_order", nullable = false)
    private Integer stepOrder;

    @Column(name = "step_name", nullable = false)
    private String stepName;

    // ✅ 승인자 ID (제출 시점 스냅샷)
    @Column(name = "approver_id", nullable = false)
    private String approverId;

    // ✅ 승인자 정보 스냅샷 (제출 시점 기준)
    @Column(name = "approver_name")
    private String approverName;

    @Column(name = "approver_job_level")
    private String approverJobLevel;

    @Column(name = "approver_dept_code")
    private String approverDeptCode;

    // ✅ 승인 액션
    @Column(name = "action", nullable = false)
    @Enumerated(EnumType.STRING)
    private ApprovalAction action;

    @Column(name = "comment", columnDefinition = "TEXT")
    private String comment;

    // ✅ 서명 관련
    @Column(name = "signature_image_url")
    private String signatureImageUrl;

    @Column(name = "is_signed")
    private Boolean isSigned = false;

    // ✅ 서명 시간 및 IP (감사 로그)
    @Column(name = "action_date")
    private LocalDateTime actionDate;

    @Column(name = "ip_address")
    private String ipAddress;

    // ✅ 문서 해시 (제출 시점 문서의 해시값 - 변조 방지)
    @Column(name = "document_hash")
    private String documentHash;

    @CreationTimestamp
    private LocalDateTime createdAt;
}
