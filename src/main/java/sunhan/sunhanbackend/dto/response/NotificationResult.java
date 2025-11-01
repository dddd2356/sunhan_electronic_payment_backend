package sunhan.sunhanbackend.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import sunhan.sunhanbackend.enums.NotificationChannel;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NotificationResult {
    private boolean success;
    private NotificationChannel channel;
    private String receiver;
    private String templateCode;
    private String message;
    private String rawResponse;
    private String errorMessage;

    public static NotificationResult success(NotificationChannel channel,
                                             String receiver,
                                             String templateCode,
                                             String message,
                                             String rawResponse) {
        return NotificationResult.builder()
                .success(true)
                .channel(channel)
                .receiver(receiver)
                .templateCode(templateCode)
                .message(message)
                .rawResponse(rawResponse)
                .build();
    }

    public static NotificationResult fail(NotificationChannel channel,
                                          String receiver,
                                          String templateCode,
                                          String errorMessage) {
        return NotificationResult.builder()
                .success(false)
                .channel(channel)
                .receiver(receiver)
                .templateCode(templateCode)
                .errorMessage(errorMessage)
                .build();
    }
}