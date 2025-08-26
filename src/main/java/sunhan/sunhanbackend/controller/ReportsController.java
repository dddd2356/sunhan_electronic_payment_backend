package sunhan.sunhanbackend.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import sunhan.sunhanbackend.dto.response.ReportsResponseDto;
import sunhan.sunhanbackend.entity.mysql.UserEntity;
import sunhan.sunhanbackend.service.IntegratedReportsService;
import sunhan.sunhanbackend.service.UserService;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequiredArgsConstructor
@Slf4j
@RequestMapping("/api/v1/user")
public class ReportsController {

    private final IntegratedReportsService integratedReportsService;
    private final UserService userService;

    /**
     * 문서 현황 보고서 - 상태별 개수만 반환 (DB 레벨 최적화)
     */
    @GetMapping("/reports/documents")
    public ResponseEntity<Map<String, Object>> getDocumentReports(Authentication auth) {
        String userId = auth.getName();
        log.info("요청 userId: {}", userId);

        try {
            UserEntity currentUser = userService.getUserInfo(userId);
            boolean isAdmin = currentUser != null && currentUser.isAdmin();

            // DB 레벨에서 최적화된 카운트 조회
            Map<String, Long> counts = integratedReportsService.getDocumentCounts(userId, isAdmin);

            Map<String, Object> reportSummary = new HashMap<>();
            reportSummary.put("counts", counts);
            return ResponseEntity.ok(reportSummary);

        } catch (Exception e) {
            log.error("문서 보고서 요약 조회 실패: userId={}", userId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "문서 보고서 조회 중 오류가 발생했습니다."));
        }
    }

    /**
     * 특정 상태의 문서 목록 상세 조회 - 모든 상태에 DB 레벨 최적화 적용
     */
    @GetMapping("/reports/documents/{status}")
    public ResponseEntity<Page<ReportsResponseDto>> getDocumentsByStatus(
            @PathVariable String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            Authentication auth) {

        String userId = auth.getName();
        UserEntity currentUser = userService.getUserInfo(userId);
        boolean isAdmin = currentUser != null && currentUser.isAdmin();

        Pageable pageable = PageRequest.of(page, size, Sort.by("updatedAt").descending());

        // 모든 상태에 대해 DB 레벨 최적화 적용
        Page<ReportsResponseDto> resultPage = integratedReportsService.getDocumentsByStatus(
                userId, status, isAdmin, pageable
        );

        return ResponseEntity.ok(resultPage);
    }

    /**
     * 문서 상태를 카테고리로 매핑하는 헬퍼 메서드
     */
    private String mapStatusToCategory(String status) {
        switch (status) {
            // 임시 저장
            case "DRAFT":
                return "draft";

            // 진행 중 (계약서 / 휴가원 공통)
            case "SENT_TO_EMPLOYEE":
            case "SIGNED_BY_EMPLOYEE":
            case "PENDING_SUBSTITUTE":
            case "PENDING_DEPT_HEAD":
            case "PENDING_CENTER_DIRECTOR":
            case "PENDING_ADMIN_DIRECTOR":
            case "PENDING_CEO_DIRECTOR":
            case "PENDING_HR_STAFF":
            case "PENDING":
                return "inProgress";

            // 반려/반송
            case "RETURNED_TO_ADMIN":
            case "REJECTED":
            case "DELETED":
                return "rejected";

            // 완료
            case "COMPLETED":
            case "APPROVED":
                return "completed";

            default:
                return "draft"; // 기본값
        }
    }
}