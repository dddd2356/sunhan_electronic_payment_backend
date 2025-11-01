package sunhan.sunhanbackend.repository.mysql;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import sunhan.sunhanbackend.entity.mysql.VerificationCode;

@Repository
public interface VerificationCodeRepository extends JpaRepository<VerificationCode, String> {
    // 기본 메서드: findById(phone)로 조회 가능
    default VerificationCode findByPhone(String phone) {
        return findById(phone).orElse(null);
    }
}