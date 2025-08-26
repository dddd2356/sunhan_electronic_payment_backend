package sunhan.sunhanbackend.dto.request;

import lombok.Data;
import lombok.Getter;
import lombok.Setter;

@Data
@Getter
@Setter
public class UpdateFormRequestDto {
    private String formDataJson;
    private Double totalDays;
}

