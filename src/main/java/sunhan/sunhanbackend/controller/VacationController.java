package sunhan.sunhanbackend.controller;

import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.*;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import sunhan.sunhanbackend.dto.response.*;
import sunhan.sunhanbackend.entity.mysql.Department;
import sunhan.sunhanbackend.entity.mysql.UserEntity;
import sunhan.sunhanbackend.enums.HalfDayType;
import sunhan.sunhanbackend.enums.PermissionType;
import sunhan.sunhanbackend.repository.mysql.DepartmentRepository;
import sunhan.sunhanbackend.repository.mysql.UserRepository;
import sunhan.sunhanbackend.service.PermissionService;
import sunhan.sunhanbackend.service.UserService;
import sunhan.sunhanbackend.service.VacationService;
import sunhan.sunhanbackend.service.VacationYearService;

import java.io.ByteArrayOutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequestMapping("/api/v1/vacation")
@RequiredArgsConstructor
public class VacationController {

    private final VacationService vacationService;
    private final UserRepository userRepository;
    private final DepartmentRepository departmentRepository;
    private final VacationYearService vacationYearService;
    private final PermissionService permissionService;
    private final UserService userService;

    /**
     * 특정 사용자의 휴가 사용 내역 조회
     */
    @GetMapping("/history/{userId}")
    public ResponseEntity<?> getVacationHistory(@PathVariable String userId, Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        try {
            String currentUserId = (String) authentication.getPrincipal();

            // 본인 또는 관리 권한이 있는 경우만 조회 가능
            if (!currentUserId.equals(userId) && !vacationService.canViewUserVacation(currentUserId, userId)) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
            }

            List<VacationHistoryResponseDto> history = vacationService.getVacationHistory(userId);
            return ResponseEntity.ok(history);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "휴가 내역 조회 중 오류가 발생했습니다."));
        }
    }

    /**
     * ✅ 특정 연도 휴가 현황 조회
     */
    @GetMapping("/status/{userId}")
    public ResponseEntity<?> getVacationStatus(
            @PathVariable String userId,
            @RequestParam(required = false) Integer year,
            Authentication authentication
    ) {
        try {
            String currentUserId = (String) authentication.getPrincipal();

            if (!currentUserId.equals(userId) && !vacationService.canViewUserVacation(currentUserId, userId)) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
            }

            VacationStatusResponseDto status = vacationService.getVacationStatus(
                    userId,
                    year != null ? year : LocalDate.now().getYear()
            );
            return ResponseEntity.ok(status);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "휴가 현황 조회 중 오류가 발생했습니다."));
        }
    }
    /**
     * ✅ 연도 범위 조회
     */
    @GetMapping("/status/{userId}/years")
    public ResponseEntity<?> getVacationStatusByYears(
            @PathVariable String userId,
            @RequestParam Integer startYear,
            @RequestParam Integer endYear,
            Authentication authentication
    ) {
        try {
            String currentUserId = (String) authentication.getPrincipal();

            if (!currentUserId.equals(userId) && !vacationService.canViewUserVacation(currentUserId, userId)) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
            }

            List<VacationStatusResponseDto> statuses = vacationService.getVacationStatusByYearRange(
                    userId, startYear, endYear
            );
            return ResponseEntity.ok(statuses);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "휴가 이력 조회 중 오류가 발생했습니다."));
        }
    }
    /**
     * 사용자의 휴가일수 설정 (관리자만) - Deprecated
     * @deprecated 대신 /vacation-details/{userId} PUT 엔드포인트 사용
     */
    @Deprecated
    @PutMapping("/total-days/{userId}")
    public ResponseEntity<?> setTotalVacationDays(
            @PathVariable String userId,
            @RequestBody Map<String, Object> request,
            Authentication authentication) {

        if (authentication == null || !authentication.isAuthenticated()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        try {
            String adminUserId = (String) authentication.getPrincipal();

            Object totalDaysObj = request.get("totalVacationDays");
            if (totalDaysObj == null) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "휴가일수를 입력해주세요."));
            }

            Double totalDays;
            try {
                if (totalDaysObj instanceof Integer) {
                    totalDays = ((Integer) totalDaysObj).doubleValue();
                } else if (totalDaysObj instanceof Double) {
                    totalDays = (Double) totalDaysObj;
                } else if (totalDaysObj instanceof Number) {
                    totalDays = ((Number) totalDaysObj).doubleValue();
                } else {
                    totalDays = Double.parseDouble(totalDaysObj.toString());
                }
            } catch (NumberFormatException e) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "올바른 숫자 형식이 아닙니다."));
            }

            if (totalDays < 0) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "휴가일수는 0보다 커야 합니다."));
            }

            // ✅ 현재 연도로 설정
            Integer currentYear = LocalDate.now().getYear();

            VacationDetailsDto dto = new VacationDetailsDto();
            dto.setAnnualCarryoverDays(0.0);
            dto.setAnnualRegularDays(totalDays);

            // ✅ year 파라미터 추가
            vacationService.setVacationDetails(adminUserId, userId, currentYear, dto);

            Map<String, Object> response = new HashMap<>();
            response.put("message", "휴가일수가 성공적으로 설정되었습니다.");
            response.put("userId", userId);
            response.put("totalVacationDays", totalDays);

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("휴가일수 설정 실패: userId={}", userId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "휴가일수 설정 중 오류가 발생했습니다."));
        }
    }

    /**
     * ✅ 특정 연도의 모든 사용자 연차 히스토리 재계산
     */
    @PostMapping("/admin/recalculate-history")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> recalculateVacationHistory(
            @RequestParam Integer year,
            Authentication auth
    ) {
        try {
            String userId = auth.getName();
            UserEntity user = userService.getUserInfo(userId);

            if (!user.isSuperAdmin()) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(Map.of("error", "시스템 관리자만 실행할 수 있습니다."));
            }

            // ✅ VacationService의 메서드 호출
            Map<String, Object> result = vacationService.recalculateYearVacationHistory(year);

            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("연차 히스토리 재계산 실패", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "재계산 중 오류가 발생했습니다."));
        }
    }

    /**
     * 단일 사용자 조회 (기존 API 호환)
     */
    @GetMapping("/my-status")
    public ResponseEntity<VacationStatusResponseDto> getMyStatus(Authentication auth) {
        String userId = (String) auth.getPrincipal();
        Integer currentYear = LocalDate.now().getYear();
        VacationStatusResponseDto status = vacationService.getVacationStatus(userId, currentYear);
        return ResponseEntity.ok(status);
    }

    /**
     * 현재 사용자의 휴가 사용 내역 조회
     */
    @GetMapping("/my-history")
    public ResponseEntity<?> getMyVacationHistory(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        try {
            String userId = (String) authentication.getPrincipal();
            List<VacationHistoryResponseDto> history = vacationService.getVacationHistory(userId);
            return ResponseEntity.ok(history);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "휴가 내역 조회 중 오류가 발생했습니다."));
        }
    }

    /**
     * ✅ 부서별 휴가 통계 조회 (정렬 기능 추가)
     */
    @GetMapping("/statistics")
    public ResponseEntity<?> getDepartmentStatistics(
            Authentication auth,
            @RequestParam(defaultValue = "usageRate") String sortBy,
            @RequestParam(defaultValue = "desc") String sortOrder
    ) {
        try {
            String userId = auth.getName();
            List<VacationStatisticsResponseDto> statistics =
                    vacationService.getDepartmentStatistics(userId, sortBy, sortOrder);
            return ResponseEntity.ok(statistics);
        } catch (Exception e) {
            log.error("부서별 휴가 통계 조회 실패", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * ✅ 특정 직원 휴가 통계 조회
     */
    @PostMapping("/statistics/specific")
    public ResponseEntity<?> getSpecificEmployeesVacation(
            Authentication auth,
            @RequestBody List<String> userIds
    ) {
        try {
            String adminUserId = auth.getName();
            List<EmployeeVacationDto> statistics =
                    vacationService.getSpecificEmployeesVacation(adminUserId, userIds);
            return ResponseEntity.ok(statistics);
        } catch (Exception e) {
            log.error("특정 직원 휴가 통계 조회 실패", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * ✅ 월별 휴가 통계 조회
     */
    @GetMapping("/statistics/monthly")
    public ResponseEntity<?> getMonthlyVacationStatistics(
            Authentication auth,
            @RequestParam int startYear,
            @RequestParam int startMonth,
            @RequestParam int endYear,
            @RequestParam int endMonth,
            @RequestParam(required = false) List<String> userIds
    ) {
        try {
            String adminUserId = auth.getName();
            List<MonthlyVacationStatisticsDto> statistics =
                    vacationService.getMonthlyVacationStatistics(
                            adminUserId, startYear, startMonth, endYear, endMonth, userIds
                    );
            return ResponseEntity.ok(statistics);
        } catch (Exception e) {
            log.error("월별 휴가 통계 조회 실패", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * ✅ 엑셀 다운로드
     */
    @GetMapping("/statistics/excel")
    public ResponseEntity<ByteArrayResource> downloadVacationStatisticsExcel(
            Authentication auth,
            @RequestParam(defaultValue = "usageRate") String sortBy,
            @RequestParam(defaultValue = "desc") String sortOrder
    ) {
        try {
            String userId = auth.getName();
            List<VacationStatisticsResponseDto> statistics =
                    vacationService.getDepartmentStatistics(userId, sortBy, sortOrder);

            byte[] excelData = generateExcel(statistics);

            ByteArrayResource resource = new ByteArrayResource(excelData);

            String filename = "vacation_statistics_" +
                    LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd")) + ".xlsx";

            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=" + filename)
                    .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                    .contentLength(excelData.length)
                    .body(resource);

        } catch (Exception e) {
            log.error("엑셀 다운로드 실패", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * ✅ 엑셀 생성 로직
     */
    private byte[] generateExcel(List<VacationStatisticsResponseDto> statistics) throws Exception {
        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("휴가 사용 통계");

            // 헤더 스타일
            CellStyle headerStyle = workbook.createCellStyle();
            Font headerFont = workbook.createFont();
            headerFont.setBold(true);
            headerStyle.setFont(headerFont);
            headerStyle.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            headerStyle.setAlignment(HorizontalAlignment.CENTER);

            int rowNum = 0;

            for (VacationStatisticsResponseDto dept : statistics) {
                // 부서 헤더
                Row deptRow = sheet.createRow(rowNum++);
                org.apache.poi.ss.usermodel.Cell deptCell = deptRow.createCell(0);
                deptCell.setCellValue(dept.getDeptName() + " (" + dept.getTotalEmployees() + "명)");
                deptCell.setCellStyle(headerStyle);

                // 컬럼 헤더
                Row headerRow = sheet.createRow(rowNum++);
                String[] headers = {"이름", "사번", "직급", "입사일자", "총 휴가", "사용", "남은휴가", "사용률(%)"};
                for (int i = 0; i < headers.length; i++) {
                    org.apache.poi.ss.usermodel.Cell cell = headerRow.createCell(i);
                    cell.setCellValue(headers[i]);
                    cell.setCellStyle(headerStyle);
                }

                // 직원 데이터
                for (EmployeeVacationDto emp : dept.getEmployees()) {
                    Row row = sheet.createRow(rowNum++);
                    row.createCell(0).setCellValue(emp.getUserName());
                    row.createCell(1).setCellValue(emp.getUserId());
                    row.createCell(2).setCellValue(getPositionByJobLevel(emp.getJobLevel()));
                    row.createCell(3).setCellValue(
                            emp.getStartDate() != null ?
                                    emp.getStartDate().format(DateTimeFormatter.ofPattern("yyyy-MM-dd")) :
                                    "-"
                    );
                    row.createCell(4).setCellValue(emp.getTotalDays());
                    row.createCell(5).setCellValue(emp.getUsedDays());
                    row.createCell(6).setCellValue(emp.getRemainingDays());
                    row.createCell(7).setCellValue(emp.getUsageRate());
                }

                rowNum++; // 부서 간 빈 줄
            }

            // 컬럼 너비 자동 조정
            for (int i = 0; i < 8; i++) {
                sheet.autoSizeColumn(i);
            }

            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            workbook.write(outputStream);
            return outputStream.toByteArray();
        }
    }

    private String getPositionByJobLevel(String jobLevel) {
        switch (jobLevel) {
            case "0": return "사원";
            case "1": return "부서장";
            case "2": return "센터장";
            case "3": return "원장";
            case "4": return "행정원장";
            case "5": return "대표원장";
            default: return "미설정";
        }
    }

    /**
     * 부서별 조회 (관리자용 - 성능 최적화)
     */
    @GetMapping("/department/{deptCode}/status")
    public ResponseEntity<List<VacationStatusResponseDto>> getDepartmentStatus(
            @PathVariable String deptCode,
            Authentication auth) {

        // 부서 직원 목록 조회
        List<UserEntity> deptUsers = userRepository.findByDeptCodeAndUseFlag(deptCode, "1");
        List<String> userIds = deptUsers.stream()
                .map(UserEntity::getUserId)
                .collect(Collectors.toList());

        // ✅ 일괄 조회 (N+1 문제 해결)
        List<VacationStatusResponseDto> statuses = vacationService.getVacationStatusBatch(userIds);

        return ResponseEntity.ok(statuses);
    }

    /**
     * ✅ 부서 요약 정보만 조회 (직원 데이터 제외)
     */
    @GetMapping("/statistics/summary")
    public ResponseEntity<?> getDepartmentSummaries(Authentication auth) {
        try {
            String userId = auth.getName();
            List<DepartmentSummaryDto> summaries =
                    vacationService.getDepartmentSummaries(userId);
            return ResponseEntity.ok(summaries);
        } catch (Exception e) {
            log.error("부서 요약 정보 조회 실패", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * ✅ 특정 부서 상세 정보 조회 (직원별 상세 정보 포함)
     */
    @GetMapping("/statistics/department/{deptCode}")
    public ResponseEntity<?> getDepartmentDetail(
            Authentication auth,
            @PathVariable String deptCode,
            @RequestParam(defaultValue = "usageRate") String sortBy,
            @RequestParam(defaultValue = "desc") String sortOrder
    ) {
        try {
            String userId = auth.getName();
            VacationStatisticsResponseDto detail =
                    vacationService.getDepartmentDetail(userId, deptCode, sortBy, sortOrder);
            return ResponseEntity.ok(detail);
        } catch (Exception e) {
            log.error("부서 상세 정보 조회 실패", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * ✅ 선택된 부서의 직원별 상세 현황을 엑셀로 다운로드 (전체 지원)
     */
    @GetMapping("/statistics/excel/department/{deptCode}")
    public ResponseEntity<ByteArrayResource> downloadDepartmentExcel(
            Authentication auth,
            @PathVariable String deptCode,
            @RequestParam(defaultValue = "usageRate") String sortBy,
            @RequestParam(defaultValue = "desc") String sortOrder
    ) {
        try {
            String userId = auth.getName();

            // ✅ "ALL"이든 특정 부서든 동일하게 처리
            VacationStatisticsResponseDto dept =
                    vacationService.getDepartmentDetail(userId, deptCode, sortBy, sortOrder);

            // 엑셀 생성
            byte[] excelData = generateExcel(List.of(dept));

            ByteArrayResource resource = new ByteArrayResource(excelData);
            String filename = dept.getDeptName() + "_vacation_statistics_" +
                    LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd")) + ".xlsx";

            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=" + filename)
                    .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                    .contentLength(excelData.length)
                    .body(resource);

        } catch (Exception e) {
            log.error("엑셀 다운로드 실패", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * ✅ 선택된 직원들의 휴가 통계를 엑셀로 다운로드
     */
    @PostMapping("/statistics/excel/custom")
    public ResponseEntity<ByteArrayResource> downloadCustomExcel(
            Authentication auth,
            @RequestBody List<String> userIds,
            @RequestParam(defaultValue = "usageRate") String sortBy,
            @RequestParam(defaultValue = "desc") String sortOrder
    ) {
        try {
            String userId = auth.getName();

            // 선택된 직원들의 휴가 정보 조회
            List<EmployeeVacationDto> employees =
                    vacationService.getSpecificEmployeesVacation(userId, userIds);

            // 정렬 적용
            employees = vacationService.sortEmployeeList(employees, sortBy, sortOrder);

            // 가상의 부서 생성
            VacationStatisticsResponseDto customDept = VacationStatisticsResponseDto.builder()
                    .deptCode("CUSTOM")
                    .deptName("선택된 직원")
                    .totalEmployees(employees.size())
                    .avgUsageRate(employees.stream()
                            .mapToDouble(EmployeeVacationDto::getUsageRate)
                            .average()
                            .orElse(0.0))
                    .totalVacationDays(employees.stream()
                            .mapToDouble(EmployeeVacationDto::getTotalDays)
                            .sum())
                    .totalUsedDays(employees.stream()
                            .mapToDouble(EmployeeVacationDto::getUsedDays)
                            .sum())
                    .totalRemainingDays(employees.stream()
                            .mapToDouble(EmployeeVacationDto::getRemainingDays)
                            .sum())
                    .employees(employees)
                    .build();

            // 엑셀 생성
            byte[] excelData = generateExcel(List.of(customDept));

            ByteArrayResource resource = new ByteArrayResource(excelData);
            String filename = "선택직원_vacation_statistics_" +
                    LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd")) + ".xlsx";

            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=" +
                            URLEncoder.encode(filename, StandardCharsets.UTF_8))
                    .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                    .contentLength(excelData.length)
                    .body(resource);

        } catch (Exception e) {
            log.error("커스텀 엑셀 다운로드 실패", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * ✅ 연차 설정 (연도별)
     */
    @PutMapping("/vacation-details/{userId}")
    public ResponseEntity<?> setVacationDetails(
            @PathVariable String userId,
            @RequestParam(required = false) Integer year,
            @RequestBody VacationDetailsDto dto,
            Authentication authentication
    ) {
        try {
            String adminUserId = (String) authentication.getPrincipal();

            if (year == null) {
                year = LocalDate.now().getYear();
            }

            vacationService.setVacationDetails(adminUserId, userId, year, dto);

            return ResponseEntity.ok(Map.of(
                    "message", "연차일수가 성공적으로 설정되었습니다.",
                    "userId", userId,
                    "year", year
            ));
        } catch (Exception e) {
            log.error("연차일수 설정 실패: userId={}, year={}", userId, year, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "연차일수 설정 중 오류가 발생했습니다."));
        }
    }

    /**
     * ✅ 관리자용: 연도별 데이터 수동 초기화
     */
    @PostMapping("/admin/initialize-year")
    public ResponseEntity<?> initializeYear(
            @RequestParam Integer year,
            @RequestParam(defaultValue = "false") boolean forceOverwrite,
            Authentication auth
    ) {
        try {
            String userId = auth.getName();
            UserEntity user = userRepository.findByUserId(userId)
                    .orElseThrow(() -> new EntityNotFoundException("사용자를 찾을 수 없습니다."));

            if (!user.isSuperAdmin()) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(Map.of("error", "시스템 관리자만 실행할 수 있습니다."));
            }

            vacationYearService.initializeYearVacationManually(year, forceOverwrite);

            return ResponseEntity.ok(Map.of(
                    "message", year + "년 연차 데이터 초기화가 완료되었습니다.",
                    "year", year
            ));
        } catch (Exception e) {
            log.error("연도 초기화 실패: year={}", year, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "초기화 중 오류가 발생했습니다."));
        }
    }

    @GetMapping("/statistics/ledger")
    public ResponseEntity<?> getVacationLedger(
            Authentication auth,
            @RequestParam(required = false, defaultValue = "ALL") String deptCode,
            @RequestParam(defaultValue = "ALL") String leaveType,
            @RequestParam(defaultValue = "2025") int year
    ) {
        try {
            String userId = auth.getName();
            List<VacationLedgerDto> ledger = vacationService.getVacationLedger(userId, deptCode, leaveType, year);
            return ResponseEntity.ok(ledger);
        } catch (Exception e) {
            log.error("관리대장 조회 실패", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    // ✅ 특정 직원들 관리대장 조회
    @PostMapping("/statistics/ledger/users")
    public ResponseEntity<?> getVacationLedgerByUsers(
            Authentication auth,
            @RequestBody List<String> userIds,
            @RequestParam(defaultValue = "2025") int year
    ) {
        try {
            String adminUserId = auth.getName();
            List<VacationLedgerDto> ledger = vacationService.getVacationLedgerByUsers(adminUserId, userIds, year);
            return ResponseEntity.ok(ledger);
        } catch (Exception e) {
            log.error("특정 직원 관리대장 조회 실패", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/statistics/ledger/excel")
    public ResponseEntity<ByteArrayResource> downloadLedgerExcel(
            Authentication auth,
            @RequestParam(required = false, defaultValue = "ALL") String deptCode,
            @RequestParam(defaultValue = "ALL") String leaveType,
            @RequestParam(defaultValue = "2025") int year
    ) {
        try {
            String userId = auth.getName();
            List<VacationLedgerDto> ledger = vacationService.getVacationLedger(userId, deptCode, leaveType, year);

            // ✅ 부서명 조회
            String deptName = "전체";
            if (!"ALL".equals(deptCode)) {
                try {
                    Department dept = departmentRepository.findById(deptCode.replaceAll("\\d+$", "")).orElse(null);
                    deptName = dept != null ? dept.getDeptName() : deptCode;
                } catch (Exception e) {
                    deptName = deptCode;
                }
            }

            byte[] excelData = generateLedgerExcel(ledger, leaveType, year, deptName);

            ByteArrayResource resource = new ByteArrayResource(excelData);

            String filename = String.format("%d년_연차특별경조_휴가관리대장[%s].xlsx", year, deptName);

            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=" +
                            URLEncoder.encode(filename, StandardCharsets.UTF_8))
                    .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                    .contentLength(excelData.length)
                    .body(resource);
        } catch (Exception e) {
            log.error("관리대장 엑셀 다운로드 실패", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    // ✅ 특정 직원들 엑셀 다운로드
    @PostMapping("/statistics/ledger/excel/users")
    public ResponseEntity<ByteArrayResource> downloadLedgerExcelByUsers(
            Authentication auth,
            @RequestBody List<String> userIds,
            @RequestParam(defaultValue = "2025") int year
    ) {
        try {
            String adminUserId = auth.getName();
            List<VacationLedgerDto> ledger = vacationService.getVacationLedgerByUsers(adminUserId, userIds, year);

            byte[] excelData = generateLedgerExcel(ledger, "ALL", year, "선택직원");

            ByteArrayResource resource = new ByteArrayResource(excelData);

            String filename = String.format("%d년_연차특별경조_휴가관리대장[선택직원].xlsx", year);

            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=" +
                            URLEncoder.encode(filename, StandardCharsets.UTF_8))
                    .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                    .contentLength(excelData.length)
                    .body(resource);
        } catch (Exception e) {
            log.error("특정 직원 관리대장 엑셀 다운로드 실패", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    private byte[] generateLedgerExcel(List<VacationLedgerDto> ledger, String leaveType, int year, String deptName) throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("휴가 관리대장");

            // 스타일 정의
            CellStyle headerStyle = workbook.createCellStyle();
            Font headerFont = workbook.createFont();
            headerFont.setBold(true);
            headerStyle.setFont(headerFont);
            headerStyle.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            headerStyle.setAlignment(HorizontalAlignment.CENTER);
            headerStyle.setVerticalAlignment(VerticalAlignment.CENTER);
            headerStyle.setBorderTop(BorderStyle.THIN);
            headerStyle.setBorderBottom(BorderStyle.THIN);
            headerStyle.setBorderLeft(BorderStyle.THIN);
            headerStyle.setBorderRight(BorderStyle.THIN);

            // ✅ 기본 셀 스타일 (경계선만)
            XSSFCellStyle baseStyle = workbook.createCellStyle();
            baseStyle.setAlignment(HorizontalAlignment.LEFT);
            baseStyle.setVerticalAlignment(VerticalAlignment.TOP);
            baseStyle.setWrapText(true);
            baseStyle.setBorderTop(BorderStyle.THIN);
            baseStyle.setBorderBottom(BorderStyle.THIN);
            baseStyle.setBorderLeft(BorderStyle.THIN);
            baseStyle.setBorderRight(BorderStyle.THIN);

            // ✅ 폰트 정의 (Rich Text용)
            XSSFFont blackFont = workbook.createFont();
            blackFont.setColor(new XSSFColor(new byte[]{0, 0, 0}, null)); // 검정

            XSSFFont redFont = workbook.createFont();
            redFont.setColor(new XSSFColor(new byte[]{(byte)255, 0, 0}, null)); // 빨강

            XSSFFont blueFont = workbook.createFont();
            blueFont.setColor(new XSSFColor(new byte[]{0, 0, (byte)255}, null)); // 파랑

            CellStyle normalStyle = workbook.createCellStyle();
            normalStyle.setAlignment(HorizontalAlignment.CENTER);
            normalStyle.setVerticalAlignment(VerticalAlignment.CENTER);
            normalStyle.setBorderTop(BorderStyle.THIN);
            normalStyle.setBorderBottom(BorderStyle.THIN);
            normalStyle.setBorderLeft(BorderStyle.THIN);
            normalStyle.setBorderRight(BorderStyle.THIN);

            int rowNum = 0;

            // 제목 행
            Row titleRow = sheet.createRow(rowNum++);
            Cell titleCell = titleRow.createCell(0);

            CellStyle titleStyle = workbook.createCellStyle();
            Font titleFont = workbook.createFont();
            titleFont.setBold(true);
            titleFont.setFontHeightInPoints((short) 16);
            titleStyle.setFont(titleFont);
            titleStyle.setAlignment(HorizontalAlignment.CENTER);
            titleStyle.setVerticalAlignment(VerticalAlignment.CENTER);

            String title = String.format("%d년 연차, 특별/경조 휴가 관리대장 [%s]", year, deptName);
            titleCell.setCellValue(title);
            titleCell.setCellStyle(titleStyle);

            sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, 33));
            rowNum++;

            // 첫 번째 헤더 행
            Row headerRow1 = sheet.createRow(rowNum++);
            int colNum = 0;

            String[] basicHeaders = {"번호", "부서명", "성명", "입사일자", "휴가구분", "작년이월", "휴가일수"};
            for (String header : basicHeaders) {
                Cell cell = headerRow1.createCell(colNum);
                cell.setCellValue(header);
                cell.setCellStyle(headerStyle);
                sheet.addMergedRegion(new CellRangeAddress(rowNum - 1, rowNum, colNum, colNum));
                colNum++;
            }

            for (int month = 1; month <= 12; month++) {
                Cell monthCell = headerRow1.createCell(colNum);
                monthCell.setCellValue(month + "월");
                monthCell.setCellStyle(headerStyle);
                sheet.addMergedRegion(new CellRangeAddress(rowNum - 1, rowNum - 1, colNum, colNum + 1));
                colNum += 2;
            }

            String[] endHeaders = {"사용계", "남은개수", "비고"};
            for (String header : endHeaders) {
                Cell cell = headerRow1.createCell(colNum);
                cell.setCellValue(header);
                cell.setCellStyle(headerStyle);
                sheet.addMergedRegion(new CellRangeAddress(rowNum - 1, rowNum, colNum, colNum));
                colNum++;
            }

            // 두 번째 헤더 행
            Row headerRow2 = sheet.createRow(rowNum++);
            colNum = 7;

            for (int month = 1; month <= 12; month++) {
                Cell cell1 = headerRow2.createCell(colNum++);
                cell1.setCellValue("사용일");
                cell1.setCellStyle(headerStyle);

                Cell cell2 = headerRow2.createCell(colNum++);
                cell2.setCellValue("계");
                cell2.setCellStyle(headerStyle);
            }

            // ✅ 데이터 행
            String prevUserName = null;
            int userStartRow = -1;

            for (int i = 0; i < ledger.size(); i++) {
                VacationLedgerDto entry = ledger.get(i);
                Row row = sheet.createRow(rowNum);
                colNum = 0;

                boolean isNewUser = !entry.getUserName().equals(prevUserName);

                if (isNewUser) {
                    userStartRow = rowNum;
                    prevUserName = entry.getUserName();
                }

                if (isNewUser) {
                    createCell(row, colNum++, entry.getRowNumber(), normalStyle);
                    createCell(row, colNum++, entry.getDeptName(), normalStyle);
                    createCell(row, colNum++, entry.getUserName(), normalStyle);

                    String startDateStr = "-";
                    if (entry.getStartDate() != null && !entry.getStartDate().isEmpty()) {
                        try {
                            LocalDate date = LocalDate.parse(entry.getStartDate());
                            startDateStr = date.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
                        } catch (Exception e) {
                            startDateStr = entry.getStartDate();
                        }
                    }
                    createCell(row, colNum++, startDateStr, normalStyle);
                } else {
                    for (int j = 0; j < 4; j++) {
                        Cell emptyCell = row.createCell(colNum++);
                        emptyCell.setCellStyle(normalStyle);
                    }
                }

                createCell(row, colNum++, entry.getLeaveType(), normalStyle);
                createCell(row, colNum++, entry.getCarryoverDays() != null ? entry.getCarryoverDays() : "-", normalStyle);
                createCell(row, colNum++, entry.getRegularDays() != null ? entry.getRegularDays() : "-", normalStyle);

                // ✅ 월별 데이터 (Rich Text 사용)
                for (int month = 1; month <= 12; month++) {
                    VacationLedgerDto.MonthlyUsage monthData = entry.getMonthlyUsage().get(month);

                    if (monthData != null && !monthData.getDetails().isEmpty()) {
                        XSSFCell detailCell = (XSSFCell) row.createCell(colNum);
                        detailCell.setCellStyle(baseStyle);

                        // ✅ Rich Text String 생성
                        XSSFRichTextString richText = new XSSFRichTextString();

                        for (int j = 0; j < monthData.getDetails().size(); j++) {
                            VacationLedgerDto.DailyDetail daily = monthData.getDetails().get(j);
                            LocalDate date = LocalDate.parse(daily.getDate());
                            String dayText = String.valueOf(date.getDayOfMonth());

                            if (j > 0) {
                                richText.append(", ", blackFont); // 구분자는 검정
                            }

                            // ✅ 각 날짜에 타입별 색상 적용
                            switch (daily.getHalfDayType()) {
                                case ALL_DAY:
                                    richText.append(dayText, blackFont);
                                    break;
                                case MORNING:
                                    richText.append(dayText, redFont);
                                    break;
                                case AFTERNOON:
                                    richText.append(dayText, blueFont);
                                    break;
                            }
                        }

                        detailCell.setCellValue(richText);
                    } else {
                        createCell(row, colNum, "-", normalStyle);
                    }
                    colNum++;

                    if (monthData != null) {
                        createCell(row, colNum, monthData.getMonthTotal(), normalStyle);
                    } else {
                        createCell(row, colNum, 0.0, normalStyle);
                    }
                    colNum++;
                }

                createCell(row, colNum++, entry.getTotalUsed(), normalStyle);
                createCell(row, colNum++, entry.getRemaining() != null ? entry.getRemaining() : "-", normalStyle);
                createCell(row, colNum++, entry.getRemarks() != null ? entry.getRemarks() : "", normalStyle);

                boolean isLastRowOfUser = (i == ledger.size() - 1) ||
                        !entry.getUserName().equals(ledger.get(i + 1).getUserName());

                if (isLastRowOfUser && userStartRow >= 0) {
                    for (int col = 0; col < 4; col++) {
                        sheet.addMergedRegion(new CellRangeAddress(userStartRow, rowNum, col, col));
                    }
                }

                rowNum++;
            }

            // 컬럼 너비 조정
            for (int i = 0; i < 7; i++) {
                sheet.autoSizeColumn(i);
                sheet.setColumnWidth(i, Math.max(sheet.getColumnWidth(i), 3000));
            }

            for (int month = 0; month < 12; month++) {
                int usageCol = 7 + (month * 2);
                int totalCol = usageCol + 1;

                sheet.setColumnWidth(usageCol, 5000);
                sheet.setColumnWidth(totalCol, 2500);
            }

            for (int i = 31; i < 34; i++) {
                sheet.autoSizeColumn(i);
                sheet.setColumnWidth(i, Math.max(sheet.getColumnWidth(i), 3000));
            }

            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            workbook.write(outputStream);
            return outputStream.toByteArray();
        }
    }

    private void createCell(Row row, int column, Object value, CellStyle style) {
        Cell cell = row.createCell(column);
        if (value instanceof String) {
            cell.setCellValue((String) value);
        } else if (value instanceof Integer) {
            cell.setCellValue((Integer) value);
        } else if (value instanceof Double) {
            cell.setCellValue((Double) value);
        }
        cell.setCellStyle(style);
    }
}
