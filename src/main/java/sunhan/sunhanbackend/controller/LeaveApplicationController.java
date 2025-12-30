package sunhan.sunhanbackend.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityNotFoundException;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import sunhan.sunhanbackend.dto.request.*;
import sunhan.sunhanbackend.dto.response.AttachmentResponseDto;
import sunhan.sunhanbackend.dto.response.LeaveApplicationResponseDto;
import sunhan.sunhanbackend.entity.mysql.LeaveApplication;
import sunhan.sunhanbackend.entity.mysql.UserEntity;
import sunhan.sunhanbackend.enums.LeaveApplicationStatus;
import sunhan.sunhanbackend.enums.PermissionType;
import sunhan.sunhanbackend.service.LeaveApplicationService;
import sunhan.sunhanbackend.service.PermissionService;
import sunhan.sunhanbackend.service.UserService;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/leave-application")
@RequiredArgsConstructor
@Slf4j
public class LeaveApplicationController {

    private final LeaveApplicationService leaveApplicationService;
    private final UserService userService;
    private final ObjectMapper objectMapper; // ObjectMapper 주입
    private final PermissionService permissionService;


    /**
     * 내 휴가원 목록 조회
     */
    /**
     * 조회(목록): 페이지네이션 적용 (기본 page=0, size=20, size 최대 100) 이걸로 많이 줄음
     */
    @GetMapping("/my")
    public ResponseEntity<List<LeaveApplicationResponseDto>> getMyApplications(
            Authentication auth,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size
    ) {
        String userId = auth.getName();
        try {
            int safeSize = Math.max(1, Math.min(size, 100));
            Pageable pageable = PageRequest.of(Math.max(0, page), safeSize, Sort.by(Sort.Direction.DESC, "createdAt"));

            Page<LeaveApplicationResponseDto> result = leaveApplicationService.getMyApplications(userId, pageable);

            List<LeaveApplicationResponseDto> content = (result != null) ? result.getContent() : Collections.emptyList();

            // (선택) 클라이언트에서 페이징을 사용할 수 있게 총개수를 헤더로 추가
            HttpHeaders headers = new HttpHeaders();
            headers.add("X-Total-Count", String.valueOf(result != null ? result.getTotalElements() : 0L));

            return new ResponseEntity<>(content, headers, HttpStatus.OK);
        } catch (Exception e) {
            log.error("내 휴가원 목록 조회 실패: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * 내가 승인해야 할 휴가원 목록 조회
     */
    @GetMapping("/pending/me")
    public ResponseEntity<Page<LeaveApplicationResponseDto>> getPendingApplicationsForMe(
            Authentication auth,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size
    ) {
        String approverId = auth.getName();
        try {
            int safeSize = Math.max(1, Math.min(size, 100));
            Pageable pageable = PageRequest.of(Math.max(0, page), safeSize, Sort.by(Sort.Direction.DESC, "createdAt"));

            Page<LeaveApplicationResponseDto> result = leaveApplicationService.getPendingApplicationsForMe(approverId, pageable);

            HttpHeaders headers = new HttpHeaders();
            headers.add("X-Total-Count", String.valueOf(result.getTotalElements()));

            return new ResponseEntity<>(result, headers, HttpStatus.OK);
        } catch (Exception e) {
            log.error("승인 대기 휴가원 목록 조회 실패: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * 휴가원 상세 조회
     */
    @GetMapping("/{id}")
    public ResponseEntity<?> getLeaveApplicationDetail(
            @PathVariable Long id,
            Authentication auth) {
        try {
            String userId = auth.getName();
            LeaveApplicationResponseDto dto = leaveApplicationService.getLeaveApplicationDetail(id, userId);
            return ResponseEntity.ok(dto);
        } catch (EntityNotFoundException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("휴가원 상세 조회 실패: id={}, error={}", id, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "휴가원 상세 조회 중 오류가 발생했습니다."));
        }
    }

    /**
     * 대직자 지정 전용 엔드포인트
     */
    @PutMapping("/{id}/substitute")
    public ResponseEntity<?> updateSubstitute(
            @PathVariable Long id,
            @RequestBody LeaveApplicationUpdateFormRequestDto.SubstituteInfo substituteDto,
            Authentication auth) {
        String userId = auth.getName();
        try {
            // 1) 본인이 신청자여야 합니다.
            LeaveApplication app = leaveApplicationService.getLeaveApplicationEntity(id);
            if (!app.getApplicantId().equals(userId)) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(Map.of("error", "신청자만 대직자를 지정할 수 있습니다."));
            }

            // 2) 서비스에 대직자 아이디 세팅 위임
            leaveApplicationService.setSubstitute(id, substituteDto.getUserId());

            return ResponseEntity.ok().build();
        } catch (EntityNotFoundException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "휴가원을 찾을 수 없습니다."));
        } catch (Exception e) {
            log.error("대직자 지정 실패: id={}, substitute={}", id, substituteDto, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "대직자 지정 중 오류가 발생했습니다."));
        }
    }

    /**
     * 휴가원 생성 (드래프트)
     */
    @PostMapping
    public ResponseEntity<LeaveApplicationResponseDto> createLeaveApplication(Authentication auth) {
        String applicantId = auth.getName();
        LeaveApplication application = leaveApplicationService.createLeaveApplication(applicantId);
        // 생성 후 상세 정보 조회를 위해 fromEntity를 사용하여 DTO로 변환
        UserEntity applicant = userService.getUserInfo(applicantId); // 또는 userRepository.findByUserId(applicantId).orElseThrow()
        return ResponseEntity.status(HttpStatus.CREATED).body(LeaveApplicationResponseDto.fromEntity(application, applicant, null));
    }

    /**
     * 휴가원 제출 - 결재라인 포함 버전
     */
    @PostMapping("/{id}/submit")
    public ResponseEntity<?> submitLeaveApplication(
            @PathVariable Long id,
            @RequestBody(required = false) Map<String, Object> requestBody,
            Authentication auth) {
        try {
            String userId = auth.getName();

            // 결재라인 ID 추출 (선택적)
            Long approvalLineId = null;
            if (requestBody != null && requestBody.containsKey("approvalLineId")) {
                Object lineIdObj = requestBody.get("approvalLineId");
                if (lineIdObj != null) {
                    if (lineIdObj instanceof Number) {
                        approvalLineId = ((Number) lineIdObj).longValue();
                    } else if (lineIdObj instanceof String) {
                        try {
                            approvalLineId = Long.parseLong((String) lineIdObj);
                        } catch (NumberFormatException e) {
                            log.warn("Invalid approvalLineId format: {}", lineIdObj);
                        }
                    }
                }
            }

            // 서비스 호출
            LeaveApplication submittedApplication = leaveApplicationService.submitLeaveApplication(
                    id,
                    approvalLineId,
                    userId
            );

            UserEntity applicant = userService.getUserInfo(submittedApplication.getApplicantId());
            UserEntity substitute = (submittedApplication.getSubstituteId() != null) ?
                    userService.getUserInfo(submittedApplication.getSubstituteId()) : null;

            return ResponseEntity.ok(LeaveApplicationResponseDto.fromEntity(
                    submittedApplication,
                    applicant,
                    substitute
            ));
        } catch (EntityNotFoundException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "제출할 휴가원을 찾을 수 없거나 권한이 없습니다."));
        } catch (IllegalStateException | AccessDeniedException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("error", e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("휴가원 제출 실패: id={}", id, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "제출 중 오류가 발생했습니다."));
        }
    }

    /**
     * 휴가원 승인
     */
    @PutMapping("/{id}/approve")
    public ResponseEntity<?> approveLeaveApplication(@PathVariable Long id,  @RequestBody Map<String, String> requestBody, Authentication auth) {
        String approverId = auth.getName();
        String signatureDate = requestBody.get("signatureDate");
        String signatureImageUrl = requestBody.get("signatureImageUrl");
        try {
            LeaveApplication application = leaveApplicationService.approveLeaveApplication(id, approverId, signatureDate, signatureImageUrl);
            UserEntity applicant = userService.getUserInfo(application.getApplicantId());
            UserEntity substitute = (application.getSubstituteId() != null) ?
                    userService.getUserInfo(application.getSubstituteId()) : null;
            return ResponseEntity.ok(LeaveApplicationResponseDto.fromEntity(application, applicant, substitute));
        } catch (EntityNotFoundException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "휴가원을 찾을 수 없습니다."));
        } catch (AccessDeniedException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("휴가원 승인 실패: id={}", id, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", "휴가원 승인 중 오류가 발생했습니다."));
        }
    }

    /**
     * 휴가원 반려
     */
    @PutMapping("/{id}/reject")
    public ResponseEntity<?> rejectLeaveApplication(
            @PathVariable Long id,
            Authentication auth,
            @RequestBody Map<String, String> requestBody) {
        String approverId = auth.getName();
        String rejectionReason = requestBody.get("rejectionReason");
        if (rejectionReason == null || rejectionReason.trim().isEmpty()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", "반려 사유는 필수입니다."));
        }
        try {
            LeaveApplication application = leaveApplicationService.rejectLeaveApplication(id, approverId, rejectionReason);
            UserEntity applicant = userService.getUserInfo(application.getApplicantId());
            UserEntity substitute = (application.getSubstituteId() != null) ?
                    userService.getUserInfo(application.getSubstituteId()) : null;
            return ResponseEntity.ok(LeaveApplicationResponseDto.fromEntity(application, applicant, substitute));
        } catch (EntityNotFoundException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "휴가원을 찾을 수 없습니다."));
        } catch (AccessDeniedException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("휴가원 반려 실패: id={}", id, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", "휴가원 반려 중 오류가 발생했습니다."));
        }
    }

    /**
     * 휴가원 삭제
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteLeaveApplication(@PathVariable Long id, Authentication auth) {
        String userId = auth.getName();
        try {
            leaveApplicationService.deleteLeaveApplication(id, userId);
            return ResponseEntity.ok(Map.of("message", "휴가원이 성공적으로 삭제되었습니다."));
        } catch (EntityNotFoundException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "휴가원을 찾을 수 없습니다."));
        } catch (IllegalStateException | AccessDeniedException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("휴가원 삭제 실패: id={}", id, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", "휴가원 삭제 중 오류가 발생했습니다."));
        }
    }

    /**
     * 휴가원 서명 (신청자, 대직자, 각 승인자)
     * 이 엔드포인트는 ContractFormData.SignatureEntry를 포함하는 요청을 받습니다.
     */
    @PutMapping("/{id}/sign")
    public ResponseEntity<?> signLeaveApplication(
            @PathVariable Long id,
            @RequestBody @Valid SignLeaveApplicationRequestDto request,
            Authentication auth) {
        log.info("서명 요청 받음 - ID: {}, Request: {}", id, request);
        try {
            // 요청의 signerId와 실제 인증된 사용자의 ID를 비교하여 보안 강화 (필요 시)
            if (!auth.getName().equals(request.getSignerId())) {
                log.warn("Unauthorized signing attempt: authenticated user {} tried to sign as {}", auth.getName(), request.getSignerId());
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "인증된 사용자와 서명 요청 ID가 일치하지 않습니다."));
            }

            LeaveApplicationResponseDto signedApplication = leaveApplicationService.signLeaveApplication(id, request);
            return ResponseEntity.ok(signedApplication);
        } catch (EntityNotFoundException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "휴가원을 찾을 수 없습니다."));
        } catch (AccessDeniedException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("휴가원 서명 실패: id={}, signer={}", id, request.getSignerId(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", "휴가원 서명 중 오류가 발생했습니다."));
        }
    }

    /**
     * 휴가원 서명 정보 조회 (formDataJson에서 파싱)
     * 이 메서드는 프론트엔드에서 각 서명 필드를 채우는 데 사용될 수 있습니다.
     */
    @GetMapping("/{id}/signatures")
    public ResponseEntity<Map<String, Object>> getLeaveApplicationSignatures(@PathVariable Long id) {
        try {
            LeaveApplication application = leaveApplicationService.getLeaveApplicationEntity(id);

            Map<String, Object> response = new HashMap<>();

            // 서명 데이터를 안전하게 파싱
            Map<String, List<Map<String, Object>>> signatures = new HashMap<>();

            try {
                if (application.getFormDataJson() != null && !application.getFormDataJson().isEmpty()) {
                    ObjectMapper objectMapper = new ObjectMapper();
                    Map<String, Object> formData = objectMapper.readValue(
                            application.getFormDataJson(),
                            new TypeReference<Map<String, Object>>() {}
                    );

                    Object signaturesObject = formData.get("signatures");
                    if (signaturesObject != null) {
                        signatures = objectMapper.convertValue(
                                signaturesObject,
                                new TypeReference<Map<String, List<Map<String, Object>>>>() {}
                        );
                    }
                }
            } catch (Exception e) {
                log.warn("서명 데이터 파싱 실패, 기본값 사용: {}", e.getMessage());
                signatures = new HashMap<>();
            }

            // 각 서명 타입별로 안전하게 초기화
            String[] signatureTypes = {"applicant", "substitute", "departmentHead",
                    "hrStaff", "centerDirector", "adminDirector", "ceoDirector"};

            for (String type : signatureTypes) {
                if (!signatures.containsKey(type) || signatures.get(type) == null || signatures.get(type).isEmpty()) {
                    List<Map<String, Object>> defaultSignature = new ArrayList<>();
                    Map<String, Object> signatureEntry = new HashMap<>();
                    signatureEntry.put("text", "");
                    signatureEntry.put("imageUrl", null);
                    signatureEntry.put("isSigned", false);
                    defaultSignature.add(signatureEntry);
                    signatures.put(type, defaultSignature);
                } else {
                    // 기존 서명 데이터의 null 값 처리
                    List<Map<String, Object>> signatureList = signatures.get(type);
                    for (Map<String, Object> sig : signatureList) {
                        if (sig.get("text") == null) sig.put("text", "");
                        if (sig.get("imageUrl") == null) sig.put("imageUrl", null);
                        if (sig.get("isSigned") == null) sig.put("isSigned", false);
                    }
                }
            }

            // null 값이 없는 안전한 Map 생성
            Map<String, Object> safeResponse = new HashMap<>();
            safeResponse.put("signatures", signatures);
            safeResponse.put("isApplicantSigned", application.getIsApplicantSigned());
            safeResponse.put("isSubstituteApproved", application.getIsSubstituteApproved());
            safeResponse.put("isDeptHeadApproved", application.getIsDeptHeadApproved());
            safeResponse.put("isHrStaffApproved", application.getIsHrStaffApproved());
            safeResponse.put("isCenterDirectorApproved", application.getIsCenterDirectorApproved());
            safeResponse.put("isAdminDirectorApproved", application.getIsAdminDirectorApproved());
            safeResponse.put("isCeoDirectorApproved", application.getIsCeoDirectorApproved());

            return ResponseEntity.ok(safeResponse);

        } catch (Exception e) {
            log.error("휴가원 서명 조회 실패: id={}", id, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "서명 정보 조회에 실패했습니다."));
        }
    }

    /**
     * 휴가원 전결 승인
     */
    @PutMapping("/{id}/final-approve")
    public ResponseEntity<?> finalApproveLeaveApplication(@PathVariable Long id, Authentication auth) {
        String approverId = auth.getName();
        try {
            LeaveApplication application = leaveApplicationService.finalApproveLeaveApplication(id, approverId);
            UserEntity applicant = userService.getUserInfo(application.getApplicantId());
            UserEntity substitute = (application.getSubstituteId() != null) ?
                    userService.getUserInfo(application.getSubstituteId()) : null;
            return ResponseEntity.ok(LeaveApplicationResponseDto.fromEntity(application, applicant, substitute));
        } catch (EntityNotFoundException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "휴가원을 찾을 수 없습니다."));
        } catch (AccessDeniedException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("휴가원 전결 승인 실패: id={}", id, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", "휴가원 전결 승인 중 오류가 발생했습니다."));
        }
    }

    /**
     * 휴가원 PDF 다운로드
     */
    @GetMapping("/{id}/pdf")
    public ResponseEntity<byte[]> downloadLeaveApplicationPdf(@PathVariable Long id) {
        try {
            log.info("PDF 다운로드 요청: id={}", id);

            // 먼저 휴가원이 존재하는지 확인
            LeaveApplication application = leaveApplicationService.getLeaveApplicationEntity(id);
            if (application == null) {
                log.warn("휴가원을 찾을 수 없음: id={}", id);
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                        .body(("{\"error\":\"휴가원을 찾을 수 없습니다.\"}").getBytes());
            }

            // 신청자 ID가 유효한지 확인
            if (application.getApplicantId() == null || application.getApplicantId().trim().isEmpty()) {
                log.warn("신청자 ID가 유효하지 않음: id={}, applicantId={}", id, application.getApplicantId());
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                        .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                        .body(("{\"error\":\"신청자 정보가 유효하지 않습니다.\"}").getBytes());
            }

            byte[] data = leaveApplicationService.getLeaveApplicationPdfBytes(id);

            log.info("PDF 생성 완료: id={}, size={}bytes", id, data.length);

            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_PDF)
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"leave_application_" + id + ".pdf\"")
                    .body(data);

        } catch (EntityNotFoundException e) {
            log.warn("휴가원을 찾을 수 없음: id={}, error={}", id, e.getMessage());
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .body(("{\"error\":\"" + e.getMessage() + "\"}").getBytes());
        } catch (IllegalStateException e) {
            log.warn("잘못된 상태: id={}, error={}", id, e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .body(("{\"error\":\"" + e.getMessage() + "\"}").getBytes());
        } catch (IllegalArgumentException e) {
            log.warn("잘못된 인수: id={}, error={}", id, e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .body(("{\"error\":\"" + e.getMessage() + "\"}").getBytes());
        } catch (Exception e) {
            log.error("휴가원 PDF 다운로드 실패: id={}, error={}", id, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .body(("{\"error\":\"PDF 생성에 실패했습니다: " + e.getMessage() + "\"}").getBytes());
        }
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> saveLeaveApplication(
            @PathVariable Long id,
            @RequestBody LeaveApplicationUpdateFormRequestDto  updateDto,
            Authentication auth) {
        try {
            String userId = auth.getName();

            // 안전한 saveLeaveApplication 서비스 메서드를 호출합니다.
            LeaveApplication updatedApplication = leaveApplicationService.saveLeaveApplication(id, userId, updateDto);

            UserEntity applicant = userService.getUserInfo(updatedApplication.getApplicantId());
            UserEntity substitute = (updatedApplication.getSubstituteId() != null) ?
                    userService.getUserInfo(updatedApplication.getSubstituteId()) : null;

            return ResponseEntity.ok(LeaveApplicationResponseDto.fromEntity(updatedApplication, applicant, substitute));
        } catch (EntityNotFoundException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "휴가원을 찾을 수 없거나 수정 권한이 없습니다."));
        } catch (IllegalStateException | IllegalArgumentException | AccessDeniedException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("휴가원 임시저장 실패: id={}", id, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", "임시저장 중 오류가 발생했습니다."));
        }
    }

    @PutMapping("/{id}/signature/{signatureType}")
    public ResponseEntity<LeaveApplicationResponseDto> updateSignature(
            @PathVariable Long id,
            @PathVariable String signatureType,
            @RequestBody(required = false) Map<String, Object> signatureData,
            Authentication auth) {

        String userId = auth.getName();
        LeaveApplication application = leaveApplicationService.getLeaveApplicationEntity(id);

        // ✅ 현재 사용자가 서명할 권한이 있는지 확인
        if (!canSignAtPosition(id, userId, signatureType)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(null);
        }

        // ✅ 이미 다른 사용자가 서명한 경우 덮어쓰기 방지
        try {
            Map<String, Object> formData = objectMapper.readValue(
                    application.getFormDataJson(),
                    new TypeReference<Map<String, Object>>() {}
            );

            Map<String, List<Map<String, Object>>> signatures =
                    (Map<String, List<Map<String, Object>>>) formData.get("signatures");

            if (signatures != null && signatures.containsKey(signatureType)) {
                List<Map<String, Object>> existingSigs = signatures.get(signatureType);
                if (existingSigs != null && !existingSigs.isEmpty()) {
                    Map<String, Object> existingSig = existingSigs.get(0);
                    Boolean isSigned = (Boolean) existingSig.get("isSigned");
                    String existingSignerId = (String) existingSig.get("signerId");

                    // ✅ 이미 다른 사람이 서명했으면 거부
                    if (Boolean.TRUE.equals(isSigned) && existingSignerId != null && !existingSignerId.equals(userId)) {
                        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                                .body(null);
                    }
                }
            }
        } catch (Exception e) {
            log.error("서명 검증 실패", e);
        }

        // null 또는 서명 취소 요청 처리
        if (signatureData == null || signatureData.isEmpty() ||
                signatureData.get("imageUrl") == null) {
            Map<String, Object> cancelSignatureData = new HashMap<>();
            cancelSignatureData.put("text", "");
            cancelSignatureData.put("imageUrl", null);
            cancelSignatureData.put("isSigned", false);
            signatureData = cancelSignatureData;
        }

        try {
            LeaveApplication updated = leaveApplicationService.updateSignature(id, userId, signatureType, signatureData);
            UserEntity applicant = userService.getUserInfo(updated.getApplicantId());
            UserEntity substitute = (updated.getSubstituteId() != null)
                    ? userService.getUserInfo(updated.getSubstituteId())
                    : null;
            LeaveApplicationResponseDto dto = LeaveApplicationResponseDto.fromEntity(updated, applicant, substitute);
            return ResponseEntity.ok(dto);
        } catch (Exception e) {
            log.error("서명 업데이트 실패: id={}, signatureType={}, userId={}", id, signatureType, userId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * 완료된 휴가원 목록 조회 (모든 사용자용 - 권한에 따라 조회 범위 제한)
     */
    @GetMapping("/completed")
    public ResponseEntity<Page<LeaveApplicationResponseDto>> getCompletedApplications(
            Authentication auth,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size
    ) {
        String userId = auth.getName();
        try {
            int safeSize = Math.max(1, Math.min(size, 100));
            Pageable pageable = PageRequest.of(Math.max(0, page), safeSize, Sort.by(Sort.Direction.DESC, "createdAt"));

            Page<LeaveApplicationResponseDto> result = leaveApplicationService.getCompletedApplications(userId, pageable);

            HttpHeaders headers = new HttpHeaders();
            headers.add("X-Total-Count", String.valueOf(result.getTotalElements()));

            return new ResponseEntity<>(result, headers, HttpStatus.OK);
        } catch (Exception e) {
            log.error("완료된 휴가원 목록 조회 실패: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * 완료된 휴가원 조회 (신청자 본인 전용)
     */
    @GetMapping("/completed/me")
    public ResponseEntity<Page<LeaveApplicationResponseDto>> getMyCompletedApplications(
            Authentication auth,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size
    ) {
        String userId = auth.getName();
        try {
            int safeSize = Math.max(1, Math.min(size, 100));
            Pageable pageable = PageRequest.of(Math.max(0, page), safeSize, Sort.by(Sort.Direction.DESC, "createdAt"));

            Page<LeaveApplicationResponseDto> result = leaveApplicationService.getCompletedApplicationsByApplicant(userId, pageable);

            HttpHeaders headers = new HttpHeaders();
            headers.add("X-Total-Count", String.valueOf(result.getTotalElements()));

            return new ResponseEntity<>(result, headers, HttpStatus.OK);
        } catch (Exception e) {
            log.error("본인 완료된 휴가원 조회 실패: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * 서명 권한 체크 메서드
     */
    private boolean canSignAtPosition(Long applicationId, String userId, String signatureType) {
        try {
            LeaveApplication application = leaveApplicationService.getLeaveApplicationEntity(applicationId);
            UserEntity user = userService.getUserInfo(userId);

            // ✅ 신청자 서명은 항상 DRAFT 상태에서만 가능 (결재라인과 무관)
            if ("applicant".equals(signatureType)) {
                return userId.equals(application.getApplicantId()) &&
                        application.getStatus() == LeaveApplicationStatus.DRAFT;
            }

            // ✅ DRAFT 상태에서는 신청자 외 다른 서명 불가
            if (application.getStatus() == LeaveApplicationStatus.DRAFT) {
                return false;
            }

            // ✅ 결재라인 기반인 경우 currentApproverId로 판단
            if (application.isUsingApprovalLine()) {
                return userId.equals(application.getCurrentApproverId());
            }

            return false;
        } catch (Exception e) {
            log.error("서명 권한 체크 실패: applicationId={}, userId={}, signatureType={}",
                    applicationId, userId, signatureType, e);
            return false;
        }
    }

    /**
     * 부서 관리 권한 체크 (부서장이 해당 부서 직원을 관리할 수 있는지)
     */
    private boolean canManageDepartment(String managerId, String employeeId) {
        try {
            UserEntity manager = userService.getUserInfo(managerId);
            UserEntity employee = userService.getUserInfo(employeeId);

            // 같은 부서이면서 관리자가 부서장 권한이 있는 경우
            return manager.getDeptCode().equals(employee.getDeptCode()) &&
                    manager.getJobLevel() != null &&
                    Integer.parseInt(manager.getJobLevel()) >= 1;

        } catch (Exception e) {
            log.error("부서 관리 권한 체크 실패: managerId={}, employeeId={}", managerId, employeeId, e);
            return false;
        }
    }

    /**
     * 대직자 후보 목록 조회
     * 현재 사용자와 같은 부서의 직원들을 반환
     */
    @GetMapping("/substitute-candidates")
    public ResponseEntity<?> getSubstituteCandidates(Authentication auth) {
        if (auth == null || !auth.isAuthenticated()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        String userId = auth.getName();

        try {
            UserEntity currentUser = userService.getUserInfo(userId);
            if (currentUser == null) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "사용자 정보를 찾을 수 없습니다."));
            }

            List<UserEntity> allInDept = userService.getActiveUsersByDeptCode(currentUser.getUserId(), currentUser.getDeptCode());

            List<Map<String, Object>> dto = allInDept.stream()
                    .filter(u -> u.getUserId() != null && !u.getUserId().equals(userId))
                    .map(u -> {
                        Map<String, Object> m = new HashMap<>();
                        m.put("userId", u.getUserId());
                        m.put("userName", u.getUserName());
                        m.put("jobLevel", u.getJobLevel());
                        return m;
                    })
                    .collect(Collectors.toList());
            return ResponseEntity.ok(dto);
        } catch (Exception e) {
            log.error("getSubstituteCandidates failed for {}: {}", userId, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", "대직자 후보 조회 중 오류가 발생했습니다."));
        }
    }

    /**
     * 휴가원 첨부파일 업로드
     */
    @PostMapping("/{id}/attachments")
    public ResponseEntity<?> uploadAttachment(
            @PathVariable Long id,
            @RequestParam("file") MultipartFile file,
            Authentication auth) {
        try {
            String userId = auth.getName();
            AttachmentResponseDto attachmentDto = leaveApplicationService.addAttachment(id, userId, file);
            return ResponseEntity.status(HttpStatus.CREATED).body(attachmentDto);
        } catch (AccessDeniedException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("첨부파일 업로드 실패: leaveApplicationId={}, fileName={}", id, file.getOriginalFilename(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", "파일 업로드 중 오류가 발생했습니다."));
        }
    }

    /**
     * 휴가원 첨부파일 삭제
     */
    @DeleteMapping("/{id}/attachments/{attachmentId}")
    public ResponseEntity<?> deleteAttachment(
            @PathVariable Long id,
            @PathVariable Long attachmentId,
            Authentication auth) {
        try {
            String userId = auth.getName();
            leaveApplicationService.deleteAttachment(id, attachmentId, userId);
            return ResponseEntity.ok(Map.of("message", "첨부파일이 성공적으로 삭제되었습니다."));
        } catch (AccessDeniedException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", e.getMessage()));
        } catch (EntityNotFoundException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("첨부파일 삭제 실패: leaveApplicationId={}, attachmentId={}", id, attachmentId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", "파일 삭제 중 오류가 발생했습니다."));
        }
    }

    /**
     * 휴가원 첨부파일 다운로드
     */
    @GetMapping("/attachments/{attachmentId}/download")
    public ResponseEntity<Resource> downloadAttachment(@PathVariable Long attachmentId) {
        try {
            // 파일 정보를 먼저 조회하여 원본 파일 이름을 가져옴
            String originalFileName = leaveApplicationService.getAttachmentInfo(attachmentId).getOriginalFileName();
            Resource resource = leaveApplicationService.loadFileAsResource(attachmentId);

            // 한글 파일 이름이 깨지지 않도록 Content-Disposition 헤더 설정
            ContentDisposition contentDisposition = ContentDisposition.builder("attachment")
                    .filename(originalFileName, StandardCharsets.UTF_8)
                    .build();

            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_OCTET_STREAM) // 범용적인 이진 파일 타입
                    .header(HttpHeaders.CONTENT_DISPOSITION, contentDisposition.toString())
                    .body(resource);

        } catch (EntityNotFoundException e) {
            log.error("다운로드할 파일을 찾을 수 없음: attachmentId={}", attachmentId, e);
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        } catch (Exception e) {
            log.error("파일 다운로드 실패: attachmentId={}", attachmentId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * ✅ 결재라인 기반 반려
     */
    @PutMapping("/{id}/reject-with-line")
    public ResponseEntity<?> rejectWithApprovalLine(
            @PathVariable Long id,
            @RequestBody Map<String, String> request,
            Authentication auth
    ) {
        try {
            String approverId = auth.getName();
            String rejectionReason = request.get("rejectionReason");

            if (rejectionReason == null || rejectionReason.trim().isEmpty()) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "반려 사유를 입력해주세요."));
            }

            LeaveApplication application = leaveApplicationService.rejectWithApprovalLine(
                    id,
                    approverId,
                    rejectionReason
            );

            return ResponseEntity.ok(application);
        } catch (Exception e) {
            log.error("결재라인 반려 실패", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", e.getMessage()));
        }
    }
    /**
     * ✅ 완료된 휴가원 취소 (인사권한자 전용)
     */
    @PutMapping("/{id}/cancel-approved")
    public ResponseEntity<?> cancelApprovedLeaveApplication(
            @PathVariable Long id,
            @RequestBody Map<String, String> request,
            Authentication auth
    ) {
        try {
            String userId = auth.getName();
            String cancellationReason = request.get("cancellationReason");

            if (cancellationReason == null || cancellationReason.trim().isEmpty()) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "취소 사유를 입력해주세요."));
            }

            LeaveApplication application = leaveApplicationService.cancelApprovedLeaveApplication(
                    id,
                    userId,
                    cancellationReason
            );

            return ResponseEntity.ok(application);
        } catch (AccessDeniedException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("완료된 휴가원 취소 실패", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * ✅ 현재 사용자가 해당 휴가원을 전결 승인할 수 있는지 확인
     */
    @GetMapping("/{id}/can-final-approve")
    public ResponseEntity<?> canFinalApprove(
            @PathVariable Long id,
            Authentication auth
    ) {
        try {
            String userId = auth.getName();

            LeaveApplication application = leaveApplicationService.getLeaveApplicationEntity(id);

            // 1. 현재 승인자가 맞는지 확인
            if (!userId.equals(application.getCurrentApproverId())) {
                return ResponseEntity.ok(Map.of("canFinalApprove", false));
            }

            // 2. 전결 권한 확인
            boolean canFinalApprove = permissionService.getAllUserPermissions(userId)
                    .stream()
                    .anyMatch(p ->
                            p == PermissionType.FINAL_APPROVAL_ALL ||
                                    p == PermissionType.FINAL_APPROVAL_LEAVE_APPLICATION
                    );

            return ResponseEntity.ok(Map.of("canFinalApprove", canFinalApprove));

        } catch (Exception e) {
            log.error("전결 권한 확인 실패", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    // ✅ 기존 /approve-with-line에서 isFinalApproval 파라미터를 사용하므로
// 추가 작업 없이 권한 체크만 추가하면 됨

    @PutMapping("/{id}/approve-with-line")
    public ResponseEntity<?> approveWithApprovalLine(
            @PathVariable Long id,
            @RequestBody Map<String, Object> request,
            Authentication auth
    ) {
        try {
            String approverId = auth.getName();
            String comment = (String) request.getOrDefault("comment", "");
            String signatureImageUrl = (String) request.get("signatureImageUrl");
            boolean isFinalApproval = Boolean.TRUE.equals(request.get("isFinalApproval"));

            // ✅ [추가] 전결 시도 시 권한 검증
            if (isFinalApproval) {
                boolean canFinalApprove = permissionService.getAllUserPermissions(approverId)
                        .stream()
                        .anyMatch(p ->
                                p == PermissionType.FINAL_APPROVAL_ALL ||
                                        p == PermissionType.FINAL_APPROVAL_LEAVE_APPLICATION
                        );

                if (!canFinalApprove) {
                    return ResponseEntity.status(HttpStatus.FORBIDDEN)
                            .body(Map.of("error", "전결 승인 권한이 없습니다."));
                }
            }

            LeaveApplication application = leaveApplicationService.approveWithApprovalLine(
                    id,
                    approverId,
                    comment,
                    signatureImageUrl,
                    isFinalApproval
            );

            return ResponseEntity.ok(application);
        } catch (Exception e) {
            log.error("결재라인 승인 실패", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", e.getMessage()));
        }
    }
}