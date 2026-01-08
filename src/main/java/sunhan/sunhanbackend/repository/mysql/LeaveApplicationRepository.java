package sunhan.sunhanbackend.repository.mysql;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import sunhan.sunhanbackend.entity.mysql.LeaveApplication;
import sunhan.sunhanbackend.enums.LeaveApplicationStatus;
import sunhan.sunhanbackend.enums.LeaveType;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Repository
public interface LeaveApplicationRepository extends JpaRepository<LeaveApplication, Long> {
    @EntityGraph(attributePaths = {"applicant", "substitute"})
    Optional<LeaveApplication> findById(Long id);

    /**
     * 신청자별 휴가원 조회 (N+1 문제 해결)
     * @EntityGraph를 사용하여 연관된 applicant와 substitute 엔티티를 함께 조회합니다.
     */
    @EntityGraph(attributePaths = {"applicant", "substitute"})
    @Query("SELECT la FROM LeaveApplication la WHERE la.applicant.userId = :applicantId ORDER BY la.createdAt DESC")
    Page<LeaveApplication> findByApplicant_UserId(@Param("applicantId") String applicantId, Pageable pageable);

    // 관리자가 모든 휴가 신청을 조회할 때 N+1 쿼리 방지
    @EntityGraph(value = "LeaveApplication.withApplicantAndSubstitute")
    Page<LeaveApplication> findAll(Pageable pageable);
    /**
     * 특정 사용자의 특정 상태 휴가 신청 조회
     */
    List<LeaveApplication> findByApplicantIdAndStatus(String applicantId, LeaveApplicationStatus status);

    // 🔧 N+1 쿼리 문제 해결: JOIN FETCH를 명시적으로 사용하여 applicant를 한 번에 조회
    @Query("SELECT la FROM LeaveApplication la JOIN FETCH la.applicant WHERE la.applicant.userId = :userId AND la.status = :status ORDER BY la.createdAt DESC")
    List<LeaveApplication> findByApplicantIdAndStatusWithApplicant(@Param("userId") String userId, @Param("status") LeaveApplicationStatus status);

    // pending용: 이미 일부 있음, 확장
    @EntityGraph(attributePaths = {"applicant", "substitute"})
    Page<LeaveApplication> findByCurrentApproverIdAndStatusIn(String currentApproverId, Set<LeaveApplicationStatus> statuses, Pageable pageable);

    // completed용: 이미 일부 있음 (findByStatus, findByApplicantIdAndStatusWithPaging), 필요 시 추가
    @EntityGraph(attributePaths = {"applicant", "substitute"})
    Page<LeaveApplication> findByStatus(LeaveApplicationStatus status, Pageable pageable);

    // ✨ N+1 문제 해결 및 페이징을 위한 메서드 (명시적 @Query 사용)
    @EntityGraph(attributePaths = {"applicant", "substitute"})
    @Query("SELECT la FROM LeaveApplication la WHERE la.applicant.userId = :userId AND la.status = :status")
    Page<LeaveApplication> findByApplicantIdAndStatusWithPaging(@Param("userId") String userId, @Param("status") LeaveApplicationStatus status, Pageable pageable);

    @EntityGraph(attributePaths = {"applicant", "substitute"})
    Page<LeaveApplication> findByStatusIn(Set<LeaveApplicationStatus> statuses, Pageable pageable);

    @Query("SELECT la FROM LeaveApplication la " +
            "JOIN FETCH la.applicant u " +
            "WHERE u.deptCode = :deptCode AND la.status = :status AND u.useFlag = '1'")
    List<LeaveApplication> findByDeptCodeAndStatus(
            @Param("deptCode") String deptCode,
            @Param("status") LeaveApplicationStatus status
    );

    @EntityGraph(attributePaths = {"applicant", "substitute"})
    @Query("SELECT la FROM LeaveApplication la WHERE la.applicant.userId = :userId AND la.status IN :statuses")
    Page<LeaveApplication> findByApplicantIdAndStatusIn(
            @Param("userId") String userId,
            @Param("statuses") Set<LeaveApplicationStatus> statuses,
            Pageable pageable
    );

    /**
     * ✅ 특정 기간 및 사용자의 승인된 휴가원 조회
     */
    @Query("SELECT la FROM LeaveApplication la WHERE " +
            "la.applicant.userId IN :userIds AND " +
            "la.status = :status AND " +
            "la.leaveType = :leaveType AND " +
            "((la.startDate BETWEEN :startDate AND :endDate) OR " +
            "(la.endDate BETWEEN :startDate AND :endDate) OR " +
            "(la.startDate < :startDate AND la.endDate > :endDate))")
    List<LeaveApplication> findByUserIdsAndPeriod(
            @Param("userIds") List<String> userIds,
            @Param("status") LeaveApplicationStatus status,
            @Param("leaveType") LeaveType leaveType,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate
    );
}