package sunhan.sunhanbackend.controller.approval;

import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import sunhan.sunhanbackend.dto.approval.ApprovalLineCreateDto;
import sunhan.sunhanbackend.dto.approval.ApprovalLineResponseDto;
import sunhan.sunhanbackend.dto.approval.ApprovalLineUpdateDto;
import sunhan.sunhanbackend.dto.approval.ApprovalStepResponseDto;
import sunhan.sunhanbackend.entity.mysql.approval.ApprovalLine;
import sunhan.sunhanbackend.enums.approval.ApproverType;
import sunhan.sunhanbackend.enums.approval.DocumentType;
import sunhan.sunhanbackend.repository.mysql.UserRepository;
import sunhan.sunhanbackend.service.approval.ApprovalLineService;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/approval-lines")
@RequiredArgsConstructor
@Slf4j
public class ApprovalLineController {

    private final ApprovalLineService approvalLineService;
    private final UserRepository userRepository;

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

            // ✅ Entity → DTO 변환 + 승인자 이름 조회
            List<ApprovalLineResponseDto> dtos = lines.stream()
                    .map(line -> {
                        ApprovalLineResponseDto dto = ApprovalLineResponseDto.fromEntity(line);

                        // 각 단계의 승인자 이름 설정
                        for (ApprovalStepResponseDto stepDto : dto.getSteps()) {
                            if (stepDto.getApproverType() == ApproverType.SPECIFIC_USER
                                    && stepDto.getApproverId() != null
                                    && !stepDto.getApproverId().isEmpty()) {

                                userRepository.findByUserId(stepDto.getApproverId())
                                        .ifPresent(user -> stepDto.setApproverName(user.getUserName()));
                            } else if (stepDto.getApproverType() == ApproverType.SUBSTITUTE) {
                                stepDto.setApproverName("(제출 시 선택)");
                            } else if (stepDto.getApproverType() == ApproverType.DEPARTMENT_HEAD) {
                                stepDto.setApproverName("(제출 시 선택)");
                            }
                        }

                        return dto;
                    })
                    .collect(Collectors.toList());

            return ResponseEntity.ok(dtos);
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

            // ✅ Entity → DTO 변환 + 승인자 이름 조회
            ApprovalLineResponseDto dto = ApprovalLineResponseDto.fromEntity(line);

            // ✅ 각 단계의 승인자 이름 설정
            for (ApprovalStepResponseDto stepDto : dto.getSteps()) {
                if (stepDto.getApproverType() == ApproverType.SPECIFIC_USER
                        && stepDto.getApproverId() != null
                        && !stepDto.getApproverId().isEmpty()) {

                    userRepository.findByUserId(stepDto.getApproverId())
                            .ifPresent(user -> stepDto.setApproverName(user.getUserName()));
                } else if (stepDto.getApproverType() == ApproverType.SUBSTITUTE) {
                    stepDto.setApproverName("(제출 시 선택)");
                } else if (stepDto.getApproverType() == ApproverType.DEPARTMENT_HEAD) {
                    stepDto.setApproverName("(제출 시 선택)");
                }
            }

            return ResponseEntity.ok(dto);
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
}