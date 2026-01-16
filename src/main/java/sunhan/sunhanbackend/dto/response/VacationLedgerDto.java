package sunhan.sunhanbackend.dto.response;


import lombok.Builder;
import lombok.Data;
import sunhan.sunhanbackend.enums.HalfDayType;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

//휴가 관리대장
@Data
public class VacationLedgerDto {
    private String title; // 엑셀 제목용
    private int year;
    private String filterDeptCode;
    private String filterDeptName;

    private int rowNumber;
    private String deptName;
    private String userName;
    private String userId;
    private String startDate;
    private String leaveType;

    // 연차만
    private Double carryoverDays;
    private Double regularDays;

    // 월별 사용 내역 (1~12월)
    private Map<Integer, MonthlyUsage> monthlyUsage = new HashMap<>();

    private Double totalUsed;
    private Double remaining;
    private String remarks;

    @Data
    public static class MonthlyUsage {
        private List<DailyDetail> details = new ArrayList<>();
        private Double monthTotal = 0.0;
    }

    @Data
    public static class DailyDetail {
        private String date;
        private HalfDayType halfDayType;
        private Double days;
    }
}