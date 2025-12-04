package sunhan.sunhanbackend.repository.mysql.workschedule;

import org.springframework.data.jpa.repository.JpaRepository;
import sunhan.sunhanbackend.entity.mysql.workschedule.DeptDutyConfig;

import java.util.Optional;

public interface DeptDutyConfigRepository extends JpaRepository<DeptDutyConfig, Long> {
    // ✅ scheduleId로 조회
    Optional<DeptDutyConfig> findByScheduleId(Long scheduleId);

    // ✅ scheduleId로 삭제 (필요시)
    void deleteByScheduleId(Long scheduleId);
}
