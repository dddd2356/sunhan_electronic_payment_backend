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
import sunhan.sunhanbackend.dto.response.*;
import sunhan.sunhanbackend.entity.mysql.LeaveApplication;
import sunhan.sunhanbackend.entity.mysql.UserEntity;
import sunhan.sunhanbackend.enums.LeaveApplicationStatus;
import sunhan.sunhanbackend.enums.LeaveType;
import sunhan.sunhanbackend.enums.PermissionType;
import sunhan.sunhanbackend.repository.mysql.DepartmentRepository;
import sunhan.sunhanbackend.repository.mysql.LeaveApplicationRepository;
import sunhan.sunhanbackend.repository.mysql.UserRepository;
import sunhan.sunhanbackend.entity.mysql.Department;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
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
     * ✅ 정렬 기능이 추가된 부서별 통계 조회 (시스템 부서 제외)
     */
    @Transactional(readOnly = true)
    public List<VacationStatisticsResponseDto> getDepartmentStatistics(
            String adminUserId,
            String sortBy,
            String sortOrder
    ) {
        UserEntity admin = userService.getUserInfo(adminUserId);

        int jobLevel = -1;
        try {
            if (admin.getJobLevel() != null) {
                jobLevel = Integer.parseInt(admin.getJobLevel().trim());
            }
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("잘못된 직급 정보입니다.");
        }

        boolean isAdmin = jobLevel == 6;
        boolean hasVacationPermission = permissionService.hasPermission(adminUserId, PermissionType.HR_LEAVE_APPLICATION);

        if (!isAdmin && !hasVacationPermission) {
            throw new AccessDeniedException("통계 조회 권한이 없습니다.");
        }

        List<String> deptCodes = userRepository.findAllActiveDeptCodes();
        Map<String, List<String>> grouped = deptCodes.stream()
                .filter(Objects::nonNull)
                .filter(code -> !"000".equals(code)) // ✅ 시스템 부서 제외
                .collect(Collectors.groupingBy(this::getBaseDeptCode));

        return grouped.keySet().stream()
                .filter(baseCode -> !"000".equals(baseCode)) // ✅ 시스템 부서 제외
                .map(baseCode -> calculateDeptStatisticsForBase(baseCode, sortBy, sortOrder))
                .sorted((a, b) -> a.getDeptCode().compareTo(b.getDeptCode()))
                .collect(Collectors.toList());
    }

    /**
     * baseCode 단위로 실제 사용자들을 조회해 통계를 계산
     * ex) baseCode = "OS"  -> findByDeptCodeStartingWithAndUseFlag("OS", "1") 로 OS, OS01, OS_02 등 모두 포함
     */
    private VacationStatisticsResponseDto calculateDeptStatisticsForBase(
            String baseCode,
            String sortBy,
            String sortOrder
    ) {
        List<UserEntity> deptUsers = userRepository.findByDeptCodeStartingWithAndUseFlag(baseCode, "1");

        if (deptUsers.isEmpty()) {
            String deptName = departmentRepository.findByDeptCode(baseCode)
                    .map(Department::getDeptName)
                    .orElse(baseCode);

            return VacationStatisticsResponseDto.builder()
                    .deptCode(baseCode)
                    .deptName(deptName)
                    .totalEmployees(0)
                    .avgUsageRate(0.0)
                    .totalVacationDays(0.0)
                    .totalUsedDays(0.0)
                    .totalRemainingDays(0.0)
                    .employees(new ArrayList<>())
                    .build();
        }

        // ✅ 정렬 로직 적용
        List<EmployeeVacationDto> employeeStats = deptUsers.stream()
                .map(this::calculateEmployeeVacation)
                .collect(Collectors.toList());

        // ✅ 정렬 적용
        employeeStats = sortEmployees(employeeStats, sortBy, sortOrder);

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
     * ✅ 직원 목록 정렬 로직
     */
    private List<EmployeeVacationDto> sortEmployees(
            List<EmployeeVacationDto> employees,
            String sortBy,
            String sortOrder
    ) {
        if (sortBy == null || sortBy.isEmpty()) {
            sortBy = "usageRate";
        }
        if (sortOrder == null || sortOrder.isEmpty()) {
            sortOrder = "desc";
        }

        Comparator<EmployeeVacationDto> comparator;

        switch (sortBy) {
            case "userName":
                comparator = Comparator.comparing(EmployeeVacationDto::getUserName,
                        Comparator.nullsLast(String::compareTo));
                break;
            case "deptCode":
                comparator = Comparator.comparing(
                        emp -> {
                            String deptCode = emp.getDeptCode();
                            if (deptCode == null) return "";
                            // 부서 이름으로 정렬하려면 departmentNames Map 사용
                            String baseDeptCode = deptCode.replaceAll("\\d+$", "");
                            return baseDeptCode;
                        },
                        Comparator.nullsLast(String::compareTo)
                );
                break;
            case "jobLevel":
                comparator = Comparator.comparing(
                        emp -> {
                            try {
                                return Integer.parseInt(emp.getJobLevel());
                            } catch (NumberFormatException e) {
                                return 999;
                            }
                        }
                );
                break;
            case "totalDays":
                comparator = Comparator.comparing(EmployeeVacationDto::getTotalDays);
                break;
            case "usedDays":
                comparator = Comparator.comparing(EmployeeVacationDto::getUsedDays);
                break;
            case "remainingDays":
                comparator = Comparator.comparing(EmployeeVacationDto::getRemainingDays);
                break;
            case "startDate":
                comparator = Comparator.comparing(
                        e -> e.getStartDate() != null ? e.getStartDate() : LocalDate.MIN
                );
                break;
            case "usageRate":
            default:
                comparator = Comparator.comparing(EmployeeVacationDto::getUsageRate);
                break;
        }

        if ("asc".equalsIgnoreCase(sortOrder)) {
            employees.sort(comparator);
        } else {
            employees.sort(comparator.reversed());
        }

        return employees;
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
                .deptCode(user.getDeptCode())
                .jobLevel(user.getJobLevel())
                .jobType(user.getJobType())
                .startDate(user.getStartDate())
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

    /**
     * ✅ 특정 직원들만 조회
     */
    @Transactional(readOnly = true)
    public List<EmployeeVacationDto> getSpecificEmployeesVacation(
            String adminUserId,
            List<String> userIds
    ) {
        // 권한 확인
        UserEntity admin = userService.getUserInfo(adminUserId);
        int jobLevel = Integer.parseInt(admin.getJobLevel());
        boolean isAdmin = jobLevel == 6;
        boolean hasVacationPermission = permissionService.hasPermission(adminUserId, PermissionType.HR_LEAVE_APPLICATION);

        if (!isAdmin && !hasVacationPermission) {
            throw new AccessDeniedException("통계 조회 권한이 없습니다.");
        }

        // 지정된 사용자들만 조회
        List<UserEntity> users = userRepository.findByUserIdIn(userIds).stream()
                .filter(u -> "1".equals(u.getUseFlag()))
                .collect(Collectors.toList());

        return users.stream()
                .map(this::calculateEmployeeVacation)
                .collect(Collectors.toList());
    }

    /**
     * ✅ 월별 휴가 사용 통계
     */
    @Transactional(readOnly = true)
    public List<MonthlyVacationStatisticsDto> getMonthlyVacationStatistics(
            String adminUserId,
            int startYear,
            int startMonth,
            int endYear,
            int endMonth,
            List<String> userIds // null이면 전체
    ) {
        // 권한 확인
        UserEntity admin = userService.getUserInfo(adminUserId);
        int jobLevel = Integer.parseInt(admin.getJobLevel());
        boolean isAdmin = jobLevel == 6;
        boolean hasVacationPermission = permissionService.hasPermission(adminUserId, PermissionType.HR_LEAVE_APPLICATION);

        if (!isAdmin && !hasVacationPermission) {
            throw new AccessDeniedException("통계 조회 권한이 없습니다.");
        }

        // 조회할 사용자 목록
        List<UserEntity> targetUsers;
        if (userIds != null && !userIds.isEmpty()) {
            targetUsers = userRepository.findByUserIdIn(userIds);
        } else {
            targetUsers = userRepository.findByUseFlag("1");
        }

        LocalDate startDate = LocalDate.of(startYear, startMonth, 1);
        LocalDate endDate = LocalDate.of(endYear, endMonth, 1).plusMonths(1).minusDays(1);

        return targetUsers.stream()
                .map(user -> calculateMonthlyVacation(user, startDate, endDate))
                .collect(Collectors.toList());
    }

    /**
     * ✅ 개별 사용자의 월별 휴가 사용 계산
     */
    private MonthlyVacationStatisticsDto calculateMonthlyVacation(
            UserEntity user,
            LocalDate startDate,
            LocalDate endDate
    ) {
        // 해당 기간의 승인된 휴가원 조회
        List<LeaveApplication> applications = leaveApplicationRepository
                .findByApplicantIdAndStatus(user.getUserId(), LeaveApplicationStatus.APPROVED)
                .stream()
                .filter(app -> app.getLeaveType() == LeaveType.ANNUAL_LEAVE)
                .filter(app -> {
                    LocalDate appStart = app.getStartDate();
                    LocalDate appEnd = app.getEndDate();
                    return !(appEnd.isBefore(startDate) || appStart.isAfter(endDate));
                })
                .collect(Collectors.toList());

        // 월별로 그룹화
        Map<String, Double> monthlyUsage = new HashMap<>();
        LocalDate current = startDate.withDayOfMonth(1);

        while (!current.isAfter(endDate)) {
            String monthKey = current.format(DateTimeFormatter.ofPattern("yyyy-MM"));
            monthlyUsage.put(monthKey, 0.0);
            current = current.plusMonths(1);
        }

        // 휴가 일수를 월별로 계산
        for (LeaveApplication app : applications) {
            LocalDate appStart = app.getStartDate();
            LocalDate appEnd = app.getEndDate();

            LocalDate calcStart = appStart.isBefore(startDate) ? startDate : appStart;
            LocalDate calcEnd = appEnd.isAfter(endDate) ? endDate : appEnd;

            LocalDate currentDay = calcStart;
            while (!currentDay.isAfter(calcEnd)) {
                String monthKey = currentDay.format(DateTimeFormatter.ofPattern("yyyy-MM"));
                monthlyUsage.merge(monthKey, 1.0, Double::sum);
                currentDay = currentDay.plusDays(1);
            }
        }

        double totalUsed = monthlyUsage.values().stream().mapToDouble(Double::doubleValue).sum();
        Double totalDays = user.getTotalVacationDays() != null ? user.getTotalVacationDays() : 15.0;
        Double remaining = totalDays - totalUsed;

        return MonthlyVacationStatisticsDto.builder()
                .userId(user.getUserId())
                .userName(user.getUserName())
                .deptCode(user.getDeptCode())
                .startDate(user.getStartDate())
                .totalDays(totalDays.intValue())
                .monthlyUsage(monthlyUsage)
                .totalUsed(totalUsed)
                .remaining(remaining)
                .build();
    }

    /**
     * ✅ 부서 요약 정보만 조회 (직원 상세 데이터 제외 - 성능 최적화)
     */
    /**
     * ✅ 부서 요약 정보만 조회 (시스템 부서 제외)
     */
    @Transactional(readOnly = true)
    public List<DepartmentSummaryDto> getDepartmentSummaries(String adminUserId) {
        UserEntity admin = userService.getUserInfo(adminUserId);

        int jobLevel = -1;
        try {
            if (admin.getJobLevel() != null) {
                jobLevel = Integer.parseInt(admin.getJobLevel().trim());
            }
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("잘못된 직급 정보입니다.");
        }

        boolean isAdmin = jobLevel == 6;
        boolean hasVacationPermission = permissionService.hasPermission(adminUserId, PermissionType.HR_LEAVE_APPLICATION);

        if (!isAdmin && !hasVacationPermission) {
            throw new AccessDeniedException("통계 조회 권한이 없습니다.");
        }

        List<String> deptCodes = userRepository.findAllActiveDeptCodes();
        Map<String, List<String>> grouped = deptCodes.stream()
                .filter(Objects::nonNull)
                .filter(code -> !code.trim().isEmpty())
                .filter(code -> !"000".equals(code)) // ✅ 시스템 부서(관리자) 제외
                .collect(Collectors.groupingBy(this::getBaseDeptCode));

        return grouped.keySet().stream()
                .filter(baseCode -> baseCode != null && !baseCode.trim().isEmpty())
                .filter(baseCode -> !"000".equals(baseCode)) // ✅ 시스템 부서 제외
                .map(this::calculateDeptSummary)
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(DepartmentSummaryDto::getDeptCode))
                .collect(Collectors.toList());
    }

    /**
     * ✅ 부서 요약 계산 (직원 데이터 제외)
     * 빈 문자열이나 null 부서 코드 필터링
     */
    private DepartmentSummaryDto calculateDeptSummary(String baseCode) {
        // ✅ 빈 문자열이나 null 체크
        if (baseCode == null || baseCode.trim().isEmpty()) {
            return null; // null 반환하여 필터링되도록 함
        }

        List<UserEntity> deptUsers = userRepository.findByDeptCodeStartingWithAndUseFlag(baseCode, "1");

        if (deptUsers.isEmpty()) {
            String deptName = departmentRepository.findByDeptCode(baseCode)
                    .map(Department::getDeptName)
                    .orElse(baseCode);

            return DepartmentSummaryDto.builder()
                    .deptCode(baseCode)
                    .deptName(deptName)
                    .totalEmployees(0)
                    .avgUsageRate(0.0)
                    .build();
        }

        double avgUsageRate = deptUsers.stream()
                .mapToDouble(user -> {
                    Double totalDays = user.getTotalVacationDays() != null ? user.getTotalVacationDays() : 15.0;
                    Double usedDays = user.getUsedVacationDays() != null ? user.getUsedVacationDays() : 0.0;
                    return totalDays > 0 ? (usedDays * 100.0 / totalDays) : 0.0;
                })
                .average()
                .orElse(0.0);

        String deptName = departmentRepository.findByDeptCode(baseCode)
                .map(Department::getDeptName)
                .orElse(baseCode);

        return DepartmentSummaryDto.builder()
                .deptCode(baseCode)
                .deptName(deptName)
                .totalEmployees(deptUsers.size())
                .avgUsageRate(Math.round(avgUsageRate * 100.0) / 100.0)
                .build();
    }

    /**
     * ✅ 특정 부서 상세 정보 조회 (직원별 상세 데이터 포함)
     */
    @Transactional(readOnly = true)
    public VacationStatisticsResponseDto getDepartmentDetail(
            String adminUserId,
            String deptCode,
            String sortBy,
            String sortOrder
    ) {
        // 권한 확인
        UserEntity admin = userService.getUserInfo(adminUserId);
        int jobLevel = Integer.parseInt(admin.getJobLevel());
        boolean isAdmin = jobLevel == 6;
        boolean hasVacationPermission = permissionService.hasPermission(adminUserId, PermissionType.HR_LEAVE_APPLICATION);

        if (!isAdmin && !hasVacationPermission) {
            throw new AccessDeniedException("통계 조회 권한이 없습니다.");
        }

        // ✅ "ALL"인 경우 전체 통계 반환
        if ("ALL".equals(deptCode)) {
            return calculateAllDepartmentsStatistics(sortBy, sortOrder);
        }

        return calculateDeptStatisticsForBase(deptCode, sortBy, sortOrder);
    }

    /**
     * ✅ 전체 부서 통합 통계 계산 (시스템 부서 제외)
     */
    private VacationStatisticsResponseDto calculateAllDepartmentsStatistics(
            String sortBy,
            String sortOrder
    ) {
        // ✅ 시스템 부서(000) 제외하고 모든 활성 사용자 조회
        List<UserEntity> allUsers = userRepository.findByUseFlag("1").stream()
                .filter(user -> !"000".equals(user.getDeptCode())) // 시스템 부서 제외
                .collect(Collectors.toList());

        if (allUsers.isEmpty()) {
            return VacationStatisticsResponseDto.builder()
                    .deptCode("ALL")
                    .deptName("전체")
                    .totalEmployees(0)
                    .avgUsageRate(0.0)
                    .totalVacationDays(0.0)
                    .totalUsedDays(0.0)
                    .totalRemainingDays(0.0)
                    .employees(new ArrayList<>())
                    .build();
        }

        // 직원별 통계 계산
        List<EmployeeVacationDto> employeeStats = allUsers.stream()
                .map(this::calculateEmployeeVacation)
                .collect(Collectors.toList());

        // 정렬 적용
        employeeStats = sortEmployees(employeeStats, sortBy, sortOrder);

        // 합계 계산
        double totalVacationDays = employeeStats.stream()
                .mapToDouble(EmployeeVacationDto::getTotalDays)
                .sum();
        double totalUsedDays = employeeStats.stream()
                .mapToDouble(EmployeeVacationDto::getUsedDays)
                .sum();
        double totalRemainingDays = employeeStats.stream()
                .mapToDouble(EmployeeVacationDto::getRemainingDays)
                .sum();
        double avgUsageRate = employeeStats.stream()
                .mapToDouble(EmployeeVacationDto::getUsageRate)
                .average()
                .orElse(0.0);

        return VacationStatisticsResponseDto.builder()
                .deptCode("ALL")
                .deptName("전체")
                .totalEmployees(allUsers.size())
                .avgUsageRate(Math.round(avgUsageRate * 100.0) / 100.0)
                .totalVacationDays(totalVacationDays)
                .totalUsedDays(totalUsedDays)
                .totalRemainingDays(totalRemainingDays)
                .employees(employeeStats)
                .build();
    }

    public List<EmployeeVacationDto> sortEmployeeList(
            List<EmployeeVacationDto> employees,
            String sortBy,
            String sortOrder
    ) {
        return sortEmployees(employees, sortBy, sortOrder);
    }
}