package sunhan.sunhanbackend.dto.request;

import lombok.Data;
import sunhan.sunhanbackend.enums.ContractType;

@Data
public class CreateContractRequestDto {
    private String employeeId;
    private ContractType contractType;
    private Object formData; // 계약서별 다른 데이터 구조 허용
}
