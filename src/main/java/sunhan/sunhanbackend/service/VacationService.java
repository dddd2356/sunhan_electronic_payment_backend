package sunhan.sunhanbackend.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityNotFoundException;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.Hibernate;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sunhan.sunhanbackend.dto.response.*;
import sunhan.sunhanbackend.entity.mysql.*;
import sunhan.sunhanbackend.enums.HalfDayType;
import sunhan.sunhanbackend.enums.LeaveApplicationStatus;
import sunhan.sunhanbackend.enums.LeaveType;
import sunhan.sunhanbackend.enums.PermissionType;
import sunhan.sunhanbackend.repository.mysql.DepartmentRepository;
import sunhan.sunhanbackend.repository.mysql.LeaveApplicationRepository;
import sunhan.sunhanbackend.repository.mysql.UserAnnualVacationHistoryRepository;
import sunhan.sunhanbackend.repository.mysql.UserRepository;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
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
    private final UserAnnualVacationHistoryRepository vacationHistoryRepository;
    private final VacationYearService vacationYearService;

    // 부서 코드에서 baseCode 추출 (예: "OS01" -> "OS", "OS_01" -> "OS")
    private String getBaseDeptCode(String deptCode) {
        if (deptCode == null || deptCode.trim().isEmpty()) return deptCode;
        // 기본 규칙: 끝의 선택적 구분자(_ or -)와 숫자들을 제거
        // 예: OS01 -> OS, OS_01 -> OS, OS-01 -> OS
        return deptCode.replaceAll("[_\\-]?\\d+$", "");
    }

    /**
     * ✅ 연차 차감
     */
    @Transactional
    public void deductVacationDays(String userId, Double days, LocalDate startDate) {
        int year = startDate.getYear();
        int month = startDate.getMonthValue();

        UserAnnualVacationHistory history = vacationHistoryRepository
                .findByUserIdAndYear(userId, year)
                .orElseGet(() -> vacationYearService.initializeUserYearVacation(userId, year));

        if (month <= 2) {
            double remainingCarryover = history.getCarryoverDays() - history.getUsedCarryoverDays();
            if (remainingCarryover >= days) {
                history.setUsedCarryoverDays(history.getUsedCarryoverDays() + days);
            } else {
                history.setUsedCarryoverDays(history.getCarryoverDays());
                history.setUsedRegularDays(history.getUsedRegularDays() + (days - remainingCarryover));
            }
        } else {
            history.setUsedRegularDays(history.getUsedRegularDays() + days);
        }

        if (history.getRemainingDays() < 0) {
            throw new IllegalStateException(
                    String.format("%d년 연차가 부족합니다. (필요: %.1f일, 잔여: %.1f일)",
                            year, days, history.getRemainingDays())
            );
        }

        vacationHistoryRepository.save(history);

        log.info("연차 차감 완료: userId={}, year={}, days={}, remaining={}",
                userId, year, days, history.getRemainingDays());
    }

    /**
     * ✅ 연차 복구
     */
    @Transactional
    public void restoreVacationDays(String userId, Double days, LocalDate startDate) {
        int year = startDate.getYear();
        int month = startDate.getMonthValue();

        UserAnnualVacationHistory history = vacationHistoryRepository
                .findByUserIdAndYear(userId, year)
                .orElseThrow(() -> new EntityNotFoundException(
                        String.format("%d년 휴가 이력을 찾을 수 없습니다.", year)));

        if (month <= 2) {
            double usedRegular = history.getUsedRegularDays();
            if (usedRegular >= days) {
                history.setUsedRegularDays(usedRegular - days);
            } else {
                history.setUsedRegularDays(0.0);
                history.setUsedCarryoverDays(
                        Math.max(0.0, history.getUsedCarryoverDays() - (days - usedRegular))
                );
            }
        } else {
            history.setUsedRegularDays(Math.max(0.0, history.getUsedRegularDays() - days));
        }

        vacationHistoryRepository.save(history);

        log.info("연차 복구 완료: userId={}, year={}, days={}, remaining={}",
                userId, year, days, history.getRemainingDays());
    }

    /**
     * ✅ 특정 연도의 휴가 현황 조회
     */
    @Transactional(readOnly = true)
    public VacationStatusResponseDto getVacationStatus(String userId, Integer year) {
        // ✅ final 변수로 선언
        final Integer targetYear = (year != null) ? year : LocalDate.now().getYear();

        UserEntity user = userRepository.findByIdWithDepartment(userId)
                .orElseThrow(() -> new EntityNotFoundException("사용자를 찾을 수 없습니다: " + userId));

        UserAnnualVacationHistory history = vacationHistoryRepository
                .findByUserIdAndYear(userId, targetYear)
                .orElse(null);

        if (history == null) {
            log.info("사용자 {}의 {}년 휴가 데이터 자동 생성 시도", userId, targetYear);
            try {
                history = vacationYearService.initializeUserYearVacation(userId, targetYear);
            } catch (Exception e) {  // 모든 예외 catch (중복 키 포함)
                log.warn("연차 초기화 실패 → 기본값 사용: userId={}, year={}", userId, targetYear, e);
                // 더 이상 DB 작업 하지 않고 기본값 반환
                history = UserAnnualVacationHistory.builder()
                        .userId(userId)
                        .year(targetYear)
                        .carryoverDays(0.0)
                        .regularDays(0.0)
                        .usedCarryoverDays(0.0)
                        .usedRegularDays(0.0)
                        .build();
            }
        }

        String deptName = getDepartmentName(user);

        return VacationStatusResponseDto.builder()
                .userId(user.getUserId())
                .userName(user.getUserName())
                .deptName(deptName)
                .year(targetYear)
                .annualCarryoverDays(history.getCarryoverDays())
                .annualRegularDays(history.getRegularDays())
                .annualTotalDays(history.getTotalDays())
                .usedCarryoverDays(history.getUsedCarryoverDays())
                .usedRegularDays(history.getUsedRegularDays())
                .annualUsedDays(history.getUsedDays())
                .annualRemainingDays(history.getRemainingDays())
                .totalVacationDays(history.getTotalDays())
                .usedVacationDays(history.getUsedDays())
                .remainingVacationDays(history.getRemainingDays())
                .build();
    }

    /**
     * ✅ 연도 범위 조회
     */
    @Transactional(readOnly = true)
    public List<VacationStatusResponseDto> getVacationStatusByYearRange(
            String userId,
            Integer startYear,
            Integer endYear
    ) {
        UserEntity user = userRepository.findById(userId)
                .orElseThrow(() -> new EntityNotFoundException("사용자를 찾을 수 없습니다: " + userId));

        String deptName = getDepartmentName(user);

        List<UserAnnualVacationHistory> histories = vacationHistoryRepository
                .findByUserIdAndYearBetween(userId, startYear, endYear);

        Set<Integer> existingYears = histories.stream()
                .map(UserAnnualVacationHistory::getYear)
                .collect(Collectors.toSet());

        for (int year = startYear; year <= endYear; year++) {
            if (!existingYears.contains(year)) {
                UserAnnualVacationHistory newHistory =
                        vacationYearService.initializeUserYearVacation(userId, year);
                histories.add(newHistory);
            }
        }

        histories.sort(Comparator.comparing(UserAnnualVacationHistory::getYear));

        return histories.stream()
                .map(history -> VacationStatusResponseDto.builder()
                        .userId(user.getUserId())
                        .userName(user.getUserName())
                        .deptName(deptName)
                        .year(history.getYear())
                        .annualCarryoverDays(history.getCarryoverDays())
                        .annualRegularDays(history.getRegularDays())
                        .annualTotalDays(history.getTotalDays())
                        .usedCarryoverDays(history.getUsedCarryoverDays())
                        .usedRegularDays(history.getUsedRegularDays())
                        .annualUsedDays(history.getUsedDays())
                        .annualRemainingDays(history.getRemainingDays())
                        .totalVacationDays(history.getTotalDays())
                        .usedVacationDays(history.getUsedDays())
                        .remainingVacationDays(history.getRemainingDays())
                        .build())
                .collect(Collectors.toList());
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
            case "ANNUAL_LEAVE":
                return "연차휴가";
            case "SICK_LEAVE":
                return "병가";
            case "FAMILY_CARE_LEAVE":
                return "가족돌봄휴가";
            case "MATERNITY_LEAVE":
                return "출산휴가";
            case "PATERNITY_LEAVE":
                return "배우자출산휴가";
            case "SPECIAL_LEAVE":
                return "특별휴가";
            case "BEREAVEMENT_LEAVE":
                return "경조휴가";
            default:
                return leaveType;
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

    // ✅ 부서별 통계 메서드도 수정 필요 (현재 연도 기준)
    private EmployeeVacationDto calculateEmployeeVacation(UserEntity user) {
        int currentYear = LocalDate.now().getYear();

        UserAnnualVacationHistory history = vacationHistoryRepository
                .findByUserIdAndYear(user.getUserId(), currentYear)
                .orElseGet(() -> vacationYearService.initializeUserYearVacation(user.getUserId(), currentYear));

        double annualUsageRate = history.getTotalDays() > 0
                ? (history.getUsedDays() * 100.0 / history.getTotalDays())
                : 0.0;

        return EmployeeVacationDto.builder()
                .userId(user.getUserId())
                .userName(user.getUserName())
                .deptCode(user.getDeptCode())
                .jobLevel(user.getJobLevel())
                .jobType(user.getJobType())
                .startDate(user.getStartDate())
                // ✅ 수정: .intValue() 제거
                .annualCarryover(history.getCarryoverDays())
                .annualRegular(history.getRegularDays())
                .annualTotal(history.getTotalDays())
                .annualUsed(history.getUsedDays())
                .annualRemaining(history.getRemainingDays())
                .annualUsageRate(Math.round(annualUsageRate * 100.0) / 100.0)
                // ✅ 하위 호환용도 수정
                .totalDays(history.getTotalDays())
                .usedDays(history.getUsedDays())
                .remainingDays(history.getRemainingDays())
                .usageRate(Math.round(annualUsageRate * 100.0) / 100.0)
                .build();
    }

    /**
     * ✅ 여러 사용자 휴가 현황 일괄 조회 (부서 통계용)
     * N+1 문제 완전 해결
     */
    /**
     * ✅ 여러 사용자 휴가 현황 일괄 조회 (부서 통계용)
     */
    @Transactional(readOnly = true)
    public List<VacationStatusResponseDto> getVacationStatusBatch(List<String> userIds) {
        int currentYear = LocalDate.now().getYear();

        List<UserEntity> users = userRepository.findAllById(userIds);

        // ✅ 해당 사용자들의 연차 이력 일괄 조회
        List<UserAnnualVacationHistory> histories = vacationHistoryRepository
                .findByUserIdsAndYear(userIds, currentYear);

        // ✅ userId를 키로 하는 Map 생성
        Map<String, UserAnnualVacationHistory> historyMap = histories.stream()
                .collect(Collectors.toMap(
                        UserAnnualVacationHistory::getUserId,
                        Function.identity()
                ));

        return users.stream()
                .map(user -> {
                    // ✅ 해당 사용자의 연차 이력 조회 (없으면 기본값)
                    UserAnnualVacationHistory history = historyMap.computeIfAbsent(
                            user.getUserId(),
                            userId -> vacationYearService.initializeUserYearVacation(userId, currentYear)
                    );

                    String deptName = getDepartmentName(user);

                    return VacationStatusResponseDto.builder()
                            .userId(user.getUserId())
                            .userName(user.getUserName())
                            .deptName(deptName)
                            .year(currentYear)
                            .annualCarryoverDays(history.getCarryoverDays())
                            .annualRegularDays(history.getRegularDays())
                            .annualTotalDays(history.getTotalDays())
                            .usedCarryoverDays(history.getUsedCarryoverDays())
                            .usedRegularDays(history.getUsedRegularDays())
                            .annualUsedDays(history.getUsedDays())
                            .annualRemainingDays(history.getRemainingDays())
                            .totalVacationDays(history.getTotalDays())
                            .usedVacationDays(history.getUsedDays())
                            .remainingVacationDays(history.getRemainingDays())
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

        // ✅ 수정: UserAnnualVacationHistory에서 조회
        int currentYear = LocalDate.now().getYear();
        UserAnnualVacationHistory history = vacationHistoryRepository
                .findByUserIdAndYear(user.getUserId(), currentYear)
                .orElseGet(() -> vacationYearService.initializeUserYearVacation(user.getUserId(), currentYear));

        Double totalDays = history.getTotalDays();
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
     */
    private DepartmentSummaryDto calculateDeptSummary(String baseCode) {
        if (baseCode == null || baseCode.trim().isEmpty()) {
            return null;
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

        // ✅ 현재 연도 기준으로 계산
        int currentYear = LocalDate.now().getYear();
        List<String> userIds = deptUsers.stream()
                .map(UserEntity::getUserId)
                .collect(Collectors.toList());

        // ✅ 해당 부서 사용자들의 연차 이력 일괄 조회
        List<UserAnnualVacationHistory> histories = vacationHistoryRepository
                .findByUserIdsAndYear(userIds, currentYear);

        Map<String, UserAnnualVacationHistory> historyMap = histories.stream()
                .collect(Collectors.toMap(
                        UserAnnualVacationHistory::getUserId,
                        Function.identity()
                ));

        double avgUsageRate = deptUsers.stream()
                .mapToDouble(user -> {
                    UserAnnualVacationHistory history = historyMap.computeIfAbsent(
                            user.getUserId(),
                            userId -> vacationYearService.initializeUserYearVacation(userId, currentYear)
                    );

                    Double totalDays = history.getTotalDays();
                    Double usedDays = history.getUsedDays();
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

    /**
     * ✅ 연차 설정
     */
    @Transactional
    @CacheEvict(value = "userCache", key = "#targetUserId")
    public void setVacationDetails(String adminUserId, String targetUserId, Integer year, VacationDetailsDto dto) {
        // ✅ HR_LEAVE_APPLICATION 권한 확인
        Set<PermissionType> adminPermissions = permissionService.getAllUserPermissions(adminUserId);

        if (!adminPermissions.contains(PermissionType.HR_LEAVE_APPLICATION)) {
            throw new AccessDeniedException("연차 설정 권한이 없습니다. HR_LEAVE_APPLICATION 권한이 필요합니다.");
        }

        // ✅ final 변수로 선언
        final Integer targetYear = (year != null) ? year : LocalDate.now().getYear();

        UserAnnualVacationHistory history = vacationHistoryRepository
                .findByUserIdAndYear(targetUserId, targetYear)
                .orElseGet(() -> vacationYearService.initializeUserYearVacation(targetUserId, targetYear));

        if (dto.getAnnualCarryoverDays() != null) {
            history.setCarryoverDays(dto.getAnnualCarryoverDays());
        }
        if (dto.getAnnualRegularDays() != null) {
            history.setRegularDays(dto.getAnnualRegularDays());
        }

        vacationHistoryRepository.save(history);

        log.info("관리자 {}가 사용자 {}의 {}년 연차일수 설정 완료 (이월:{}, 정상:{})",
                adminUserId, targetUserId, targetYear,
                history.getCarryoverDays(), history.getRegularDays());
    }

    @Transactional
    public List<VacationLedgerDto> getVacationLedger(
            String adminUserId,
            String deptCode,
            String leaveTypeFilter,  // "ANNUAL" or "SPECIAL"
            int year
    ) {
        UserEntity admin = userService.getUserInfo(adminUserId);
        int jobLevel = Integer.parseInt(admin.getJobLevel());
        boolean isAdmin = jobLevel == 6;
        boolean hasPermission = permissionService.hasPermission(adminUserId, PermissionType.HR_LEAVE_APPLICATION);

        if (!isAdmin && !hasPermission) {
            throw new AccessDeniedException("관리대장 조회 권한이 없습니다.");
        }

        // ✅ 부서별 필터링
        List<UserEntity> users;
        if ("ALL".equals(deptCode)) {
            users = userRepository.findByUseFlag("1").stream()
                    .filter(u -> !"000".equals(u.getDeptCode()))
                    .collect(Collectors.toList());
        } else {
            users = userRepository.findByDeptCodeStartingWithAndUseFlag(deptCode, "1");
        }

        List<VacationLedgerDto> ledger = new ArrayList<>();
        int rowNumber = 1;

        for (UserEntity user : users) {
            try {
                // ✅ 연차 행
                VacationLedgerDto annualLedger = buildLedgerEntry(
                        user, rowNumber++, "연차", LeaveType.ANNUAL_LEAVE, year
                );
                ledger.add(annualLedger);

                // ✅ 경조/특별 행
                VacationLedgerDto specialLedger = buildLedgerEntry(
                        user, rowNumber++, "경조/특별", null, year
                );
                ledger.add(specialLedger);
            } catch (Exception e) {
                log.error("사용자 관리대장 생성 실패: userId={}", user.getUserId(), e);
            }
        }

        return ledger;
    }

    // ✅ 특정 직원들로 관리대장 조회
    @Transactional
    public List<VacationLedgerDto> getVacationLedgerByUsers(
            String adminUserId,
            List<String> userIds,
            int year
    ) {
        UserEntity admin = userService.getUserInfo(adminUserId);
        int jobLevel = Integer.parseInt(admin.getJobLevel());
        boolean isAdmin = jobLevel == 6;
        boolean hasPermission = permissionService.hasPermission(adminUserId, PermissionType.HR_LEAVE_APPLICATION);

        if (!isAdmin && !hasPermission) {
            throw new AccessDeniedException("관리대장 조회 권한이 없습니다.");
        }

        // ✅ 특정 사용자들만 조회
        List<UserEntity> users = userRepository.findAllById(userIds).stream()
                .filter(u -> "1".equals(u.getUseFlag()))
                .collect(Collectors.toList());

        List<VacationLedgerDto> ledger = new ArrayList<>();
        int rowNumber = 1;

        for (UserEntity user : users) {
            try {
                VacationLedgerDto annualLedger = buildLedgerEntry(
                        user, rowNumber++, "연차", LeaveType.ANNUAL_LEAVE, year
                );
                ledger.add(annualLedger);

                VacationLedgerDto specialLedger = buildLedgerEntry(
                        user, rowNumber++, "경조/특별", null, year
                );
                ledger.add(specialLedger);
            } catch (Exception e) {
                log.error("사용자 관리대장 생성 실패: userId={}", user.getUserId(), e);
            }
        }

        return ledger;
    }

    /**
     * 사용자별 관리대장 엔트리 생성
     */
    private VacationLedgerDto buildLedgerEntry(
            UserEntity user,
            int rowNumber,
            String leaveTypeName,
            LeaveType leaveType,
            int year
    ) {
        VacationLedgerDto dto = new VacationLedgerDto();
        dto.setRowNumber(rowNumber);
        dto.setUserId(user.getUserId());
        dto.setYear(year);

        // ✅ 부서명 안전하게 조회
        String deptName = getDepartmentName(user);
        dto.setDeptName(deptName);
        dto.setUserName(user.getUserName());
        dto.setStartDate(user.getStartDate() != null ? user.getStartDate().toString() : "");
        dto.setLeaveType(leaveTypeName);

        // ✅ 연차인 경우만 이월/정상 일수 설정
        if (leaveType == LeaveType.ANNUAL_LEAVE) {
            UserAnnualVacationHistory history = vacationHistoryRepository
                    .findByUserIdAndYear(user.getUserId(), year)
                    .orElseGet(() -> vacationYearService.initializeUserYearVacation(user.getUserId(), year));

            dto.setCarryoverDays(history.getCarryoverDays());
            dto.setRegularDays(history.getRegularDays());
        } else {
            dto.setCarryoverDays(null);
            dto.setRegularDays(null);
        }

        // ✅ 해당 사용자의 휴가 신청 조회
        List<LeaveApplication> applications;
        if (leaveType == LeaveType.ANNUAL_LEAVE) {
            applications = leaveApplicationRepository.findByApplicantIdAndLeaveTypeAndYear(
                    user.getUserId(), leaveType, year);
        } else {
            applications = leaveApplicationRepository.findByApplicantIdAndLeaveTypeInAndYear(
                    user.getUserId(),
                    Arrays.asList(
                            LeaveType.FAMILY_EVENT_LEAVE,
                            LeaveType.SPECIAL_LEAVE,
                            LeaveType.SICK_LEAVE,
                            LeaveType.MENSTRUAL_LEAVE,
                            LeaveType.MATERNITY_LEAVE,
                            LeaveType.MISCARRIAGE_LEAVE
                    ),
                    year
            );
        }

        // ✅ 월별 사용 내역 계산
        Map<Integer, VacationLedgerDto.MonthlyUsage> monthlyUsage = new HashMap<>();
        double totalUsed = 0.0;

        for (LeaveApplication app : applications) {
            try {
                Hibernate.initialize(app.getDays());

                if (app.getDays() == null || app.getDays().isEmpty()) {
                    createLeaveApplicationDays(app);
                    app = leaveApplicationRepository.findById(app.getId()).orElse(app);
                    Hibernate.initialize(app.getDays());
                }

                if (app.getDays() != null && !app.getDays().isEmpty()) {
                    for (LeaveApplicationDay day : app.getDays()) {
                        int month = day.getDate().getMonthValue();

                        monthlyUsage.putIfAbsent(month, new VacationLedgerDto.MonthlyUsage());
                        VacationLedgerDto.MonthlyUsage usage = monthlyUsage.get(month);

                        VacationLedgerDto.DailyDetail detail = new VacationLedgerDto.DailyDetail();
                        detail.setDate(day.getDate().toString());
                        detail.setHalfDayType(day.getHalfDayType());
                        detail.setDays(day.getDays());

                        usage.getDetails().add(detail);
                        usage.setMonthTotal(usage.getMonthTotal() + day.getDays());

                        totalUsed += day.getDays();
                    }
                }
            } catch (Exception e) {
                log.error("휴가 신청 처리 실패: appId={}", app.getId(), e);
            }
        }

        // 각 월별 사용일을 날짜 순으로 정렬
        for (VacationLedgerDto.MonthlyUsage usage : monthlyUsage.values()) {
            usage.getDetails().sort(Comparator.comparing(detail ->
                    LocalDate.parse(detail.getDate())
            ));
        }

        dto.setMonthlyUsage(monthlyUsage);
        dto.setTotalUsed(totalUsed);

        // ✅ 남은 일수 계산 (연차만)
        if (leaveType == LeaveType.ANNUAL_LEAVE) {
            UserAnnualVacationHistory history = vacationHistoryRepository
                    .findByUserIdAndYear(user.getUserId(), year)
                    .orElseGet(() -> vacationYearService.initializeUserYearVacation(user.getUserId(), year));

            double total = history.getTotalDays();
            dto.setRemaining(total - totalUsed);
        } else {
            dto.setRemaining(null);
        }

        dto.setRemarks("");

        return dto;
    }

    /**
     * LeaveApplication에서 flexiblePeriods를 기반으로 LeaveApplicationDay 생성
     */
    private void createLeaveApplicationDays(LeaveApplication app) {
        try {
            String formDataJson = app.getFormDataJson();
            if (formDataJson == null || formDataJson.isEmpty()) {
                log.warn("formDataJson이 없음: applicationId={}", app.getId());
                return;
            }

            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(formDataJson);

            // ✅ 기존 컬렉션을 clear하고 재사용 (새로 할당하지 않음)
            List<LeaveApplicationDay> days = app.getDays();
            if (days == null) {
                days = new ArrayList<>();
            } else {
                days.clear(); // ✅ 기존 리스트 비우기
            }

            // flexiblePeriods 처리
            JsonNode periods = root.get("flexiblePeriods");
            if (periods != null && periods.isArray()) {
                for (JsonNode period : periods) {
                    JsonNode startDateNode = period.get("startDate");
                    JsonNode endDateNode = period.get("endDate");
                    JsonNode halfDayOptionNode = period.get("halfDayOption");

                    if (startDateNode == null || endDateNode == null || halfDayOptionNode == null) {
                        log.warn("flexiblePeriod 정보가 불완전함: applicationId={}", app.getId());
                        continue;
                    }

                    String startDateStr = startDateNode.asText();
                    String endDateStr = endDateNode.asText();
                    String halfDayOption = halfDayOptionNode.asText();

                    if (startDateStr.isEmpty() || endDateStr.isEmpty()) {
                        continue;
                    }

                    LocalDate startDate = LocalDate.parse(startDateStr);
                    LocalDate endDate = LocalDate.parse(endDateStr);

                    HalfDayType halfDayType;
                    switch (halfDayOption) {
                        case "morning":
                            halfDayType = HalfDayType.MORNING;
                            break;
                        case "afternoon":
                            halfDayType = HalfDayType.AFTERNOON;
                            break;
                        default:
                            halfDayType = HalfDayType.ALL_DAY;
                    }

                    LocalDate currentDate = startDate;
                    while (!currentDate.isAfter(endDate)) {
                        LeaveApplicationDay day = new LeaveApplicationDay(
                                currentDate,
                                halfDayType,
                                halfDayType.getDayValue()
                        );
                        day.setLeaveApplication(app);
                        days.add(day); // ✅ 기존 리스트에 추가
                        currentDate = currentDate.plusDays(1);
                    }
                }
            }

            // consecutivePeriod 처리
            JsonNode consecutive = root.get("consecutivePeriod");
            if (consecutive != null) {
                JsonNode startDateNode = consecutive.get("startDate");
                JsonNode endDateNode = consecutive.get("endDate");

                if (startDateNode != null && endDateNode != null) {
                    String startDateStr = startDateNode.asText();
                    String endDateStr = endDateNode.asText();

                    if (!startDateStr.isEmpty() && !endDateStr.isEmpty()) {
                        LocalDate startDate = LocalDate.parse(startDateStr);
                        LocalDate endDate = LocalDate.parse(endDateStr);

                        LocalDate currentDate = startDate;
                        while (!currentDate.isAfter(endDate)) {
                            LeaveApplicationDay day = new LeaveApplicationDay(
                                    currentDate,
                                    HalfDayType.ALL_DAY,
                                    1.0
                            );
                            day.setLeaveApplication(app);
                            days.add(day); // ✅ 기존 리스트에 추가
                            currentDate = currentDate.plusDays(1);
                        }
                    }
                }
            }

            // ✅ 새로 할당하지 않고 기존 리스트 사용
            if (app.getDays() == null) {
                app.setDays(days); // ✅ 최초 할당만
            }

            // ✅ saveAll 대신 save (cascade로 자동 저장)
            if (!days.isEmpty()) {
                leaveApplicationRepository.save(app);
                log.info("LeaveApplicationDay 생성 완료: applicationId={}, count={}", app.getId(), days.size());
            }

        } catch (Exception e) {
            log.error("LeaveApplicationDay 생성 실패: applicationId={}", app.getId(), e);
        }
    }

    /**
     * ✅ 부서명 조회 헬퍼 메서드
     */
    private String getDepartmentName(UserEntity user) {
        if (user.getDeptCode() == null || user.getDeptCode().isEmpty()) {
            return "미설정";
        }

        String baseDeptCode = getBaseDeptCode(user.getDeptCode());

        return departmentRepository.findByDeptCode(baseDeptCode)
                .map(Department::getDeptName)
                .orElse(user.getDeptCode());
    }

    /**
     * ✅ 기존 휴가원의 totalDays 재계산 (관리자용)
     */

    @Transactional
    public void recalculateUserVacationHistory(String userId, Integer year) {
        // 1. 해당 연도 히스토리 조회
        UserAnnualVacationHistory history = vacationHistoryRepository
                .findByUserIdAndYear(userId, year)
                .orElseThrow(() -> new RuntimeException("연차 히스토리를 찾을 수 없습니다."));

        // 2. 승인된 휴가원의 실제 사용량 합산
        List<LeaveApplication> approvedApplications = leaveApplicationRepository
                .findAll().stream()
                .filter(app -> app.getApplicantId().equals(userId))
                .filter(app -> app.getStatus() == LeaveApplicationStatus.APPROVED)
                .filter(app -> app.getStartDate() != null && app.getStartDate().getYear() == year)
                .collect(Collectors.toList());

        double totalUsedCarryover = 0.0;
        double totalUsedRegular = 0.0;

        for (LeaveApplication app : approvedApplications) {
            // ✅ leave_application_day 기준으로 실제 사용량 계산
            double actualDays = app.getTotalDays() != null ? app.getTotalDays() : 0.0;

            // 월별로 이월/정상 구분
            int month = app.getStartDate().getMonthValue();

            if (month <= 2) {
                // 1~2월: 이월 먼저 차감
                double carryoverRemaining = history.getCarryoverDays() - totalUsedCarryover;
                if (carryoverRemaining >= actualDays) {
                    totalUsedCarryover += actualDays;
                } else {
                    totalUsedCarryover += carryoverRemaining;
                    totalUsedRegular += (actualDays - carryoverRemaining);
                }
            } else {
                // 3월 이후: 정상만 차감
                totalUsedRegular += actualDays;
            }
        }

        // 3. 히스토리 업데이트
        log.info("사용자 {} {}년 연차 재계산: 이월 {}→{}, 정상 {}→{}",
                userId, year,
                history.getUsedCarryoverDays(), totalUsedCarryover,
                history.getUsedRegularDays(), totalUsedRegular);

        history.setUsedCarryoverDays(totalUsedCarryover);
        history.setUsedRegularDays(totalUsedRegular);

        vacationHistoryRepository.save(history);
    }

    @Transactional
    public Map<String, Object> recalculateYearVacationHistory(Integer year) {
        log.info("{}년도 연차 히스토리 재계산 시작", year);

        // 1. 해당 연도의 승인된 연차만 조회
        List<LeaveApplication> approvedAnnual = leaveApplicationRepository.findAll().stream()
                .filter(app -> app.getStatus() == LeaveApplicationStatus.APPROVED)
                .filter(app -> app.getLeaveType() == LeaveType.ANNUAL_LEAVE)
                .filter(app -> app.getStartDate() != null
                        && app.getStartDate().getYear() == year)
                .collect(Collectors.toList());

        log.info("승인된 연차 휴가원 {}건 조회 완료", approvedAnnual.size());

        // 2. 사용자별로 이월/정상 사용일수 집계
        Map<String, UsageDetail> userUsageMap = new HashMap<>();

        for (LeaveApplication app : approvedAnnual) {
            String userId = app.getApplicantId();

            // ✅ LeaveApplicationDay에서 실제 사용일수 계산
            if (app.getDays() == null || app.getDays().isEmpty()) {
                log.warn("휴가원 {}에 days 데이터가 없습니다. totalDays={}로 대체",
                        app.getId(), app.getTotalDays());

                // days가 없으면 totalDays를 월별로 구분
                Double totalDays = app.getTotalDays() != null ? app.getTotalDays() : 0.0;
                int month = app.getStartDate().getMonthValue();

                UsageDetail detail = userUsageMap.computeIfAbsent(userId, k -> new UsageDetail());

                if (month <= 2) {
                    // 1~2월: 이월 우선 차감
                    UserAnnualVacationHistory history = vacationHistoryRepository
                            .findByUserIdAndYear(userId, year)
                            .orElse(null);

                    if (history != null) {
                        double carryoverRemaining = history.getCarryoverDays() - detail.carryover;
                        if (carryoverRemaining >= totalDays) {
                            detail.carryover += totalDays;
                        } else {
                            detail.carryover += carryoverRemaining;
                            detail.regular += (totalDays - carryoverRemaining);
                        }
                    } else {
                        detail.regular += totalDays;
                    }
                } else {
                    detail.regular += totalDays;
                }
                continue;
            }

            // ✅ LeaveApplicationDay 기반 정확한 계산
            for (LeaveApplicationDay day : app.getDays()) {
                int month = day.getDate().getMonthValue();
                Double dayValue = day.getDays() != null ? day.getDays() : 1.0;

                UsageDetail detail = userUsageMap.computeIfAbsent(userId, k -> new UsageDetail());

                if (month <= 2) {
                    // 1~2월: 이월 우선 차감
                    UserAnnualVacationHistory history = vacationHistoryRepository
                            .findByUserIdAndYear(userId, year)
                            .orElse(null);

                    if (history != null) {
                        double carryoverRemaining = history.getCarryoverDays() - detail.carryover;
                        if (carryoverRemaining >= dayValue) {
                            detail.carryover += dayValue;
                        } else {
                            detail.carryover += carryoverRemaining;
                            detail.regular += (dayValue - carryoverRemaining);
                        }
                    } else {
                        detail.regular += dayValue;
                    }
                } else {
                    detail.regular += dayValue;
                }
            }
        }

        log.info("사용자별 집계 완료: {}명", userUsageMap.size());

        // 3. UserAnnualVacationHistory 업데이트
        int updatedCount = 0;
        List<UserAnnualVacationHistory> allHistories =
                vacationHistoryRepository.findByYear(year);

        for (UserAnnualVacationHistory history : allHistories) {
            UsageDetail usage = userUsageMap.getOrDefault(
                    history.getUserId(),
                    new UsageDetail()
            );

            Double oldCarryover = history.getUsedCarryoverDays();
            Double oldRegular = history.getUsedRegularDays();

            if (!usage.carryover.equals(oldCarryover)
                    || !usage.regular.equals(oldRegular)) {

                history.setUsedCarryoverDays(usage.carryover);
                history.setUsedRegularDays(usage.regular);
                vacationHistoryRepository.save(history);

                log.info("연차 재계산: userId={}, year={}, 이월: {} → {}, 정상: {} → {}",
                        history.getUserId(), year,
                        oldCarryover, usage.carryover,
                        oldRegular, usage.regular);
                updatedCount++;
            }
        }

        log.info("{}년도 연차 히스토리 재계산 완료: {}명 업데이트", year, updatedCount);

        Map<String, Object> result = new HashMap<>();
        result.put("year", year);
        result.put("updatedCount", updatedCount);
        result.put("message", year + "년도 연차 히스토리 재계산 완료");

        return result;
    }

    // ✅ 내부 클래스
    private static class UsageDetail {
        Double carryover = 0.0;
        Double regular = 0.0;

        void add(Double c, Double r) {
            this.carryover += c;
            this.regular += r;
        }
    }
}