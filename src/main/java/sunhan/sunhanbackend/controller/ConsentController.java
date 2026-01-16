package sunhan.sunhanbackend.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import sunhan.sunhanbackend.entity.mysql.UserEntity;
import sunhan.sunhanbackend.entity.mysql.consent.ConsentAgreement;
import sunhan.sunhanbackend.enums.consent.ConsentStatus;
import sunhan.sunhanbackend.enums.consent.ConsentType;
import sunhan.sunhanbackend.repository.mysql.UserRepository;
import sunhan.sunhanbackend.service.consent.ConsentService;
import sunhan.sunhanbackend.util.ConsentPdfRenderer;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/v1/consents")
@RequiredArgsConstructor
public class ConsentController {

    private final ConsentService consentService;
    private final UserRepository userRepository;
    // ==================== 권한 확인 ====================

    /**
     * 현재 사용자의 동의서 관련 권한 조회
     */
    @GetMapping("/permissions")
    public ResponseEntity<Map<String, Boolean>> getMyPermissions(Authentication auth) {
        String userId = auth.getName();

        Map<String, Boolean> permissions = Map.of(
                "canCreate", consentService.hasCreatePermission(userId),
                "canManage", consentService.hasManagePermission(userId)
        );

        return ResponseEntity.ok(permissions);
    }

    // ==================== 관리자용 API ====================

    /**
     * 관리자용: 전체 동의서 목록 조회
     * 권한: CONSENT_MANAGE 필요
     */
    @GetMapping("/admin/list")
    public ResponseEntity<?> getAdminList(Authentication auth) {
        try {
            String userId = auth.getName();
            List<ConsentAgreement> agreements = consentService.findAllForAdmin(userId);
            return ResponseEntity.ok(agreements);
        } catch (Exception e) {
            log.error("관리자 목록 조회 실패", e);
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 관리자용: 검색/필터링 (페이징)
     * @param status 상태 필터 (optional)
     * @param type 타입 필터 (optional)
     * @param searchTerm 검색어 (userId, userName) (optional)
     * @param page 페이지 번호 (default: 0)
     * @param size 페이지 크기 (default: 20)
     */
    @GetMapping("/admin/search")
    public ResponseEntity<?> searchAgreements(
            @RequestParam(required = false) ConsentStatus status,
            @RequestParam(required = false) ConsentType type,
            @RequestParam(required = false) String searchTerm,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            Authentication auth
    ) {
        try {
            String userId = auth.getName();
            Pageable pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());

            Page<ConsentAgreement> result = consentService.searchAgreements(
                    userId, status, type, searchTerm, pageable
            );

            return ResponseEntity.ok(Map.of(
                    "content", result.getContent(),
                    "totalElements", result.getTotalElements(),
                    "totalPages", result.getTotalPages(),
                    "currentPage", result.getNumber()
            ));
        } catch (Exception e) {
            log.error("검색 실패", e);
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 관리자용: 통계 조회
     */
    @GetMapping("/admin/statistics")
    public ResponseEntity<?> getStatistics(Authentication auth) {
        try {
            String userId = auth.getName();
            Map<String, Object> stats = consentService.getStatistics(userId);
            return ResponseEntity.ok(stats);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    // ==================== 생성자용 API ====================

    /**
     * 생성자용: 내가 발송한 동의서 목록
     * 권한: CONSENT_CREATE 필요
     */
    @GetMapping("/creator/list")
    public ResponseEntity<?> getMyIssuedList(Authentication auth) {
        try {
            String userId = auth.getName();
            List<ConsentAgreement> agreements = consentService.findByCreator(userId);
            return ResponseEntity.ok(agreements);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 생성자용: 내가 발송한 완료 동의서만
     */
    @GetMapping("/creator/completed")
    public ResponseEntity<?> getMyCompletedList(Authentication auth) {
        try {
            String userId = auth.getName();
            List<ConsentAgreement> agreements = consentService.findCompletedByCreator(userId);
            return ResponseEntity.ok(agreements);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 동의서 발송 (1명)
     */
    @PostMapping("/issue")
    public ResponseEntity<?> issueConsent(
            @RequestBody Map<String, String> request,
            Authentication auth
    ) {
        try {
            String creatorId = auth.getName();
            String targetUserId = request.get("targetUserId");
            ConsentType type = ConsentType.valueOf(request.get("type"));

            ConsentAgreement agreement = consentService.issueConsent(creatorId, targetUserId, type);

            return ResponseEntity.ok(Map.of(
                    "message", "동의서가 발송되었습니다.",
                    "agreementId", agreement.getId()
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "잘못된 동의서 타입입니다."));
        } catch (Exception e) {
            log.error("동의서 발송 실패", e);
            return ResponseEntity.badRequest()
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 동의서 배치 발송 (여러 명)
     */
    @PostMapping("/issue/batch")
    public ResponseEntity<?> issueBatchConsents(
            @RequestBody Map<String, Object> request,
            Authentication auth
    ) {
        try {
            String creatorId = auth.getName();
            List<String> targetUserIds = (List<String>) request.get("targetUserIds");
            ConsentType type = ConsentType.valueOf((String) request.get("type"));

            List<ConsentAgreement> agreements = consentService.issueBatchConsents(
                    creatorId, targetUserIds, type
            );

            return ResponseEntity.ok(Map.of(
                    "message", String.format("%d명에게 동의서를 발송했습니다.", agreements.size()),
                    "successCount", agreements.size(),
                    "failCount", targetUserIds.size() - agreements.size()
            ));
        } catch (Exception e) {
            log.error("배치 발송 실패", e);
            return ResponseEntity.badRequest()
                    .body(Map.of("error", e.getMessage()));
        }
    }

    // ==================== 대상자(일반유저)용 API ====================

    /**
     * 나에게 온 동의서 목록 (작성 대기 중)
     */
    @GetMapping("/my/pending")
    public ResponseEntity<?> getMyPendingList(Authentication auth) {
        String userId = auth.getName();
        List<ConsentAgreement> agreements = consentService.findPendingByTargetUser(userId);
        return ResponseEntity.ok(agreements);
    }

    /**
     * 내가 작성한 모든 동의서
     */
    @GetMapping("/my/list")
    public ResponseEntity<?> getMyConsentList(Authentication auth) {
        String userId = auth.getName();
        List<ConsentAgreement> agreements = consentService.findAllByTargetUser(userId);
        return ResponseEntity.ok(agreements);
    }

    /**
     * 내 동의서 완료 현황 조회 (대시보드용)
     */
    @GetMapping("/my/status")
    public ResponseEntity<?> getMyConsentStatus(Authentication auth) {
        String userId = auth.getName();
        Map<ConsentType, Boolean> status = consentService.getUserConsentStatus(userId);
        return ResponseEntity.ok(status);
    }

    // ==================== 공통 API (조회/작성) ====================

    /**
     * 동의서 상세 조회
     * - 권한에 따라 접근 제어됨
     */
    @GetMapping("/{agreementId}")
    public ResponseEntity<?> getAgreementDetail(
            @PathVariable Long agreementId,
            Authentication auth
    ) {
        try {
            String userId = auth.getName();
            ConsentAgreement agreement = consentService.getAgreementDetail(agreementId, userId);
            return ResponseEntity.ok(agreement);
        } catch (Exception e) {
            log.error("동의서 조회 실패: id={}", agreementId, e);
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 동의서 작성 완료 (제출)
     * - 대상자만 작성 가능
     */
    @PutMapping("/{agreementId}/complete")
    public ResponseEntity<?> completeConsent(
            @PathVariable Long agreementId,
            @RequestBody Map<String, String> request,
            Authentication auth
    ) {
        try {
            String userId = auth.getName();
            String formDataJson = request.get("formDataJson");

            if (formDataJson == null || formDataJson.isBlank()) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "작성 내용이 비어있습니다."));
            }

            consentService.completeConsent(agreementId, userId, formDataJson);

            return ResponseEntity.ok(Map.of(
                    "message", "동의서 제출이 완료되었습니다."
            ));
        } catch (Exception e) {
            log.error("동의서 제출 실패: id={}", agreementId, e);
            return ResponseEntity.badRequest()
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 동의서 발송 취소
     * - 발송자 또는 관리자만 취소 가능
     * - 아직 작성되지 않은 경우만
     */
    @DeleteMapping("/{agreementId}/cancel")
    public ResponseEntity<?> cancelConsent(
            @PathVariable Long agreementId,
            Authentication auth
    ) {
        try {
            String userId = auth.getName();
            consentService.cancelConsent(agreementId, userId);

            return ResponseEntity.ok(Map.of(
                    "message", "동의서 발송이 취소되었습니다."
            ));
        } catch (Exception e) {
            log.error("동의서 취소 실패: id={}", agreementId, e);
            return ResponseEntity.badRequest()
                    .body(Map.of("error", e.getMessage()));
        }
    }

    // ==================== 예외 처리 ====================

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<?> handleIllegalArgument(IllegalArgumentException e) {
        return ResponseEntity.badRequest()
                .body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<?> handleRuntimeException(RuntimeException e) {
        log.error("Runtime exception", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "서버 오류가 발생했습니다."));
    }

    /**
     * 동의서 HTML 템플릿 PDF 테스트
     * - 관리자만 접근 가능
     * - HTML을 PDF로 변환하여 다운로드
     */
    @PostMapping("/test-pdf")
    public ResponseEntity<?> testConsentPdf(
            @RequestBody Map<String, String> request,
            Authentication auth
    ) {
        try {
            String userId = auth.getName();

            // 관리자 권한 확인
            UserEntity user = userRepository.findByUserId(userId)
                    .orElseThrow(() -> new RuntimeException("사용자를 찾을 수 없습니다."));

            if (!user.isAdmin()) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(Map.of("error", "관리자만 접근 가능합니다."));
            }

            String htmlContent = request.get("htmlContent");
            String type = request.get("type");

            if (htmlContent == null || htmlContent.isBlank()) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "HTML 내용이 비어있습니다."));
            }

            // 변수 치환 (테스트용 더미 데이터)
            String testHtml = htmlContent
                    .replace("{{userName}}", "홍길동")
                    .replace("{{userId}}", "test123")
                    .replace("{{deptName}}", "정형외과")
                    .replace("{{phone}}", "010-1234-5678")
                    .replace("{{date}}", LocalDate.now().toString())
                    .replace("{{signature}}", "홍길동")  // 서명 텍스트
                    .replace("{{residentNumber}}", "123456-1234567")
                    .replace("{{email}}", "hong@example.com");

            // PDF 생성
            byte[] pdfBytes = ConsentPdfRenderer.render(testHtml);

            // HTTP 응답 헤더 설정
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_PDF);
            headers.setContentDisposition(
                    ContentDisposition.attachment()
                            .filename("test_consent_" + type + ".pdf", StandardCharsets.UTF_8)
                            .build()
            );

            return ResponseEntity.ok()
                    .headers(headers)
                    .body(pdfBytes);

        } catch (Exception e) {
            log.error("PDF 테스트 생성 실패", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "PDF 생성 중 오류 발생: " + e.getMessage()));
        }
    }
}