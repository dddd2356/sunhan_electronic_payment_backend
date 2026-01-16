package sunhan.sunhanbackend.dto.consent;

import java.time.LocalDateTime;

public class ConsentResponseDto {
    private Long agreementId;
    private String title;      // ConsentForm에서 가져옴
    private String content;    // ConsentForm에서 가져옴
    private String status;     // ISSUED, COMPLETED 등
    private String targetName; // UserEntity와 조인하여 가져옴
    private LocalDateTime completedAt;
}
