package sunhan.sunhanbackend.repository.mysql.consent;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import sunhan.sunhanbackend.entity.mysql.consent.ConsentForm;
import sunhan.sunhanbackend.enums.consent.ConsentType;

import java.util.Optional;

@Repository
public interface ConsentFormRepository extends JpaRepository<ConsentForm, Long> {
    // 활성화된 양식 중 버전이 가장 높은 것 하나를 가져오는 쿼리 메서드
    Optional<ConsentForm> findTopByTypeAndIsActiveTrueOrderByVersionDesc(ConsentType type);
}