package sunhan.sunhanbackend.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import sunhan.sunhanbackend.dto.response.*;
import sunhan.sunhanbackend.entity.mysql.UserEntity;
import sunhan.sunhanbackend.repository.mysql.UserRepository;
import sunhan.sunhanbackend.service.VacationService;

import java.io.ByteArrayOutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequestMapping("/api/v1/vacation")
@RequiredArgsConstructor
public class VacationController {

    private final VacationService vacationService;
    private final UserRepository userRepository;

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
     * 특정 사용자의 휴가 현황 조회 (총 휴가일수, 사용일수, 남은일수)
     */
    @GetMapping("/status/{userId}")
    public ResponseEntity<?> getVacationStatus(@PathVariable String userId, Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        try {
            String currentUserId = (String) authentication.getPrincipal();

            // 본인 또는 관리 권한이 있는 경우만 조회 가능
            if (!currentUserId.equals(userId) && !vacationService.canViewUserVacation(currentUserId, userId)) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
            }

            VacationStatusResponseDto status = vacationService.getVacationStatus(userId);
            return ResponseEntity.ok(status);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "휴가 현황 조회 중 오류가 발생했습니다."));
        }
    }

    /**
     * 사용자의 총 휴가일수 설정 (관리자만)
     */
    @PutMapping("/total-days/{userId}")
    public ResponseEntity<?> setTotalVacationDays(
            @PathVariable String userId,
            @RequestBody Map<String, Object> request, // ✅ Object로 변경
            Authentication authentication) {

        if (authentication == null || !authentication.isAuthenticated()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        try {
            String adminUserId = (String) authentication.getPrincipal();

            // ✅ Object를 Double로 안전하게 변환
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

            // ✅ Double 타입으로 서비스 호출
            vacationService.setTotalVacationDays(adminUserId, userId, totalDays);

            Map<String, Object> response = new HashMap<>();
            response.put("message", "휴가일수가 성공적으로 설정되었습니다.");
            response.put("userId", userId);
            response.put("totalVacationDays", totalDays);

            return ResponseEntity.ok(response);

        } catch (ObjectOptimisticLockingFailureException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "다른 사용자가 동시에 정보를 수정했습니다. 새로고침 후 다시 시도해주세요."));
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("휴가일수 설정 실패: userId={}, totalDays={}", userId, request.get("totalVacationDays"), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "휴가일수 설정 중 오류가 발생했습니다."));
        }
    }

    /**
     * 단일 사용자 조회 (기존 API 호환)
     */
    @GetMapping("/my-status")
    public ResponseEntity<VacationStatusResponseDto> getMyStatus(Authentication auth) {
        String userId = (String) auth.getPrincipal();
        VacationStatusResponseDto status = vacationService.getVacationStatus(userId);
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
            case "2": return "진료센터장";
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
}
