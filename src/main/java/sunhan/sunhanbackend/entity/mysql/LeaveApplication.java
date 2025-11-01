package sunhan.sunhanbackend.entity.mysql;
import jakarta.persistence.*;
import lombok.Data;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import sunhan.sunhanbackend.entity.mysql.approval.ApprovalLine;
import sunhan.sunhanbackend.entity.mysql.approval.DocumentApprovalProcess;
import sunhan.sunhanbackend.enums.LeaveApplicationStatus;
import sunhan.sunhanbackend.enums.LeaveType;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Table(name = "leave_application")
@Data
@NoArgsConstructor
@Getter
@Setter

// 🔧 엔티티 그래프 정의 추가
@Entity
@NamedEntityGraphs({
        @NamedEntityGraph(
                name = "LeaveApplication.withApplicantAndSubstitute",
                attributeNodes = {
                        @NamedAttributeNode("applicant"),
                        @NamedAttributeNode("substitute")
                }
        ),
        @NamedEntityGraph(
                name = "LeaveApplication.withApplicant",
                attributeNodes = {
                        @NamedAttributeNode("applicant")
                }
        ),
        @NamedEntityGraph(
                name = "LeaveApplication.withUsers",
                attributeNodes = {
                        @NamedAttributeNode("applicant"),
                        @NamedAttributeNode("substitute")
                }
        )
})
public class LeaveApplication {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String applicantId;
    private String substituteId;


    // JOIN FETCH를 위한 관계 설정
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "applicantId", insertable = false, updatable = false)
    private UserEntity applicant;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "substituteId", insertable = false, updatable = false)
    private UserEntity substitute;

    @Enumerated(EnumType.STRING)
    @Column(name = "leave_type")
    private LeaveType leaveType; // 휴가 종류

    @Column(name = "leave_detail", columnDefinition = "TEXT")
    private String leaveDetail; // 휴가 상세 내용 (경조휴가, 특별휴가, 병가 등의 세부사항)

    @Column(name = "start_date")
    private LocalDate startDate; // 휴가 시작일

    @Column(name = "end_date")
    private LocalDate endDate; // 휴가 종료일

    @Column(name = "total_days")
    private Double totalDays;  // 총 휴가 일수

    @Column(name = "application_date", nullable = false)
    private LocalDate applicationDate; // 신청일

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private LeaveApplicationStatus status; // 승인 상태

    @Column(name = "approval_history", columnDefinition = "TEXT")
    private String approvalHistory; // 승인 이력 (JSON 형태)

    @Column(name = "form_data_json", columnDefinition = "TEXT")
    private String formDataJson; // 전체 폼 데이터 (JSON 형태)

    @Column(name = "rejection_reason", columnDefinition = "TEXT")
    private String rejectionReason; // 반려 사유

    @Column(name = "is_final_approved")
    private Boolean isFinalApproved = false; //전결처리 되었는지

    @Column(name = "final_approver_id")
    private String finalApproverId;  //누가 전결처리했는지

    @Column(name = "final_approval_date")
    private LocalDateTime finalApprovalDate; //전결처리한 날짜

    @Column(name = "final_approval_step")
    private String finalApprovalStep; // 어느 단계에서 전결했는지

    @Column(name = "pdf_url")
    private String pdfUrl; // 생성된 PDF 파일 경로

    @Column(name = "is_printable", nullable = false)
    private boolean printable = false; // 인쇄 가능 여부

    @Deprecated
    @Column(name = "is_substitute_approved")
    private Boolean isSubstituteApproved = false; // 대직자 승인 여부

    // --- 다음 필드들을 추가해야 합니다 ---
    @Column(name = "is_applicant_signed")
    private Boolean isApplicantSigned = false; // 신청자 서명 여부

    @Deprecated
    @Column(name = "is_dept_head_approved")
    private Boolean isDeptHeadApproved = false; // 부서장 승인 여부

    @Deprecated
    @Column(name = "is_hr_staff_approved")
    private Boolean isHrStaffApproved = false; // 인사팀 승인 여부

    @Deprecated
    @Column(name = "is_center_director_approved")
    private Boolean isCenterDirectorApproved = false; // 진료센터장 승인 여부

    @Deprecated
    @Column(name = "is_hr_final_approved")
    private Boolean isHrFinalApproved = false;

    @Deprecated
    @Column(name = "is_admin_director_approved")
    private Boolean isAdminDirectorApproved = false; // 행정원장 승인 여부

    @Deprecated
    @Column(name = "is_ceo_director_approved")
    private Boolean isCeoDirectorApproved = false; // 대표원장 승인 여부


    // ------------------------------------

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @OneToMany(mappedBy = "leaveApplication", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<LeaveApplicationAttachment> attachments = new ArrayList<>();

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "approval_line_id")
    private ApprovalLine approvalLine; // 선택된 결재라인

    // ⚠️ 기존 필드들은 하위 호환성을 위해 유지하되, deprecated 처리 고려
    @Deprecated
    @Column(name = "current_approver_id")
    private String currentApproverId;

    @Deprecated
    @Column(name = "current_approval_step")
    private String currentApprovalStep;

    @Column(name = "current_step_order")
    private Integer currentStepOrder;

    public boolean isUsingApprovalLine() {
        return approvalLine != null;
    }

    // 양방향 연관관계 편의 메서드
    public void addAttachment(LeaveApplicationAttachment attachment) {
        this.attachments.add(attachment);
    }
}