package sunhan.sunhanbackend.dto.request;

import lombok.Data;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@Data
public class LeaveApplicationUpdateFormRequestDto {
    // ✅ 1. 선택된 결재라인 ID 필드 추가 (필수)
    private Long approvalLineId;
    private ApplicantInfo applicantInfo;
    private SubstituteInfo substituteInfo;
    private List<String> leaveTypes;
    private Map<String, String> leaveContent;
    private List<Map<String, String>> flexiblePeriods;
    private Map<String, String> consecutivePeriod;
    private Double totalDays;
    private LocalDate applicationDate;
    private Map<String, List<Map<String, Object>>> signatures;

    // 내부 클래스들
    @Data
    public static class ApplicantInfo {
        private String userId;
        private String department;
        private String name;
        private String position;
        private String contact;
        private String phone;
    }

    @Data
    public static class SubstituteInfo {
        private String userId;
        private String name;
        private String position;
    }
}