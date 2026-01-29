package sunhan.sunhanbackend.dto.approval;

import lombok.Data;
import sunhan.sunhanbackend.entity.mysql.approval.ApprovalStep;
import sunhan.sunhanbackend.enums.approval.ApproverType;

@Data
public class ApprovalStepResponseDto {
    private Long id;
    private Integer stepOrder;
    private String stepName;
    private ApproverType approverType;
    private String approverId;
    private String approverName;
    private String jobLevel;
    private String deptCode;
    private Boolean isOptional;

    public static ApprovalStepResponseDto fromEntity(ApprovalStep entity) {
        ApprovalStepResponseDto dto = new ApprovalStepResponseDto();
        dto.setId(entity.getId());
        dto.setStepOrder(entity.getStepOrder());
        dto.setStepName(entity.getStepName());
        dto.setApproverType(entity.getApproverType());
        dto.setApproverId(entity.getApproverId());
        dto.setJobLevel(entity.getJobLevel());
        dto.setDeptCode(entity.getDeptCode());
        dto.setIsOptional(entity.getIsOptional());
        return dto;
    }
}
