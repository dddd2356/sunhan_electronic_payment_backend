package sunhan.sunhanbackend.dto.response;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import sunhan.sunhanbackend.dto.approval.ApprovalLineResponseDto;
import sunhan.sunhanbackend.dto.request.LeaveSummaryDto;
import sunhan.sunhanbackend.entity.mysql.LeaveApplication;
import sunhan.sunhanbackend.entity.mysql.UserEntity;
import sunhan.sunhanbackend.enums.LeaveApplicationStatus;
import sunhan.sunhanbackend.enums.LeaveType;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class LeaveApplicationResponseDto {
    private Long id;
    private String applicantId;
    private String applicantName;
    private String applicantDept;
    private String applicantDeptName;
    private String applicantPosition;
    private String applicantContact;
    private String applicantPhone;

    private String substituteId;
    private String substituteName;
    private String substitutePosition;

    private String currentApproverId;
    private Integer currentStepOrder;
    private Boolean isFinalApproved;
    private String finalApproverId;
    private LocalDateTime finalApprovalDate;
    private String finalApprovalStep;

    private LeaveType leaveType;
    private String leaveDetail;
    private LocalDate startDate;
    private LocalDate endDate;
    private Double totalDays;
    private LocalDate applicationDate;
    private LeaveApplicationStatus status;
    private String currentApprovalStep;
    private String rejectionReason;

    private Boolean isApplicantSigned;
    private Boolean isSubstituteApproved;
    private Boolean isDeptHeadApproved;
    private Boolean isHrStaffApproved;
    private Boolean isCenterDirectorApproved;
    private Boolean isHrFinalApproved;
    private Boolean isAdminDirectorApproved;
    private Boolean isCeoDirectorApproved;
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSSSS")
    private LocalDateTime createdAt;
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSSSS")
    private LocalDateTime updatedAt;

    // --- 새로 추가된 필드들 (formDataJson, PDF, printable) ---
    private String formDataJson;
    private Map<String, List<Map<String, Object>>> signatures;
    private String pdfUrl;
    private boolean printable;
    private List<AttachmentResponseDto> attachments;
    private ApprovalLineResponseDto approvalLine;

    public static LeaveApplicationResponseDto fromEntity(
            LeaveApplication app,
            UserEntity applicant,
            UserEntity substitute
    ) {
        LeaveApplicationResponseDto dto = new LeaveApplicationResponseDto();
        // 기본 필드 매핑
        dto.setId(app.getId());
        dto.setApplicantId(app.getApplicantId());
        dto.setApplicantName(applicant.getUserName());
        dto.setApplicantDept(applicant.getDeptCode());
        dto.setApplicantPosition(applicant.getJobLevel());
        String fullAddress = applicant.getAddress() != null ? applicant.getAddress() : "";
        String detailAddress = applicant.getDetailAddress() != null ? applicant.getDetailAddress() : "";
        dto.setApplicantContact((fullAddress + " " + detailAddress).trim());
        dto.setApplicantPhone(applicant.getPhone());

        if (substitute != null) {
            dto.setSubstituteId(substitute.getUserId());
            dto.setSubstituteName(substitute.getUserName());
            dto.setSubstitutePosition(convertLevelToPosition(substitute.getJobLevel()));
        } else {
            dto.setSubstituteId(app.getSubstituteId());
            dto.setSubstituteName(null);
            dto.setSubstitutePosition(null);
        }

        dto.setLeaveType(app.getLeaveType());
        dto.setLeaveDetail(app.getLeaveDetail());
        dto.setStartDate(app.getStartDate());
        dto.setEndDate(app.getEndDate());
        dto.setTotalDays(app.getTotalDays());
        dto.setApplicationDate(app.getApplicationDate());
        dto.setStatus(app.getStatus());
        dto.setCurrentApprovalStep(app.getCurrentApprovalStep());
        dto.setCurrentApproverId(app.getCurrentApproverId());
        dto.setCurrentStepOrder(app.getCurrentStepOrder());

        // ✅ 결재라인 정보 매핑
        if (app.getApprovalLine() != null) {
            dto.setApprovalLine(ApprovalLineResponseDto.fromEntity(app.getApprovalLine()));
        }

        dto.setRejectionReason(app.getRejectionReason());
        dto.setFormDataJson(app.getFormDataJson());
        dto.setPdfUrl(app.getPdfUrl());
        dto.setPrintable(app.isPrintable());
        dto.setIsFinalApproved(app.getIsFinalApproved());
        dto.setFinalApproverId(app.getFinalApproverId());
        dto.setFinalApprovalDate(app.getFinalApprovalDate());
        dto.setFinalApprovalStep(app.getFinalApprovalStep());
        if (app.getAttachments() != null) {
            dto.setAttachments(app.getAttachments().stream()
                    .map(AttachmentResponseDto::fromEntity)
                    .collect(Collectors.toList()));
        }
        // formDataJson 파싱해서 signatures 상태 반영
        Map<String, List<Map<String, Object>>> sigMap = Map.of();
        try {
            if (app.getFormDataJson() != null && !app.getFormDataJson().isEmpty()) {
                Map<String, Object> formData = new ObjectMapper()
                        .readValue(app.getFormDataJson(), Map.class);
                Object signaturesObject = formData.get("signatures");
                if (signaturesObject != null) {
                    sigMap = new ObjectMapper()
                            .convertValue(signaturesObject,
                                    new TypeReference<Map<String, List<Map<String, Object>>>>() {});
                }
            }
        } catch (Exception e) {
            // Log the error or handle it as needed. For now, it will default to an empty map.
        }

        // **전체 signatures 맵 설정**
        dto.setSignatures(sigMap);

        // applicant
        boolean applicantSigned = sigMap.getOrDefault("applicant", List.of())
                .stream()
                .anyMatch(entry -> Boolean.TRUE.equals(entry.get("isSigned")));
        dto.setIsApplicantSigned(applicantSigned);
        // substitute
        boolean substituteSigned = sigMap.getOrDefault("substitute", List.of())
                .stream()
                .anyMatch(entry -> Boolean.TRUE.equals(entry.get("isSigned")));
        dto.setIsSubstituteApproved(substituteSigned);
        // departmentHead
        boolean deptHeadSigned = sigMap.getOrDefault("departmentHead", List.of())
                .stream()
                .anyMatch(entry -> Boolean.TRUE.equals(entry.get("isSigned")));
        dto.setIsDeptHeadApproved(deptHeadSigned);
        // hrStaff
        boolean hrStaffSigned = sigMap.getOrDefault("hrStaff", List.of())
                .stream()
                .anyMatch(entry -> Boolean.TRUE.equals(entry.get("isSigned")));
        dto.setIsHrStaffApproved(hrStaffSigned);
        // centerDirector
        boolean centerDirectorSigned = sigMap.getOrDefault("centerDirector", List.of())
                .stream()
                .anyMatch(entry -> Boolean.TRUE.equals(entry.get("isSigned")));
        dto.setIsCenterDirectorApproved(centerDirectorSigned);
        // adminDirector
        boolean adminDirectorSigned = sigMap.getOrDefault("adminDirector", List.of())
                .stream()
                .anyMatch(entry -> Boolean.TRUE.equals(entry.get("isSigned")));
        dto.setIsAdminDirectorApproved(adminDirectorSigned);
        // ceoDirector
        boolean ceoDirectorSigned = sigMap.getOrDefault("ceoDirector", List.of())
                .stream()
                .anyMatch(entry -> Boolean.TRUE.equals(entry.get("isSigned")));
        dto.setIsCeoDirectorApproved(ceoDirectorSigned);

        dto.setCreatedAt(app.getCreatedAt());
        dto.setUpdatedAt(app.getUpdatedAt());
        dto.setCurrentStepOrder(app.getCurrentStepOrder());
        return dto;
    }

    private static String convertLevelToPosition(String lvl) {
               return switch (lvl) {
                     case "0" -> "사원";
                       case "1" -> "부서장";
                       case "2" -> "센터장";
                       case "3" -> "원장";
                       case "4" -> "행정원장";
                       case "5" -> "대표원장";
                       default -> "알수없음";
                   };
           }
    public static LeaveApplicationResponseDto fromSummary(LeaveSummaryDto s) {
        LeaveApplicationResponseDto dto = new LeaveApplicationResponseDto();
        dto.setId(s.getId());
        dto.setStartDate(s.getStartDate());
        dto.setEndDate(s.getEndDate());
        dto.setTotalDays(s.getTotalDays());
        dto.setStatus(s.getStatus());
        dto.setApplicantName(s.getApplicantName());
        dto.setSubstituteName(s.getSubstituteName());
        return dto;
    }
}
