package sunhan.sunhanbackend.repository.mysql.workschedule;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import sunhan.sunhanbackend.entity.mysql.workschedule.WorkSchedule;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.Query;

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

    /**
     * 특정 년월, 부서, 상태로 근무표 조회
     */
    Optional<WorkSchedule> findByScheduleYearMonthAndDeptCodeAndApprovalStatus(
            String scheduleYearMonth,
            String deptCode,
            WorkSchedule.ScheduleStatus status
    );

    /**
     * 내 근무표 조회 쿼리
     * 1. (내 부서이면서 && 커스텀이 아닌 것) -> 부서원에게 공유됨
     * 2. OR (내가 작성한 것) -> 커스텀이든 아니든 작성자는 봐야 함
     * 3. OR (내가 엔트리에 포함된 것) -> 커스텀에 초대된 경우
     */
    @Query("SELECT DISTINCT w FROM WorkSchedule w " +
            "LEFT JOIN w.entries e " +
            "WHERE (w.deptCode = :deptCode AND (w.isCustom = false OR w.isCustom IS NULL)) " + // 일반 근무표만 부서 공유
            "OR w.createdBy = :userId " +                                                      // 작성자 본인
            "OR (e.userId = :userId AND e.isDeleted = false) " +                               // 참여자로 포함됨
            "ORDER BY w.scheduleYearMonth DESC")
    List<WorkSchedule> findRelevantSchedules(@Param("deptCode") String deptCode,
                                             @Param("userId") String userId);

    /**
     * WORK_SCHEDULE_MANAGE 권한자용: 모든 부서의 완료된 근무표 조회
     */
    List<WorkSchedule> findByApprovalStatusInOrderByScheduleYearMonthDesc(
            List<WorkSchedule.ScheduleStatus> statuses
    );

    /**
     * 생성자 + 년월 + 커스텀 + 상태로 조회 (커스텀 근무표용)
     */
    Optional<WorkSchedule> findByCreatedByAndScheduleYearMonthAndIsCustomAndApprovalStatus(
            String createdBy,
            String scheduleYearMonth,
            boolean isCustom,
            WorkSchedule.ScheduleStatus approvalStatus
    );

    /**
     * 내가 작성한 문서 중 특정 상태들만 조회
     */
    List<WorkSchedule> findByCreatedByAndApprovalStatusInOrderByScheduleYearMonthDesc(
            String createdBy,
            List<WorkSchedule.ScheduleStatus> statuses
    );

    /**
     * 완료된 근무표 조회 (내 부서 + 내가 참여한 커스텀)
     */
    @Query("SELECT DISTINCT w FROM WorkSchedule w " +
            "LEFT JOIN w.entries e " +
            "WHERE w.approvalStatus = :status " +
            "AND (" +
            "   (w.deptCode = :deptCode AND (w.isCustom = false OR w.isCustom IS NULL)) " +
            "   OR (e.userId = :userId AND e.isDeleted = false)" +
            ") " +
            "ORDER BY w.scheduleYearMonth DESC")
    List<WorkSchedule> findCompletedSchedulesForUser(
            @Param("deptCode") String deptCode,
            @Param("userId") String userId,
            @Param("status") WorkSchedule.ScheduleStatus status
    );

    List<WorkSchedule> findByScheduleYearMonthAndApprovalStatus(String scheduleYearMonth,
                                                                WorkSchedule.ScheduleStatus approvalStatus);
}