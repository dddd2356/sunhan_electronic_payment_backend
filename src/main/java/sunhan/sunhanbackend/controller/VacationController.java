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
import sunhan.sunhanbackend.service.VacationService;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/v1/vacation")
@RequiredArgsConstructor
public class VacationController {

    private final VacationService vacationService;

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
    public ResponseEntity<?> setTotalVacationDays(@PathVariable String userId,
                                                  @RequestBody Map<String, Integer> request,
                                                  Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        try {
            String adminUserId = (String) authentication.getPrincipal();
            Integer totalDays = request.get("totalVacationDays");

            if (totalDays == null || totalDays < 0) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "올바른 휴가일수를 입력해주세요."));
            }

            vacationService.setTotalVacationDays(adminUserId, userId, totalDays);

            Map<String, Object> response = new HashMap<>();
            response.put("message", "휴가일수가 성공적으로 설정되었습니다.");
            response.put("userId", userId);
            response.put("totalVacationDays", totalDays);

            return ResponseEntity.ok(response);
        } catch (ObjectOptimisticLockingFailureException e) {
            // 동시성 충돌 발생 시 409 Conflict 반환
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "다른 사용자가 동시에 정보를 수정했습니다. 새로고침 후 다시 시도해주세요."));
        } catch (RuntimeException e) {
            // 권한 문제 등 그 외 RuntimeException은 403 Forbidden 반환
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "휴가일수 설정 중 오류가 발생했습니다."));
        }
    }

    /**
     * 현재 사용자의 휴가 현황 조회
     */
    @GetMapping("/my-status")
    public ResponseEntity<?> getMyVacationStatus(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        try {
            String userId = (String) authentication.getPrincipal();
            VacationStatusResponseDto status = vacationService.getVacationStatus(userId);
            return ResponseEntity.ok(status);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "휴가 현황 조회 중 오류가 발생했습니다."));
        }
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
}
