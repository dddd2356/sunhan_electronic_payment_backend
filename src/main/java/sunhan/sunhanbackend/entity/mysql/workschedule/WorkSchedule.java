package sunhan.sunhanbackend.entity.mysql.workschedule;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import sunhan.sunhanbackend.entity.mysql.UserEntity;
import sunhan.sunhanbackend.entity.mysql.approval.ApprovalLine;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 근무현황표 메인 엔티티
 */
@Entity
@Table(name = "work_schedule",
        uniqueConstraints = {
                @UniqueConstraint(columnNames = {"dept_code", "schedule_year_month"})
        },
        indexes = {
                @Index(name = "idx_schedule_dept_month", columnList = "dept_code, schedule_year_month"),
                @Index(name = "idx_schedule_status", columnList = "approval_status")
        }
)
@Getter
@Setter
@NoArgsConstructor
public class WorkSchedule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by", referencedColumnName = "id", insertable = false, updatable = false)
    private UserEntity creator;

    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true; // 활성 여부: true면 '생성 가능한(활성) 근무표', false면 아카이브된(비활성)

    @Column(name = "dept_code", nullable = false, length = 10)
    private String deptCode; // 부서 코드

    @Column(name = "schedule_year_month", nullable = false, length = 7)
    private String scheduleYearMonth; // "YYYY-MM" 형식 (예: "2025-01")

    @Column(name = "created_by", nullable = false, length = 20)
    private String createdBy; // 작성자 userId

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "approval_line_id")
    private ApprovalLine approvalLine; // 결재라인 참조

    @Column(name = "current_approval_step")
    private Integer currentApprovalStep = 0; // 현재 결재 단계 (0: 작성, 1: 첫번째 결재자...)

    @Enumerated(EnumType.STRING)
    @Column(name = "approval_status", nullable = false, length = 20)
    private ScheduleStatus approvalStatus = ScheduleStatus.DRAFT; // 상태

    @Column(name = "creator_signature_url", columnDefinition = "TEXT")
    private String creatorSignatureUrl;

    @Column(name = "creator_signed_at")
    private LocalDateTime creatorSignedAt;

    @Column(name = "remarks", columnDefinition = "TEXT")
    private String remarks; // 비고 (맨 아래 긴 비고란)

    @Column(name = "pdf_url", length = 500)
    private String pdfUrl; // 생성된 PDF 경로

    @Column(name = "is_printable", nullable = false)
    private Boolean isPrintable = false; // 인쇄 가능 여부

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @OneToMany(mappedBy = "workSchedule", fetch = FetchType.LAZY, cascade = CascadeType.ALL)
    @OrderBy("displayOrder ASC") // 표시 순서 보장 (선택)
    private List<WorkScheduleEntry> entries = new ArrayList<>();

    @Column(name = "is_custom", nullable = false)
    private Boolean isCustom = false; // 커스텀 근무표 여부

    @Column(name = "custom_dept_name", length = 100)
    private String customDeptName; // 커스텀 부서명 (isCustom=true일 때 사용)

    /**
     * 근무현황표 상태
     */
    public enum ScheduleStatus {
        DRAFT,          // 임시저장
        SUBMITTED,      // 제출됨
        APPROVED,       // 승인 완료
        REJECTED        // 반려됨
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    public UserEntity getUser() {
        return this.creator;
    }
}