package sunhan.sunhanbackend.repository.mysql.workschedule;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import sunhan.sunhanbackend.entity.mysql.workschedule.WorkSchedule;
import sunhan.sunhanbackend.entity.mysql.workschedule.WorkScheduleEntry;

import java.util.List;
import java.util.Optional;

@Repository
public interface WorkScheduleEntryRepository extends JpaRepository<WorkScheduleEntry, Long> {

    /**
     * 특정 근무표의 모든 엔트리 조회 (표시 순서로 정렬)
     */
    List<WorkScheduleEntry> findByWorkScheduleIdOrderByDisplayOrderAsc(Long workScheduleId);

    /**
     * 특정 근무표 + 사용자 조회
     */
    Optional<WorkScheduleEntry> findByWorkScheduleIdAndUserId(Long workScheduleId, String userId);

    /**
     * 특정 근무표의 엔트리 개수
     */
    long countByWorkScheduleId(Long workScheduleId);

    @Query("SELECT DISTINCT ws FROM WorkSchedule ws " +
            "JOIN ws.approvalLine al " +
            "JOIN al.steps step " +
            "WHERE ws.approvalStatus = 'SUBMITTED' " +
            "AND step.approverId = :userId " +
            "AND step.stepOrder = ws.currentApprovalStep")
    List<WorkSchedule> findPendingSchedulesForUser(String userId);

}