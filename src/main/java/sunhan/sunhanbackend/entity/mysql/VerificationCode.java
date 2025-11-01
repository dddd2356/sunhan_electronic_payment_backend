package sunhan.sunhanbackend.entity.mysql;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
public class VerificationCode {
    @Id
    private String phone;  // 전화번호를 ID로 사용 (유일성 보장)
    private String code;   // 인증 코드
    private LocalDateTime expiry;  // 만료 시간
}
