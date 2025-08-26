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
    public String employerName, employerAddress, employerPhone;
    public String employeeName, employeeAddress, employeePhone, employeeSSN;
    public String startDate, workTime, contractDate, salaryContractDate;
    public String breakTime, totalAnnualSalary, basicSalary, positionAllowance;
    public String licenseAllowance, hazardPay, treatmentImprovementExpenses;
    public String specialAllowance, adjustmentAllowance, overtimePay;
    public String nDutyAllowance, regularHourlyWage, employmentOccupation;
    public String dutyNight, receiptConfirmation1, receiptConfirmation2;
    public String writtenDate, employeeSignatureUrl;
    //제 5조 설명필요한 부분
    public String overtimeDescription, dutyDescription;
    // 대표원장 관련 필드 추가
    public String ceoName;
    public String ceoSignatureUrl;

    public Map<String, String> agreements;
    public Map<String, List<SignatureEntry>> signatures;

    @Getter // Lombok 사용 시 getter 자동 생성
    @Setter // Lombok 사용 시 setter 자동 생성
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SignatureEntry {
        public String text;
        public String imageUrl;
        public boolean isSigned;
    }
}