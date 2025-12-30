package sunhan.sunhanbackend.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import sunhan.sunhanbackend.dto.response.VacationHistoryResponseDto;
import sunhan.sunhanbackend.dto.response.VacationStatisticsResponseDto;
import sunhan.sunhanbackend.dto.response.VacationStatusResponseDto;
import sunhan.sunhanbackend.entity.mysql.UserEntity;
import sunhan.sunhanbackend.repository.mysql.UserRepository;
import sunhan.sunhanbackend.service.VacationService;

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
     * 부서별 휴가 통계 조회
     */
    @GetMapping("/statistics")
    public ResponseEntity<?> getDepartmentStatistics(
            @AuthenticationPrincipal String userId) {
        try {
            List<VacationStatisticsResponseDto> statistics =
                    vacationService.getDepartmentStatistics(userId);
            return ResponseEntity.ok(statistics);
        } catch (RuntimeException e) {
            log.error("통계 조회 실패: {}", e.getMessage( ));
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", e.getMessage()));
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
}
