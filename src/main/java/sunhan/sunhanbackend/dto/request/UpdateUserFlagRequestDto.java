package sunhan.sunhanbackend.dto.request;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class UpdateUserFlagRequestDto {
    private String targetUserId;
    private String newUseFlag;  // "0" 또는 "1"
}
