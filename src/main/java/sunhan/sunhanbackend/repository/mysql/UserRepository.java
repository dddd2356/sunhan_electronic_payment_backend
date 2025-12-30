package sunhan.sunhanbackend.repository.mysql;

import jakarta.persistence.LockModeType;
import org.springframework.cache.annotation.Cacheable;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;


import sunhan.sunhanbackend.entity.mysql.UserEntity;

import sunhan.sunhanbackend.enums.Role;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<UserEntity, String> {
    // 단건 조회는 캐시 적용
    @Cacheable(value = "userCache", key = "#userId", condition = "#userId != null && !#userId.isEmpty()")
    Optional<UserEntity> findByUserId(String userId);
    // department를 함께 로드하는 안전한 조회 (엔티티그래프)

    @EntityGraph(attributePaths = {"department"})
    @Query("SELECT u FROM UserEntity u WHERE u.userId = :userId")
    Optional<UserEntity> findWithDeptByUserId(@Param("userId") String userId);

    // N+1 완화용: 여러 userId를 한 번에 가져올 때 department도 함께 로드
    @EntityGraph(attributePaths = {"department"})
    @Query("SELECT u FROM UserEntity u WHERE u.userId IN :userIds")
    List<UserEntity> findWithDeptByUserIdIn(@Param("userIds") Collection<String> userIds);
    @EntityGraph(attributePaths = {"department"})
    List<UserEntity> findByUseFlag(String useFlag);

    // 부서별 조회 (캐시)
    @Cacheable(value = "deptCache", key = "#deptCode")
    List<UserEntity> findByDeptCode(String deptCode);
    @EntityGraph(attributePaths = {"department"}) // 추가
    List<UserEntity> findByDeptCodeAndUseFlag(String deptCode, String useFlag);
    // 직급으로 조회 (결재자 검색용)f
    @Cacheable(value = "jobLevelUsersCache", key = "#jobLevel")
    List<UserEntity> findByJobLevel(String jobLevel);
    List<UserEntity> findByJobLevelAndDeptCode(String jobLevel, String deptCode);
    List<UserEntity> findByDeptCodeAndJobLevel(String deptCode, String jobLevel);
    @Query("select u from UserEntity u left join fetch u.department where u.userId = :userId")
    Optional<UserEntity> findByUserIdWithDepartment(@Param("userId") String userId);
    // 부서장(첫번째) 조회 (캐시) - key를 더 명확하게 수정
    @Cacheable(value = "deptManagerCache", key = "#deptCode + '_' + #jobLevel")
    Optional<UserEntity> findFirstByDeptCodeAndJobLevel(String deptCode, String jobLevel);
    // deptCode + useFlag로 첫 번째 UserEntity 조회
    Optional<UserEntity> findFirstByDeptCodeAndUseFlag(String deptCode, String useFlag);
    // 직급별 대표 조회 (캐시)
    @Cacheable(value = "jobLevelCache", key = "#jobLevel")
    Optional<UserEntity> findFirstByJobLevel(String jobLevel);
    /**
     * N+1 방지용: userId 목록으로 한 번에 조회
     */
    @Query("SELECT u FROM UserEntity u WHERE u.userId IN :userIds")
    List<UserEntity> findByUserIdIn(@Param("userIds") Collection<String> userIds);

    // 인사팀 직원 조회 (캐시)
    @Cacheable(value = "hrStaffCache", key = "#jobLevel + '_' + #deptCode + '_' + #role")
    Optional<UserEntity> findFirstByJobLevelAndDeptCodeAndRole(String jobLevel, String deptCode, Role role);

    // 부서내 정렬 조회 (캐시)
    @Cacheable(value = "deptUsersCache", key = "#deptCode")
    @Query("SELECT u FROM UserEntity u WHERE u.deptCode = :deptCode ORDER BY u.jobLevel, u.userName")
    List<UserEntity> findByDeptCodeOrderByJobLevelAndName(@Param("deptCode") String deptCode);

    // 직급 목록으로 조회
    @Query("SELECT u FROM UserEntity u WHERE u.jobLevel IN :jobLevels ORDER BY u.jobLevel")
    List<UserEntity> findByJobLevelIn(@Param("jobLevels") Collection<String> jobLevels);

    // 관리자 권한 체크용 최적화된 쿼리 (예시)
    @Query("SELECT u FROM UserEntity u WHERE " +
            "(:adminLevel >= 6 OR " +
            "(:adminLevel >= 2 AND u.jobLevel IN ('0', '1')) OR " +
            "(:adminLevel = 1 AND u.deptCode = :deptCode)) " +
            "ORDER BY u.jobLevel, u.userName")
    List<UserEntity> findManageableUsersByAdminLevel(@Param("adminLevel") int adminLevel,
                                                     @Param("deptCode") String deptCode);
    Optional<UserEntity> findFirstByJobLevelInAndDeptCodeAndRole(List<String> jobLevels, String deptCode, Role role);
    List<UserEntity> findByJobLevelAndRole(String jobLevel, Role role);
    // 비관적 락을 사용한 사용자 조회 (동시성 문제 해결)
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT u FROM UserEntity u WHERE u.userId = :userId")
    Optional<UserEntity> findByUserIdWithLock(@Param("userId") String userId);

    // useFlag = 1 조건이 포함된 새로운 메서드들
    @Cacheable(value = "deptManagerUseFlagCache", key = "#deptCode + '_' + #jobLevel")
    Optional<UserEntity> findFirstByDeptCodeAndJobLevelAndUseFlag(String deptCode, String jobLevel, String useFlag);

    @Cacheable(value = "jobLevelUseFlagCache", key = "#jobLevel")
    Optional<UserEntity> findFirstByJobLevelAndUseFlag(String jobLevel, String useFlag);

    @Cacheable(value = "hrStaffUseFlagCache", key = "#jobLevel + '_' + #deptCode + '_' + #role")
    Optional<UserEntity> findFirstByJobLevelInAndDeptCodeAndRoleAndUseFlag(List<String> jobLevels, String deptCode, Role role, String useFlag);

    Optional<UserEntity> findByPhone(String phone);

    @Query("SELECT DISTINCT u.deptCode FROM UserEntity u WHERE u.deptCode IS NOT NULL AND u.useFlag = '1'")
    List<String> findAllActiveDeptCodes();

    //  부서 + 직급 + 활성 여부로 조회
    List<UserEntity> findByDeptCodeAndJobLevelAndUseFlag(String deptCode, String jobLevel, String useFlag);

    //  직급 + 활성 여부로 조회
    List<UserEntity> findByJobLevelAndUseFlag(String jobLevel, String useFlag);

    //  활성 여부 + 직급 목록으로 조회
    List<UserEntity> findByUseFlagAndJobLevelIn(String useFlag, List<String> jobLevels);

    // deptCode로 시작하는(예: OS, OS01, OS_01 등) 모든 사용자를 활성(useFlag) 기준으로 가져옴
    List<UserEntity> findByDeptCodeStartingWithAndUseFlag(String baseDeptCode, String useFlag);

    // 💡 [NEW] 페이징된 사용자 목록 조회 (활성/비활성 여부 + 검색어 포함)
    @Query("SELECT u FROM UserEntity u WHERE (:showAll = TRUE OR u.useFlag = '1') " +
            "AND (:searchTerm IS NULL OR LOWER(u.userId) LIKE %:searchTerm% OR LOWER(u.userName) LIKE %:searchTerm% OR LOWER(u.deptCode) LIKE %:searchTerm%)")
    Page<UserEntity> findAllUsersWithPaging(
            @Param("showAll") boolean showAll,
            @Param("searchTerm") String searchTerm,
            Pageable pageable
    );

    // 💡 [NEW] 전체 사용자 목록 조회 (통계 계산용)
    List<UserEntity> findAll();

    // ✅ JPA가 자동으로 COUNT 쿼리를 생성합니다. (전체 사용자 수)
    long count();

    // ✅ useFlag='1'인 사용자 수
    long countByUseFlag(String useFlag);
    /**
     * 💡 [NEW] 부서별 페이징된 사용자 목록 조회
     * deptBase로 시작하는 부서 코드를 가진 사용자만 조회
     */
    @Query("SELECT u FROM UserEntity u WHERE " +
            "u.deptCode LIKE CONCAT(:deptBase, '%') AND " +
            "(:showAll = TRUE OR u.useFlag = '1') AND " +
            "(:searchTerm IS NULL OR " +
            "LOWER(u.userId) LIKE %:searchTerm% OR " +
            "LOWER(u.userName) LIKE %:searchTerm% OR " +
            "LOWER(u.deptCode) LIKE %:searchTerm%)")
    Page<UserEntity> findDepartmentUsersWithPaging(
            @Param("deptBase") String deptBase,
            @Param("showAll") boolean showAll,
            @Param("searchTerm") String searchTerm,
            Pageable pageable
    );
}