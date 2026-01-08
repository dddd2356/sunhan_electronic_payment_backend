package sunhan.sunhanbackend.dto.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Getter;
import lombok.Setter;

import java.util.List;
import java.util.Map;

@Getter // Lombok 사용 시 getter 자동 생성
@Setter // Lombok 사용 시 setter 자동 생성
@JsonIgnoreProperties(ignoreUnknown = true)
public class ContractFormData {
    public String contractTitle;
    public String employerName, employerAddress, employerPhone;
    public String employeeName, employeeAddress, employeePhone, employeeSSN;
    public String startDate, workTime, contractDate, salaryContractDate;
    public String breakTime, totalAnnualSalary, basicSalary, positionAllowance;
    public String licenseAllowance, hazardPay, treatmentImprovementExpenses;
    public String adjustmentAllowance, overtimePay;
    public String nDutyAllowance, regularHourlyWage, employmentOccupation;
    public String dutyNight, receiptConfirmation1, receiptConfirmation2;
    public String writtenDate, employeeSignatureUrl;
    //제 5조 설명필요한 부분
    private String overtime;             // 라벨
    private String nDuty;                // 라벨
    private String overtimeDescription;  // 설명
    private String dutyDescription;      // 설명

    // 대표원장 관련 필드 추가
    public String ceoName;
    public String ceoSignatureUrl;

    public Map<String, String> agreements;
    public Map<String, List<SignatureEntry>> signatures;

    // === Getter with Default ===
    public String getOvertime() {
        return (overtime == null || overtime.trim().isEmpty())
                ? "연장/야간수당(고정)"
                : overtime.trim();
    }

    public String getNDuty() {
        return (nDuty == null || nDuty.trim().isEmpty())
                ? "N/당직수당"
                : nDuty.trim();
    }

    public String getOvertimeDescription() {
        return (overtimeDescription == null || overtimeDescription.trim().isEmpty())
                ? "월 소정근로시간 209시간을 초과한 연장근로, 야간근로 가산"
                : overtimeDescription.trim();
    }

    public String getDutyDescription() {
        return (dutyDescription == null || dutyDescription.trim().isEmpty())
                ? "의무나이트 이행 수당(의무 나이트 미수행 시 차감)"
                : dutyDescription.trim();
    }

    @Getter // Lombok 사용 시 getter 자동 생성
    @Setter // Lombok 사용 시 setter 자동 생성
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SignatureEntry {
        public String text;
        public String imageUrl;
        public boolean isSigned;
    }
}