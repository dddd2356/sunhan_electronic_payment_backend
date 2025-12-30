package sunhan.sunhanbackend.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityNotFoundException;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sunhan.sunhanbackend.dto.response.EmployeeVacationDto;
import sunhan.sunhanbackend.dto.response.VacationHistoryResponseDto;
import sunhan.sunhanbackend.dto.response.VacationStatisticsResponseDto;
import sunhan.sunhanbackend.dto.response.VacationStatusResponseDto;
import sunhan.sunhanbackend.entity.mysql.LeaveApplication;
import sunhan.sunhanbackend.entity.mysql.UserEntity;
import sunhan.sunhanbackend.enums.LeaveApplicationStatus;
import sunhan.sunhanbackend.enums.LeaveType;
import sunhan.sunhanbackend.enums.PermissionType;
import sunhan.sunhanbackend.repository.mysql.DepartmentRepository;
import sunhan.sunhanbackend.repository.mysql.LeaveApplicationRepository;
import sunhan.sunhanbackend.repository.mysql.UserRepository;
import sunhan.sunhanbackend.entity.mysql.Department;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class VacationService {

    private final UserRepository userRepository;
    private final LeaveApplicationRepository leaveApplicationRepository;
    private final UserService userService;
    private final PermissionService permissionService;
    private final DepartmentRepository departmentRepository;

    // 부서 코드에서 baseCode 추출 (예: "OS01" -> "OS", "OS_01" -> "OS")
    private String getBaseDeptCode(String deptCode) {
        if (deptCode == null || deptCode.trim().isEmpty()) return deptCode;
        // 기본 규칙: 끝의 선택적 구분자(_ or -)와 숫자들을 제거
        // 예: OS01 -> OS, OS_01 -> OS, OS-01 -> OS
        return deptCode.replaceAll("[_\\-]?\\d+$", "");
    }

    @PersistenceContext
    private EntityManager entityManager;

    /**
     * ✅ 개선된 휴가 현황 조회 (DB 집계 사용)
     */
    @Transactional(readOnly = true)
    public VacationStatusResponseDto getVacationStatus(String userId) {

        // 🔥 캐시를 우회하고 EntityManager로 직접 조회
        UserEntity user = entityManager.find(UserEntity.class, userId);

        if (user == null) {
            throw new EntityNotFoundException("사용자를 찾을 수 없습니다: " + userId);
        }

        // 🔥 DB에서 최신 데이터 강제 새로고침
        entityManager.refresh(user);

        Double totalDays = user.getTotalVacationDays() != null ? user.getTotalVacationDays() : 15.0;
        Double usedVacationDays = user.getUsedVacationDays() != null ? user.getUsedVacationDays() : 0.0;
        Double remainingDays = totalDays - usedVacationDays;
        String deptName = user.getDepartment() != null ? user.getDepartment().getDeptName() : user.getDeptCode();

        return VacationStatusResponseDto.builder()
                .userId(user.getUserId())
                .userName(user.getUserName())
                .deptName(deptName)
                .totalVacationDays(totalDays)
                .usedVacationDays(usedVacationDays)
                .remainingVacationDays(remainingDays)
                .build();
    }


    /**
     * 사용자의 휴가 사용 내역 조회
     */
    @Transactional(readOnly = true)
    public List<VacationHistoryResponseDto> getVacationHistory(String userId) {
        // 🚀 Use the optimized JOIN FETCH query to get applications and applicants at once.
        List<LeaveApplication> approvedApplications = leaveApplicationRepository
                .findByApplicantIdAndStatusWithApplicant(userId, LeaveApplicationStatus.APPROVED);

        return approvedApplications.stream()
                .map(this::convertToHistoryDto) // No extra queries will be triggered here.
                .collect(Collectors.toList());
    }
    /**
     * 총 휴가일수 설정 (관리자만)
     */
    @Transactional
    public void setTotalVacationDays(String adminUserId, String targetUserId, Double totalDays) {
        // 권한 검증
        if (!userService.canManageUser(adminUserId, targetUserId)) {
            throw new RuntimeException("해당 사용자의 휴가일수를 설정할 권한이 없습니다.");
        }

        UserEntity targetUser = userRepository.findByUserId(targetUserId)
                .orElseThrow(() -> new RuntimeException("대상 사용자를 찾을 수 없습니다: " + targetUserId));

        targetUser.setTotalVacationDays(totalDays);

        // 이 시점에 동시성 충돌 발생 가능
        try {
            userRepository.save(targetUser);
        } catch (ObjectOptimisticLockingFailureException e) {
            // 명확한 메시지를 전달하기 위해 예외를 다시 던짐
            throw new RuntimeException("다른 사용자가 해당 정보를 수정했습니다. 다시 시도해주세요.", e);
        }

        log.info("관리자 {}가 사용자 {}의 총 휴가일수를 {}일로 설정",
                adminUserId, targetUserId, totalDays);
    }

    /**
     * 사용자의 휴가 정보 조회 권한 확인
     */
    public boolean canViewUserVacation(String currentUserId, String targetUserId) {
        // 본인의 휴가 정보는 항상 조회 가능
        if (currentUserId.equals(targetUserId)) {
            return true;
        }

        // 관리 권한이 있는 경우 조회 가능
        return userService.canManageUser(currentUserId, targetUserId);
    }

    /**
     * LeaveApplication을 VacationHistoryResponseDto로 변환
     */
    private VacationHistoryResponseDto convertToHistoryDto(LeaveApplication application) {
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd");

        return VacationHistoryResponseDto.builder()
                .id(application.getId())
                .startDate(application.getStartDate().format(formatter))
                .endDate(application.getEndDate().format(formatter))
                .days(application.getTotalDays() != null ? application.getTotalDays().intValue() : 0)
                .reason(application.getLeaveType().getDisplayName())
                .leaveType(application.getLeaveType().name())
                .status(application.getStatus().toString())
                .createdDate(application.getCreatedAt().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")))
                .build();
    }

    /**
     * 휴가 종류를 한국어로 변환
     */
    private String getLeaveTypeKorean(String leaveType) {
        switch (leaveType) {
            case "ANNUAL_LEAVE": return "연차휴가";
            case "SICK_LEAVE": return "병가";
            case "FAMILY_CARE_LEAVE": return "가족돌봄휴가";
            case "MATERNITY_LEAVE": return "출산휴가";
            case "PATERNITY_LEAVE": return "배우자출산휴가";
            case "SPECIAL_LEAVE": return "특별휴가";
            case "BEREAVEMENT_LEAVE": return "경조휴가";
            default: return leaveType;
        }
    }

    /**
     * 부서별 휴가 통계 조회
     */
    @Transactional(readOnly = true)
    public List<VacationStatisticsResponseDto> getDepartmentStatistics(String adminUserId) {
        // 1. 사용자 조회 (예외는 getUserInfo가 던짐)
        UserEntity admin = userService.getUserInfo(adminUserId);

        // 2. jobLevel 안전 파싱
        int jobLevel = -1;
        try {
            if (admin.getJobLevel() != null) {
                jobLevel = Integer.parseInt(admin.getJobLevel().trim());
            }
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("잘못된 직급 정보입니다.");
        }

        // 3. 관리자 여부 (기존 규칙 유지: jobLevel == 6)
        boolean isAdmin = jobLevel == 6;

        // 4. 휴가원 권한 여부 (PermissionService 사용)
        boolean hasVacationPermission = permissionService.hasPermission(adminUserId, PermissionType.HR_LEAVE_APPLICATION);

        // 5. 최종 권한 검사: 관리자 OR 휴가권한 보유자
        if (!isAdmin && !hasVacationPermission) {
            throw new AccessDeniedException("통계 조회 권한이 없습니다.");
        }

        // 1) 현재 활성화된 모든 deptCode 수집
        List<String> deptCodes = userRepository.findAllActiveDeptCodes();

        // 2) baseCode로 그룹화
        Map<String, List<String>> grouped = deptCodes.stream()
                .filter(Objects::nonNull)
                .collect(Collectors.groupingBy(this::getBaseDeptCode));


        return grouped.keySet().stream()
                .map(this::calculateDeptStatisticsForBase)
                .sorted((a, b) -> a.getDeptCode().compareTo(b.getDeptCode()))
                .collect(Collectors.toList());
    }

    /**
     * baseCode 단위로 실제 사용자들을 조회해 통계를 계산
     * ex) baseCode = "OS"  -> findByDeptCodeStartingWithAndUseFlag("OS", "1") 로 OS, OS01, OS_02 등 모두 포함
     */
    private VacationStatisticsResponseDto calculateDeptStatisticsForBase(String baseCode) {
        // 1) baseCode로 시작하는 모든 활성 사용자 조회
        List<UserEntity> deptUsers = userRepository.findByDeptCodeStartingWithAndUseFlag(baseCode, "1");

        if (deptUsers.isEmpty()) {
            // deptName은 department 테이블에서 baseCode로 찾거나 baseCode 자체 사용
            String deptName = departmentRepository.findByDeptCode(baseCode)
                    .map(Department::getDeptName)
                    .orElse(baseCode);

            return VacationStatisticsResponseDto.builder()
                    .deptCode(baseCode)
                    .deptName(deptName)
                    .totalEmployees(0)
                    .avgUsageRate(0.0)
                    // DTO가 Double을 기대하면 0.0으로 넘겨야 함
                    .totalVacationDays(0.0)
                    .totalUsedDays(0.0)
                    .totalRemainingDays(0.0)
                    .employees(new ArrayList<>())
                    .build();
        }

        // 기존 calculateDeptStatistics 내용과 동일한 집계(직원별 계산 재사용)
        List<EmployeeVacationDto> employeeStats = deptUsers.stream()
                .map(this::calculateEmployeeVacation)
                .sorted((a, b) -> b.getUsageRate().compareTo(a.getUsageRate()))
                .collect(Collectors.toList());

        // 합계들을 double로 계산해서 DTO의 Double 필드에 맞춤
        double totalVacationDays = employeeStats.stream().mapToDouble(EmployeeVacationDto::getTotalDays).sum();
        double totalUsedDays = employeeStats.stream().mapToDouble(EmployeeVacationDto::getUsedDays).sum();
        double totalRemainingDays = employeeStats.stream().mapToDouble(EmployeeVacationDto::getRemainingDays).sum();
        double avgUsageRate = employeeStats.stream().mapToDouble(EmployeeVacationDto::getUsageRate).average().orElse(0.0);

        String deptName = departmentRepository.findByDeptCode(baseCode)
                .map(Department::getDeptName)
                .orElse(baseCode);

        return VacationStatisticsResponseDto.builder()
                .deptCode(baseCode)
                .deptName(deptName)
                .totalEmployees(deptUsers.size())
                .avgUsageRate(Math.round(avgUsageRate * 100.0) / 100.0)
                .totalVacationDays(totalVacationDays)
                .totalUsedDays(totalUsedDays)
                .totalRemainingDays(totalRemainingDays)
                .employees(employeeStats)
                .build();
    }

    /**
     * 개별 직원의 휴가 정보 계산
     */
    private EmployeeVacationDto calculateEmployeeVacation(UserEntity user) {
        Double totalDays = user.getTotalVacationDays() != null ? user.getTotalVacationDays() : 15.0;
        Double usedDays = user.getUsedVacationDays() != null ? user.getUsedVacationDays() : 0.0;
        Double remaining = totalDays - usedDays;
        double usageRate = totalDays > 0 ? (usedDays * 100.0 / totalDays) : 0.0;

        return EmployeeVacationDto.builder()
                .userId(user.getUserId())
                .userName(user.getUserName())
                .jobLevel(user.getJobLevel())
                .jobType(user.getJobType())
                .totalDays(totalDays.intValue())
                .usedDays(usedDays.intValue())
                .remainingDays(remaining.intValue())
                .usageRate(Math.round(usageRate * 100.0) / 100.0)
                .build();
    }

    /**
     * ✅ 여러 사용자 휴가 현황 일괄 조회 (부서 통계용)
     * N+1 문제 완전 해결
     */
    @Transactional(readOnly = true)
    public List<VacationStatusResponseDto> getVacationStatusBatch(List<String> userIds) {
        List<UserEntity> users = userRepository.findAllById(userIds);

        return users.stream()
                .map(user -> {
                    Double totalVacationDays = user.getTotalVacationDays() != null ? user.getTotalVacationDays() : 15.0;
                    Double usedVacationDays = user.getUsedVacationDays() != null ? user.getUsedVacationDays() : 0.0;
                    Double remainingVacationDays = totalVacationDays - usedVacationDays;

                    return VacationStatusResponseDto.builder()
                            .userId(user.getUserId())
                            .userName(user.getUserName())

                            .totalVacationDays(totalVacationDays)
                            .usedVacationDays(usedVacationDays)
                            .remainingVacationDays(remainingVacationDays)
                            .build();
                })
                .collect(Collectors.toList());
    }
}