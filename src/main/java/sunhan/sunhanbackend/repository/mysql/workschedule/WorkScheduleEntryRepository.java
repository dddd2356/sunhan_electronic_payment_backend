package sunhan.sunhanbackend.repository.mysql.workschedule;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
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

    /**
     * 특정 유저의 해당 연도 휴가 사용량 합계 조회
     * 조건:
     * 1. 해당 연도(year)의 데이터
     * 2. 활성화된(isActive=true) 근무표
     * 3. 현재 수정 중인 근무표(excludeScheduleId)는 제외 (중복 더하기 방지)
     * 4. [추가됨] 최종 승인(APPROVED)된 근무표만 계산
     */
    @Query("SELECT SUM(e.vacationUsedThisMonth) FROM WorkScheduleEntry e " +
            "JOIN e.workSchedule w " +
            "WHERE e.userId = :userId " +
            "AND w.scheduleYearMonth LIKE CONCAT(:year, '-%') " +
            "AND w.isActive = true " +
            "AND w.approvalStatus = sunhan.sunhanbackend.entity.mysql.workschedule.WorkSchedule.ScheduleStatus.APPROVED " + // ✅ 승인된 문서 조건 추가
            "AND w.id != :excludeScheduleId")
    Double sumUsedVacationByUserIdAndYearExcludingCurrent(@Param("userId") String userId,
                                                          @Param("year") String year,
                                                          @Param("excludeScheduleId") Long excludeScheduleId);

    /**
     * 특정 유저의 특정 스케줄 ID에 해당하는 WorkScheduleEntry를 조회 (이전 달의 의무 나이트 개수를 가져오기 위함)
     * @param userId 사용자 ID
     * @param scheduleId 근무표 ID
     * @return WorkScheduleEntry (Optional)
     */
    Optional<WorkScheduleEntry> findByUserIdAndWorkScheduleId(String userId, Long scheduleId);

    @Query("""
    SELECT SUM(e.vacationUsedThisMonth)
    FROM WorkScheduleEntry e
    JOIN e.workSchedule s
    WHERE e.userId = :userId
      AND SUBSTRING(s.scheduleYearMonth, 1, 4) = :year
      AND s.approvalStatus = 'APPROVED'
""")
    Double sumApprovedVacationByUserIdAndYear(
            @Param("userId") String userId,
            @Param("year") String year
    );

    @Query("""
    SELECT SUM(e.vacationUsedThisMonth)
    FROM WorkScheduleEntry e
    JOIN e.workSchedule s
    WHERE e.userId = :userId
      AND SUBSTRING(s.scheduleYearMonth, 1, 4) = :year
      AND s.id != :excludeScheduleId
      AND s.approvalStatus = 'APPROVED'
""")
    Double sumApprovedVacationByUserIdAndYearExcludingCurrent(
            @Param("userId") String userId,
            @Param("year") String year,
            @Param("excludeScheduleId") Long excludeScheduleId
    );
}