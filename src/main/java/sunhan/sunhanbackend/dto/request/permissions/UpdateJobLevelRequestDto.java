package sunhan.sunhanbackend.dto.request.permissions;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * JobLevel 변경 요청 DTO
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class UpdateJobLevelRequestDto {
    private String targetUserId;
    private String newJobLevel;
}