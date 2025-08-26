package sunhan.sunhanbackend.dto.request;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class LeaveApplicationPdfData {
    private String leaveType;
    private String startDate;
    private String endDate;
    private String startDayType;
    private String endDayType;
    private String totalDays;
    private String reason;
    private String rejectionReason;

    private String applicantName;
    private String applicantDept;
    private String applicantPosition;
    private String applicantContact;
    private String applicantPhone;

    private String substituteName;
    private String substitutePosition;

    private String approvalManagerName;
    private String approvalDirectorName;
    private String approvalManagerSignUrl;
    private String approvalDirectorSignUrl;

    private boolean isHrStaffApproved;
    private boolean isCenterDirectorApproved;
    private boolean isAdminDirectorApproved;
    private boolean isCeoDirectorApproved;

    private boolean isFinalApproved;
    private String finalApprovalDate;
}