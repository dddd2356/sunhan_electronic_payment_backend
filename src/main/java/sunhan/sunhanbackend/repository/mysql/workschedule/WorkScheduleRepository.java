package sunhan.sunhanbackend.repository.mysql.workschedule;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import sunhan.sunhanbackend.entity.mysql.workschedule.WorkSchedule;

import java.util.List;
import java.util.Optional;

@Repository
public interface WorkScheduleRepository extends JpaRepository<WorkSchedule, Long> {

    /**
     * 부서 + 년월로 근무표 조회
     */
    Optional<WorkSchedule> findByDeptCodeAndScheduleYearMonth(String deptCode, String scheduleYearMonth);

    /**
     * 부서별 모든 근무표 조회 (최신순)
     */
    List<WorkSchedule> findByDeptCodeOrderByScheduleYearMonthDesc(String deptCode);

    /**
     * 상태별 조회
     */
    List<WorkSchedule> findByApprovalStatusOrderByCreatedAtDesc(WorkSchedule.ScheduleStatus status);

    /**
     * 작성자별 조회
     */
    List<WorkSchedule> findByCreatedByOrderByScheduleYearMonthDesc(String createdBy);

    /**
     * 특정 년월의 부서별 근무표 목록
     */
    List<WorkSchedule> findByScheduleYearMonthOrderByDeptCodeAsc(String yearMonth);

    /**
     * 검토/승인 대기 중인 근무표 (특정 사용자가 처리해야 할)
     */
    @Query("SELECT DISTINCT ws FROM WorkSchedule ws " +
            "JOIN ws.approvalLine al " +
            "JOIN al.steps step " +
            "WHERE ws.approvalStatus = 'SUBMITTED' " +
            "AND step.approverId = :userId " +
            "AND step.stepOrder = ws.currentApprovalStep")
    List<WorkSchedule> findPendingSchedulesForUser(String userId);

    // DRAFT 상태의 내 근무표 조회
    List<WorkSchedule> findByCreatedByAndApprovalStatusOrderByScheduleYearMonthDesc(
            String createdBy,
            WorkSchedule.ScheduleStatus status
    );
    boolean existsByDeptCodeAndScheduleYearMonthAndIsActiveTrue(String deptCode, String scheduleYearMonth);
    Optional<WorkSchedule> findByDeptCodeAndScheduleYearMonthAndIsActiveTrue(String deptCode, String scheduleYearMonth);
}