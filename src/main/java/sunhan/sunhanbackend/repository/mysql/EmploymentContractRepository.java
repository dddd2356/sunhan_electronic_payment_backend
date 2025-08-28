package sunhan.sunhanbackend.repository.mysql;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import sunhan.sunhanbackend.entity.mysql.EmploymentContract;
import sunhan.sunhanbackend.enums.ContractStatus;
import sunhan.sunhanbackend.enums.ContractType;

import java.util.List;
import java.util.Optional;
import java.util.Set;

@Repository
public interface EmploymentContractRepository extends JpaRepository<EmploymentContract, Long> {
    /**
     * 기본 엔티티 그래프 이름은 "EmploymentContract.withUsers" 로 가정합니다.
     * (EmploymentContract 엔티티에 @NamedEntityGraph(name = "EmploymentContract.withUsers", attributeNodes = {...}) 가 정의되어 있어야 합니다.)
     */
    @EntityGraph(attributePaths = {"creator", "employee"})
    Page<EmploymentContract> findByCreator_UserIdOrEmployee_UserIdAndStatusIn(
            String creatorUserId, String employeeUserId, Set<ContractStatus> statuses, Pageable pageable);
    @EntityGraph("EmploymentContract.withUsers")
    @Query("SELECT ec FROM EmploymentContract ec " +
            "WHERE ec.status = :status " +
            "AND (ec.employee.userId = :userId OR ec.creator.userId = :userId)")
    List<EmploymentContract> findByUserIdAndStatusWithUsers(@Param("userId") String userId,
                                                            @Param("status") ContractStatus status);
    // 페이징 가능한 전체 조회
    @EntityGraph("EmploymentContract.withUsers")
    @Query("SELECT ec FROM EmploymentContract ec")
    Page<EmploymentContract> findAllWithUsers(Pageable pageable);

    // contractType 별 조회
    @EntityGraph("EmploymentContract.withUsers")
    @Query("SELECT ec FROM EmploymentContract ec WHERE ec.contractType = :contractType")
    List<EmploymentContract> findByContractTypeWithUsers(@Param("contractType") ContractType contractType);

    @EntityGraph("EmploymentContract.withUsers")
    @Query("SELECT ec FROM EmploymentContract ec WHERE ec.contractType = :contractType")
    Page<EmploymentContract> findByContractTypeWithUsers(@Param("contractType") ContractType contractType, Pageable pageable);

    // employee.userId + contractType 으로 조회 (Service에서 사용)
    @EntityGraph("EmploymentContract.withUsers")
    @Query("SELECT ec FROM EmploymentContract ec WHERE ec.employee.userId = :employeeId AND ec.contractType = :contractType")
    List<EmploymentContract> findByEmployeeIdAndContractTypeWithUsers(@Param("employeeId") String employeeId,
                                                                      @Param("contractType") ContractType contractType);

    // 특정 userId(creator 또는 employee) 관련 모든 계약서 조회
    @EntityGraph("EmploymentContract.withUsers")
    @Query("SELECT ec FROM EmploymentContract ec WHERE ec.creator.userId = :userId OR ec.employee.userId = :userId")
    List<EmploymentContract> findContractsByCreatorOrEmployeeWithUsers(@Param("userId") String userId);

    // status 별로 creator/employee 를 함께 가져오기 (예: SENT_TO_EMPLOYEE)
    @EntityGraph("EmploymentContract.withUsers")
    @Query("SELECT ec FROM EmploymentContract ec WHERE ec.status = :status")
    List<EmploymentContract> findByStatusWithUsers(@Param("status") ContractStatus status);

    /**
     * employee.userId 와 상태로 조회 (N+1 문제 해결)
     * @EntityGraph를 사용하여 연관된 creator와 employee 엔티티를 함께 조회합니다.
     */
    @EntityGraph(attributePaths = {"creator", "employee"})
    List<EmploymentContract> findByEmployee_UserIdAndStatus(String employeeId, ContractStatus status);

    // 단건 조회 시 엔티티 그래프 적용 (필요 시 사용)
    @EntityGraph("EmploymentContract.withUsers")
    Optional<EmploymentContract> findWithUsersById(Long id);
}