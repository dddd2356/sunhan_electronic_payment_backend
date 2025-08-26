package sunhan.sunhanbackend.dto.request;

import lombok.Data;

@Data
public class SaveContractRequestDto {
    private String formDataJson;
    private boolean isDraft; // true: 임시저장, false: 정식저장
}