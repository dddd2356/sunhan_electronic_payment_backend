package sunhan.sunhanbackend.controller.approval;

import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import sunhan.sunhanbackend.dto.approval.ApprovalLineCreateDto;
import sunhan.sunhanbackend.dto.approval.ApprovalLineUpdateDto;
import sunhan.sunhanbackend.entity.mysql.approval.ApprovalLine;
import sunhan.sunhanbackend.enums.approval.ApproverType;
import sunhan.sunhanbackend.enums.approval.DocumentType;
import sunhan.sunhanbackend.service.approval.ApprovalLineService;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/approval-lines")
@RequiredArgsConstructor
@Slf4j
public class ApprovalLineController {

    private final ApprovalLineService approvalLineService;

    /**
     * 결재라인 생성
     */
    @PostMapping
    public ResponseEntity<?> createApprovalLine(
            @RequestBody ApprovalLineCreateDto dto,
            Authentication auth
    ) {
        try {
            String userId = auth.getName();
            ApprovalLine created = approvalLineService.createApprovalLine(dto, userId);
            return ResponseEntity.status(HttpStatus.CREATED).body(created);
        } catch (Exception e) {
            log.error("결재라인 생성 실패", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 문서 타입별 결재라인 목록 조회
     */
    @GetMapping
    public ResponseEntity<?> getApprovalLines(
            @RequestParam DocumentType documentType,
            Authentication auth
    ) {
        try {
            String userId = auth.getName();
            List<ApprovalLine> lines = approvalLineService.getAvailableApprovalLines(documentType, userId);
            return ResponseEntity.ok(lines);
        } catch (Exception e) {
            log.error("결재라인 조회 실패", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 결재라인 상세 조회
     */
    @GetMapping("/{id}")
    public ResponseEntity<?> getApprovalLineDetail(@PathVariable Long id) {
        try {
            ApprovalLine line = approvalLineService.getApprovalLineDetail(id);
            return ResponseEntity.ok(line);
        } catch (EntityNotFoundException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("결재라인 상세 조회 실패", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/my")
    public ResponseEntity<?> getMyApprovalLines(@RequestParam(required = false) DocumentType documentType, Authentication auth) {
        if (auth == null || !auth.isAuthenticated()) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        String userId = auth.getName();
        try {
            List<ApprovalLine> lines = approvalLineService.getApprovalLinesByCreator(documentType, userId);
            return ResponseEntity.ok(lines);
        } catch (Exception e) {
            log.error("내 결재라인 조회 실패", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", e.getMessage()));
        }
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> updateApprovalLine(@PathVariable Long id, @RequestBody ApprovalLineUpdateDto dto, Authentication auth) {
        if (auth == null || !auth.isAuthenticated()) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        String userId = auth.getName();
        try {
            ApprovalLine updated = approvalLineService.updateApprovalLine(id, dto, userId);
            return ResponseEntity.ok(updated);
        } catch (SecurityException se) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", se.getMessage()));
        } catch (EntityNotFoundException enfe) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", enfe.getMessage()));
        } catch (Exception e) {
            log.error("결재라인 수정 실패", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", e.getMessage()));
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteApprovalLine(@PathVariable Long id, Authentication auth) {
        if (auth == null || !auth.isAuthenticated()) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        String userId = auth.getName();
        try {
            approvalLineService.deleteApprovalLine(id, userId);
            return ResponseEntity.ok(Map.of("message", "삭제(비활성화) 완료"));
        } catch (SecurityException se) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", se.getMessage()));
        } catch (EntityNotFoundException enfe) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", enfe.getMessage()));
        } catch (Exception e) {
            log.error("결재라인 삭제 실패", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", e.getMessage()));
        }
    }
    /**
     *  새로운 엔드포인트: 결재라인 생성 시 타입별 후보 승인자 목록 조회
     */
    @GetMapping("/candidates")
    public ResponseEntity<?> getApproverCandidates(
            @RequestParam ApproverType approverType,
            Authentication auth
    ) {
        try {
            String userId = auth.getName(); // 항상 로그인된 사용자 사용
            // applicantId는 외부 파라미터가 아니라 로그인 사용자로 고정해서 서비스 호출
            List<Map<String, Object>> candidates = approvalLineService.getApproverCandidates(
                    approverType,
                    userId,   // applicantId (항상 로그인 사용자)
                    userId    // requesterId (동일하게 로그인 사용자)
            );
            return ResponseEntity.ok(candidates);
        } catch (IllegalArgumentException e) {
            log.warn("후보 조회 요청 파라미터 오류: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", e.getMessage()));
        } catch (EntityNotFoundException e) {
            log.warn("엔티티 없음: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("승인자 후보 조회 실패", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "서버 오류가 발생했습니다."));
        }
    }
}