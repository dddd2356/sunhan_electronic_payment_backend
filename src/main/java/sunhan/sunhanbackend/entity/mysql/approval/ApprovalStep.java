package sunhan.sunhanbackend.entity.mysql.approval;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import sunhan.sunhanbackend.enums.approval.ApproverType;

@Entity
@Table(name = "approval_step")
@Getter
@Setter
@NoArgsConstructor
public class ApprovalStep {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "approval_line_id", nullable = false)
    @JsonIgnore
    private ApprovalLine approvalLine;

    @Column(name = "step_order", nullable = false)
    private Integer stepOrder; // 단계 순서 (1, 2, 3...)

    @Column(name = "step_name", nullable = false)
    private String stepName; // 단계 이름 (예: "대직자 승인", "부서장 승인")

    @Column(name = "approver_type")
    @Enumerated(EnumType.STRING)
    private ApproverType approverType; // SPECIFIC_USER, JOB_LEVEL, DEPARTMENT_HEAD, HR_STAFF 등

    @Column(name = "approver_id")
    private String approverId; // 특정 사용자인 경우 userId

    @Column(name = "job_level")
    private String jobLevel; // jobLevel 기반인 경우

    @Column(name = "dept_code")
    private String deptCode; // 부서 기반인 경우

    @Column(name = "is_optional")
    private Boolean isOptional = false; // 선택적 단계 여부

    @Column(name = "can_skip")
    private Boolean canSkip = false; // 건너뛸 수 있는지 여부

    @Column(name = "is_final_approval_available")
    private Boolean isFinalApprovalAvailable = false; // 전결 가능 여부

    // ✅ 복사 메서드 추가
    public ApprovalStep copy() {
        ApprovalStep newStep = new ApprovalStep();
        // ID와 ApprovalLine은 복사하지 않고, 나머지 필드만 복사
        newStep.setStepOrder(this.stepOrder);
        newStep.setStepName(this.stepName);
        newStep.setApproverType(this.approverType);
        newStep.setApproverId(this.approverId);
        newStep.setJobLevel(this.jobLevel);
        newStep.setDeptCode(this.deptCode);
        newStep.setIsOptional(this.isOptional);
        newStep.setCanSkip(this.canSkip);
        newStep.setIsFinalApprovalAvailable(this.isFinalApprovalAvailable);
        return newStep;
    }
}