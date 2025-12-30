package sunhan.sunhanbackend.dto.approval;

import lombok.Data;
import sunhan.sunhanbackend.enums.approval.ApproverType;

@Data
public class ApprovalStepDto {
    private Integer stepOrder;
    private String stepName;
    private ApproverType approverType;
    private String approverId;
    private String jobLevel;
    private String deptCode;

    private Boolean isOptional = false;
//    private Boolean canSkip = false;
//    private Boolean isFinalApprovalAvailable = false;
}