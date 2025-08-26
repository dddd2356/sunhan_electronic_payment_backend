package sunhan.sunhanbackend.dto.request.auth;

import lombok.Data;
import lombok.Getter;
import lombok.Setter;

@Data
@Getter
@Setter
public class RejectRequestDto {
    private String reason;
}