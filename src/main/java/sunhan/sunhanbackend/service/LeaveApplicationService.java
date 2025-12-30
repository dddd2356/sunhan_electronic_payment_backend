package sunhan.sunhanbackend.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;
import sunhan.sunhanbackend.dto.response.AttachmentResponseDto;
import sunhan.sunhanbackend.dto.response.LeaveApplicationResponseDto;
import sunhan.sunhanbackend.dto.request.LeaveApplicationUpdateFormRequestDto;
import sunhan.sunhanbackend.dto.request.SignLeaveApplicationRequestDto;
import sunhan.sunhanbackend.entity.mysql.LeaveApplication;
import sunhan.sunhanbackend.entity.mysql.LeaveApplicationAttachment;
import sunhan.sunhanbackend.entity.mysql.UserEntity;
import sunhan.sunhanbackend.entity.mysql.approval.ApprovalLine;
import sunhan.sunhanbackend.entity.mysql.approval.ApprovalStep;
import sunhan.sunhanbackend.entity.mysql.approval.DocumentApprovalProcess;
import sunhan.sunhanbackend.enums.*;
import sunhan.sunhanbackend.enums.approval.ApprovalProcessStatus;
import sunhan.sunhanbackend.enums.approval.ApproverType;
import sunhan.sunhanbackend.enums.approval.DocumentType;
import sunhan.sunhanbackend.repository.mysql.DepartmentRepository;
import sunhan.sunhanbackend.repository.mysql.LeaveApplicationAttachmentRepository;
import sunhan.sunhanbackend.repository.mysql.LeaveApplicationRepository;
import sunhan.sunhanbackend.repository.mysql.UserRepository;
import sunhan.sunhanbackend.repository.mysql.approval.ApprovalLineRepository;
import sunhan.sunhanbackend.repository.mysql.approval.DocumentApprovalProcessRepository;
import sunhan.sunhanbackend.service.approval.ApprovalProcessService;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class LeaveApplicationService {

    private final LeaveApplicationRepository leaveApplicationRepository;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;
    private final FormService formService;
    private final UserService userService;
    private final ApprovalProcessService approvalProcessService;
    private final DocumentApprovalProcessRepository processRepository;
    private static final DateTimeFormatter ISO_LOCAL = DateTimeFormatter.ISO_LOCAL_DATE_TIME;
    private final PdfGenerationService pdfGenerationService; // 비동기 서비스 주입
    private final PermissionService permissionService;
    private final LeaveApplicationAttachmentRepository attachmentRepository;
    @Value("${file.upload-dir}") // application.properties에 경로 설정 필요
    private String uploadDir;
    private final ApprovalLineRepository approvalLineRepository;
    private final DepartmentRepository departmentRepository;

    private String toIsoString(Object maybeDate) {
        if (maybeDate == null) return LocalDateTime.now().format(ISO_LOCAL);

        if (maybeDate instanceof LocalDateTime) {
            return ((LocalDateTime) maybeDate).format(ISO_LOCAL);
        }
        if (maybeDate instanceof String) {
            String s = (String) maybeDate;
            // 이미 ISO 같으면 그대로, 아니면 시도해서 파싱, 실패 시 현재시간으로 대체(안전)
            try {
                LocalDateTime parsed = LocalDateTime.parse(s);
                return parsed.format(ISO_LOCAL);
            } catch (Exception ignored) {
                // 시도: "2025. 8. 8." 같은 포맷을 허용하려면 별도 파서 추가 가능
                return LocalDateTime.now().format(ISO_LOCAL);
            }
        }
        return maybeDate.toString();
    }

    /**
     * 첨부파일 추가
     */
    @Transactional
    public AttachmentResponseDto addAttachment(Long leaveApplicationId, String userId, MultipartFile file) throws IOException {
        LeaveApplication application = leaveApplicationRepository.findById(leaveApplicationId)
                .orElseThrow(() -> new EntityNotFoundException("휴가원을 찾을 수 없습니다."));

        // DRAFT 상태이고, 본인이 신청한 휴가원일 때만 파일 첨부 가능
        if (application.getStatus() != sunhan.sunhanbackend.enums.LeaveApplicationStatus.DRAFT || !application.getApplicantId().equals(userId)) {
            throw new AccessDeniedException("파일을 첨부할 권한이 없습니다.");
        }

        // 1. 파일 저장 로직
        String originalFileName = file.getOriginalFilename();
        // 실제 저장될 파일 이름 (UUID를 사용하여 파일명 중복 방지)
        String storedFileName = UUID.randomUUID().toString() + "_" + originalFileName;

        // 저장 경로 설정
        Path storagePath = Paths.get(uploadDir).toAbsolutePath().normalize();
        // 디렉토리가 존재하지 않으면 생성
        Files.createDirectories(storagePath);
        Path targetLocation = storagePath.resolve(storedFileName);

        // 파일 저장
        Files.copy(file.getInputStream(), targetLocation);

        // 2. 데이터베이스에 파일 메타데이터 저장
        LeaveApplicationAttachment attachment = LeaveApplicationAttachment.builder()
                .leaveApplication(application)
                .originalFileName(originalFileName)
                .storedFilePath(targetLocation.toString()) // 서버에 저장된 전체 경로
                .fileType(file.getContentType())
                .fileSize(file.getSize())
                .build();

        attachmentRepository.save(attachment);
        application.addAttachment(attachment); // 연관관계 편의 메서드 호출

        return AttachmentResponseDto.fromEntity(attachment);
    }

    /**
     * 첨부파일 삭제
     */
    @Transactional
    public void deleteAttachment(Long leaveApplicationId, Long attachmentId, String userId) throws IOException {
        LeaveApplication application = leaveApplicationRepository.findById(leaveApplicationId)
                .orElseThrow(() -> new EntityNotFoundException("휴가원을 찾을 수 없습니다."));

        // DRAFT 상태이고, 본인이 신청한 휴가원일 때만 파일 삭제 가능
        if (application.getStatus() != sunhan.sunhanbackend.enums.LeaveApplicationStatus.DRAFT || !application.getApplicantId().equals(userId)) {
            throw new AccessDeniedException("파일을 삭제할 권한이 없습니다.");
        }

        LeaveApplicationAttachment attachment = attachmentRepository.findById(attachmentId)
                .orElseThrow(() -> new EntityNotFoundException("첨부파일을 찾을 수 없습니다."));

        // 1. 서버에 저장된 실제 파일 삭제
        File fileToDelete = new File(attachment.getStoredFilePath());
        if (fileToDelete.exists()) {
            fileToDelete.delete();
        }

        // 2. 데이터베이스에서 파일 정보 삭제
        attachmentRepository.delete(attachment);
    }

    /**
     * 첨부파일 정보 조회 (다운로드 시 파일명 확인용)
     */
    public LeaveApplicationAttachment getAttachmentInfo(Long attachmentId) {
        return attachmentRepository.findById(attachmentId)
                .orElseThrow(() -> new EntityNotFoundException("첨부파일을 찾을 수 없습니다."));
    }

    /**
     * 첨부파일 다운로드를 위한 리소스 로드
     */
    public Resource loadFileAsResource(Long attachmentId) throws MalformedURLException {
        LeaveApplicationAttachment attachment = getAttachmentInfo(attachmentId);

        Path filePath = Paths.get(attachment.getStoredFilePath()).normalize();
        Resource resource = new UrlResource(filePath.toUri());

        if (resource.exists() && resource.isReadable()) {
            return resource;
        } else {
            throw new EntityNotFoundException("파일을 찾을 수 없거나 읽을 수 없습니다: " + attachment.getOriginalFileName());
        }
    }
    /**
     * 휴가원 생성
     */
    @Transactional
    public LeaveApplication createLeaveApplication(String applicantId) {
        LeaveApplication application = new LeaveApplication();
        application.setApplicantId(applicantId);
        application.setStatus(LeaveApplicationStatus.DRAFT);
        application.setApplicationDate(LocalDate.now());
        application.setPrintable(false);

        // formDataJson 초기화 - LeaveApplicationUpdateFormRequestDto 사용
        try {
            LeaveApplicationUpdateFormRequestDto formData = new LeaveApplicationUpdateFormRequestDto();
            application.setFormDataJson(objectMapper.writeValueAsString(formData));
        } catch (JsonProcessingException e) {
            log.error("Failed to initialize formDataJson for new leave application", e);
            throw new RuntimeException("휴가원 폼 데이터 초기화에 실패했습니다.", e);
        }

        return leaveApplicationRepository.save(application);
    }

    /**
     * [수정된 부분] 휴가원 서명
     * 이 메서드는 상태를 변경하지 않고 서명 데이터와 엔티티 플래그만 업데이트
     * @param id 휴가원 ID
     * @param userId 서명하는 사용자 ID
     * @param signatureType 서명 타입 (applicant, substitute 등)
     * @param signatureData 서명 정보
     * @return 서명이 반영된 LeaveApplication 엔티티
     */
    /**
     * 서명 업데이트 메서드 개선 - 기존 서명 데이터 보존
     */
    @Transactional
    public LeaveApplication updateSignature(
            Long id,
            String userId,
            String signatureType,
            Map<String, Object> signatureData
    ) {
        LeaveApplication application = leaveApplicationRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("휴가원을 찾을 수 없습니다: " + id));

        if (!canSignAtPosition(application, userId, signatureType)) {
            throw new AccessDeniedException("해당 위치에 서명할 권한이 없습니다.");
        }

        // 기존 formData 전체를 보존하면서 서명만 업데이트
        Map<String, Object> formData;
        try {
            if (application.getFormDataJson() != null && !application.getFormDataJson().isEmpty()) {
                formData = objectMapper.readValue(
                        application.getFormDataJson(),
                        new TypeReference<Map<String, Object>>() {}
                );
            } else {
                formData = new HashMap<>();
            }
        } catch (Exception e) {
            log.warn("formDataJson 파싱 실패: {}", e.getMessage());
            formData = new HashMap<>();
        }

        // 서명 데이터 업데이트
        Map<String, List<Map<String, Object>>> signatures =
                (Map<String, List<Map<String, Object>>>) formData.get("signatures");

        if (signatures == null) {
            signatures = new HashMap<>();
        }

        // 프론트에서 넘긴 날짜를 우선 사용하고, 없으면 서버 현재시간 사용
        Object passedDate = signatureData.get("signatureDate");
        String signatureDate = getIsoString(passedDate);

        // null 값 처리 + 실제 서명자 정보 추가
        Map<String, Object> safeSignatureData = new HashMap<>();
        safeSignatureData.put("text", signatureData.getOrDefault("text", ""));
        safeSignatureData.put("imageUrl", signatureData.get("imageUrl"));
        safeSignatureData.put("isSigned", signatureData.getOrDefault("isSigned", false));
        safeSignatureData.put("signatureDate", signatureDate);

        // ★★★ 추가: 실제 서명자 정보 저장 ★★★
        safeSignatureData.put("signerId", userId);

        // 서명자 이름도 저장
        try {
            UserEntity signer = userRepository.findByUserId(userId).orElse(null);
            if (signer != null) {
                safeSignatureData.put("signerName", signer.getUserName());
            }
        } catch (Exception e) {
            log.warn("서명자 정보 조회 실패: {}", e.getMessage());
        }

        // 기존 서명 목록을 가져와서 가변 리스트로 변환
        List<Map<String, Object>> existingSignatures = signatures.get(signatureType);
        List<Map<String, Object>> mutableSignatures;

        if (existingSignatures == null) {
            mutableSignatures = new ArrayList<>();
        } else {
            // 불변 리스트를 가변 리스트(ArrayList)로 변환
            mutableSignatures = new ArrayList<>(existingSignatures);
        }

        // 가변 리스트에 새로운 서명 데이터를 추가
        mutableSignatures.add(safeSignatureData);

        // 수정된 가변 리스트를 다시 signatures 맵에 저장
        signatures.put(signatureType, mutableSignatures);
        formData.put("signatures", signatures);

        // JSON 저장
        try {
            String updatedJson = objectMapper.writeValueAsString(formData);
            application.setFormDataJson(updatedJson);
        } catch (JsonProcessingException e) {
            log.error("formDataJson 직렬화 실패: {}", e.getMessage());
            throw new RuntimeException("폼 데이터 업데이트 실패", e);
        }

        // 서명 플래그 업데이트
        boolean isSigned = (boolean) signatureData.getOrDefault("isSigned", false);

        switch (signatureType) {
            case "applicant":
                application.setIsApplicantSigned(isSigned);
                break;
            case "substitute":
                application.setIsSubstituteApproved(isSigned);
                break;
            case "departmentHead":
                application.setIsDeptHeadApproved(isSigned);
                break;
            case "centerDirector":
                application.setIsCenterDirectorApproved(isSigned);
                break;
            case "adminDirector":
                application.setIsAdminDirectorApproved(isSigned);
                break;
            case "ceoDirector":
                application.setIsCeoDirectorApproved(isSigned);
                break;
            case "hrStaff":
                application.setIsHrStaffApproved(isSigned);
                break;
        }

        application.setUpdatedAt(LocalDateTime.now());
        return leaveApplicationRepository.save(application);
    }

    private String getIsoString(Object passedDate) {
        return toIsoString(passedDate);
    }

    private boolean canSignAtPosition(LeaveApplication app, String userId, String signatureType) {
        // 1. 신청자 서명은 결재라인 사용 여부와 무관하게 허용
        if ("applicant".equals(signatureType)) {
            return (userId.equals(app.getApplicantId()) &&
                    app.getStatus() == LeaveApplicationStatus.DRAFT);
        }

        // 2. 그 외 서명은 결재라인 기반일 경우에만 진행
        if (app.isUsingApprovalLine()) {
            // 모든 승인자는 currentApproverId와 일치해야 함
            return userId.equals(app.getCurrentApproverId());
        } else {
            // ❌ 결재 라인이 없는 상태(DRAFT 상태)에서 신청자 외 다른 서명을 시도하면 에러 발생 (기존 로직 유지)
            throw new IllegalStateException("하드코딩 방식은 더 이상 지원하지 않습니다.");
        }
    }

    /**
     * 휴가원 정보 업데이트 (드래프트 상태에서만 가능)
     * 임시 저장을 위해 기존 데이터와 새로운 데이터를 병합하는 방식으로 수정
     */
    @Transactional
    public LeaveApplication saveLeaveApplication(Long id, String userId, LeaveApplicationUpdateFormRequestDto updateDto) {
        LeaveApplication application = leaveApplicationRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("해당 ID의 휴가원을 찾을 수 없습니다: " + id));

        // 수정 권한 확인
        if (!application.getApplicantId().equals(userId)) {
            throw new AccessDeniedException("휴가원 작성자만 수정할 수 있습니다.");
        }

        // 상태 확인 (DRAFT나 REJECTED 상태에서만 수정 가능)
        if (application.getStatus() != LeaveApplicationStatus.DRAFT &&
                application.getStatus() != LeaveApplicationStatus.REJECTED) {
            throw new IllegalStateException("드래프트 또는 반려 상태에서만 수정할 수 있습니다.");
        }

        try {
            // 기존 formDataJson을 Map으로 파싱하여 기존 데이터 보존
            Map<String, Object> existingFormData = new HashMap<>();
            if (application.getFormDataJson() != null && !application.getFormDataJson().isEmpty()) {
                try {
                    existingFormData = objectMapper.readValue(application.getFormDataJson(), new TypeReference<Map<String, Object>>() {});
                } catch (JsonProcessingException e) {
                    log.warn("기존 formDataJson 파싱 실패, 새로 생성합니다: {}", e.getMessage());
                }
            }

            // 새로운 데이터를 Map으로 변환
            Map<String, Object> newFormData = objectMapper.convertValue(updateDto, new TypeReference<Map<String, Object>>() {});

            // 기존 데이터와 새 데이터 병합 (새 데이터가 우선)
            existingFormData.putAll(newFormData);

            // 서명 정보는 별도로 보존 (덮어쓰지 않음)
            if (updateDto.getSignatures() != null) {
                existingFormData.put("signatures", updateDto.getSignatures());
            }

            // 병합된 데이터를 JSON으로 저장
            String mergedFormDataJson = objectMapper.writeValueAsString(existingFormData);
            application.setFormDataJson(mergedFormDataJson);

            // DTO의 데이터를 바탕으로 엔티티의 다른 필드들도 업데이트
            updateApplicationFromDto(application, updateDto);

        } catch (JsonProcessingException e) {
            log.error("휴가 폼 데이터 변환 실패: {}", updateDto, e);
            throw new RuntimeException("휴가 폼 데이터 변환에 실패했습니다.", e);
        }

        application.setUpdatedAt(LocalDateTime.now());
        return leaveApplicationRepository.save(application);
    }

    /**
     * 휴가원 제출 - 결재라인 기반
     */
    @Transactional
    public LeaveApplication submitLeaveApplication(
            Long id,
            Long approvalLineId,
            String userId
    ) {
        LeaveApplication application = leaveApplicationRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("휴가원을 찾을 수 없습니다: " + id));

        // 1. 권한 확인
        if (!application.getApplicantId().equals(userId)) {
            throw new AccessDeniedException("휴가원 작성자만 제출할 수 있습니다.");
        }

        // 2. 상태 확인
        if (application.getStatus() != LeaveApplicationStatus.DRAFT &&
                application.getStatus() != LeaveApplicationStatus.REJECTED) {
            throw new IllegalStateException("임시저장 또는 반려 상태의 휴가원만 제출할 수 있습니다.");
        }

        // ✅ 3. 결재라인 필수 체크
        if (approvalLineId == null) {
            throw new IllegalArgumentException("결재라인을 선택해주세요.");
        }

        // 4. 신청자 정보 조회
        UserEntity applicant = userRepository.findByUserId(userId)
                .orElseThrow(() -> new EntityNotFoundException("신청자 정보를 찾을 수 없습니다: " + userId));

        // 5. formDataJson 파싱 및 유효성 검사
        Map<String, Object> formDataMap = new HashMap<>();
        try {
            if (application.getFormDataJson() != null && !application.getFormDataJson().isEmpty()) {
                formDataMap = objectMapper.readValue(
                        application.getFormDataJson(),
                        new TypeReference<Map<String, Object>>() {}
                );
            }
        } catch (JsonProcessingException e) {
            log.error("formDataJson 파싱 실패: {}", e.getMessage());
            throw new IllegalArgumentException("휴가원 데이터 형식이 올바르지 않습니다.");
        }

        // 6. 필수 데이터 검증
        LeaveApplicationUpdateFormRequestDto formDataDto =
                objectMapper.convertValue(formDataMap, LeaveApplicationUpdateFormRequestDto.class);

        // ← 반드시 추가: 기존 formDataMap에 signatures가 있으면 DTO에 보존
        try {
            Object signaturesObj = formDataMap.get("signatures");
            if (signaturesObj != null) {
                Map<String, List<Map<String, Object>>> signatures = objectMapper.convertValue(
                        signaturesObj,
                        new TypeReference<Map<String, List<Map<String, Object>>>>() {}
                );
                // 만약 DTO 클래스가 setSignatures 메서드를 가지고 있으면 직접 설정
                formDataDto.setSignatures(signatures);
            }
        } catch (Exception e) {
            log.warn("submitLeaveApplication: signatures 보존 중 예외 발생, 계속 진행합니다. error={}", e.getMessage());
        }

        if (formDataDto == null) {
            throw new IllegalArgumentException("휴가원 폼 데이터가 없습니다. 먼저 휴가 정보를 입력해주세요.");
        }

        // 6-1. 휴가 종류 검증
        if (formDataDto.getLeaveTypes() == null || formDataDto.getLeaveTypes().isEmpty()) {
            throw new IllegalArgumentException("휴가 종류를 선택해주세요.");
        }

        // 6-2. 연차휴가인 경우 일수 검증
        boolean isAnnualLeave = formDataDto.getLeaveTypes().contains("연차휴가");
        if (isAnnualLeave) {
            if (formDataDto.getTotalDays() == null || formDataDto.getTotalDays() <= 0) {
                throw new IllegalArgumentException("연차휴가 신청 시 휴가 기간을 입력해주세요.");
            }
        } else {
            // 연차가 아닌 경우 날짜 유효성 확인
            boolean hasValidDates = false;

            if (formDataDto.getFlexiblePeriods() != null) {
                for (Map<String, String> period : formDataDto.getFlexiblePeriods()) {
                    if (period.get("startDate") != null && !period.get("startDate").isEmpty() &&
                            period.get("endDate") != null && !period.get("endDate").isEmpty()) {
                        hasValidDates = true;
                        break;
                    }
                }
            }

            if (!hasValidDates && formDataDto.getConsecutivePeriod() != null) {
                if (formDataDto.getConsecutivePeriod().get("startDate") != null &&
                        !formDataDto.getConsecutivePeriod().get("startDate").isEmpty() &&
                        formDataDto.getConsecutivePeriod().get("endDate") != null &&
                        !formDataDto.getConsecutivePeriod().get("endDate").isEmpty()) {
                    hasValidDates = true;
                }
            }

            if (!hasValidDates) {
                throw new IllegalArgumentException("휴가 기간을 입력해주세요.");
            }
        }

        // 6-3. 신청자 서명 검증
        boolean hasApplicantSignature = false;
        if (formDataDto.getSignatures() != null &&
                formDataDto.getSignatures().get("applicant") != null) {
            List<Map<String, Object>> applicantSigs = formDataDto.getSignatures().get("applicant");
            if (!applicantSigs.isEmpty()) {
                Map<String, Object> firstSig = applicantSigs.get(0);
                hasApplicantSignature = Boolean.TRUE.equals(firstSig.get("isSigned"));
            }
        }

        if (!hasApplicantSignature) {
            throw new IllegalStateException("신청자 서명이 완료되지 않았습니다. 서명을 먼저 진행해주세요.");
        }

        // 7. 폼 데이터 기반으로 엔티티 업데이트
        updateApplicationFromDto(application, formDataDto);

        // 8. 신청일 설정
        if (formDataDto.getApplicationDate() != null) {
            application.setApplicationDate(formDataDto.getApplicationDate());
        } else {
            application.setApplicationDate(LocalDate.now());
        }

        // 9. 신청자 서명 플래그 설정
        application.setIsApplicantSigned(true);

        // ✅ 10. 결재라인 기반 제출
        ApprovalLine selectedLine = approvalLineRepository.findByIdWithSteps(approvalLineId)
                .orElseThrow(() -> new EntityNotFoundException("선택된 결재라인을 찾을 수 없습니다."));

        // 10-1. 결재 단계 복사 및 SUBSTITUTE 단계에 대직자 ID 주입
        List<ApprovalStep> finalSteps = selectedLine.getSteps().stream()
                .map(step -> {
                    ApprovalStep processedStep = step.copy();

                    if (processedStep.getApproverType() == ApproverType.SUBSTITUTE) {
                        if (formDataDto.getSubstituteInfo() == null
                                || formDataDto.getSubstituteInfo().getUserId() == null) {
                            throw new IllegalArgumentException("대직자를 선택해주세요.");
                        }

                        String substituteId = formDataDto.getSubstituteInfo().getUserId();

                        UserEntity substitute = userRepository.findByUserId(substituteId)
                                .orElseThrow(() -> new EntityNotFoundException(
                                        "대직자를 찾을 수 없습니다: " + substituteId
                                ));

                        if (!"1".equals(substitute.getUseFlag())) {
                            throw new IllegalStateException(
                                    String.format("대직자 '%s'는 비활성 상태입니다.",
                                            substitute.getUserName())
                            );
                        }

                        processedStep.setApproverId(substituteId);
                    }

                    return processedStep;
                })
                .collect(Collectors.toList());

        // 10-2. 결재 프로세스 시작
        DocumentApprovalProcess process = approvalProcessService.startProcessWithSteps(
                id,
                DocumentType.LEAVE_APPLICATION,
                selectedLine,
                finalSteps,
                userId
        );

        // 10-3. 첫 번째 결재자 ID 설정
        ApprovalStep firstStep = finalSteps.stream()
                .filter(s -> s.getStepOrder() == 1)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("결재라인에 유효한 첫 단계가 없습니다."));

        application.setCurrentApproverId(firstStep.getApproverId());
        application.setApprovalLine(process.getApprovalLine());
        application.setStatus(LeaveApplicationStatus.PENDING); // ✅ 통합된 상태
        application.setCurrentStepOrder(firstStep.getStepOrder()); // ✅ 추가
        application.setCurrentApprovalStep(firstStep.getStepName());

        // 11. 저장
        try {
            String mergedJson = objectMapper.writeValueAsString(formDataDto);
            application.setFormDataJson(mergedJson);
        } catch (JsonProcessingException e) {
            log.error("formDataJson 직렬화 실패: {}", e.getMessage());
            throw new RuntimeException("폼 데이터 저장에 실패했습니다.", e);
        }

        LeaveApplication savedApplication = leaveApplicationRepository.save(application);

        log.info("휴가원 제출 완료: id={}, applicantId={}, status={}",
                id, userId, savedApplication.getStatus());

        return savedApplication;
    }


    @Transactional
    @CacheEvict(value = "userCache", key = "#result.applicantId")
    public LeaveApplication finalApproveLeaveApplication(Long id, String approverId) {
        log.info("Start finalApproveLeaveApplication id={} approver={}", id, approverId);

        LeaveApplication application = leaveApplicationRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("휴가원을 찾을 수 없습니다. id=" + id));

        UserEntity approver = userRepository.findByUserId(approverId)
                .orElseThrow(() -> new EntityNotFoundException("승인자를 찾을 수 없습니다. userId=" + approverId));

        int jobLevel;
        try {
            jobLevel = Integer.parseInt(Objects.toString(approver.getJobLevel(), "0"));
        } catch (NumberFormatException e) {
            log.warn("approver.jobLevel 파싱 실패 userId={} jobLevelRaw={}", approver.getUserId(), approver.getJobLevel());
            throw new AccessDeniedException("전결 승인 권한이 없습니다. (유효하지 않은 jobLevel)");
        }

        String currentStep = application.getCurrentApprovalStep();
        boolean isHrFinalStep = "HR_FINAL_APPROVAL".equals(currentStep) || "PENDING_HR_FINAL".equals(currentStep);
        Set<PermissionType> approverPermissions = permissionService.getAllUserPermissions(approverId);
        boolean isHrAdmin = approver.getRole() == Role.ADMIN &&
                approverPermissions.contains(PermissionType.HR_LEAVE_APPLICATION);


        if (jobLevel < 2 && !(isHrFinalStep && isHrAdmin)) {
            throw new AccessDeniedException("전결 승인 권한이 없습니다.");
        }
        if (!canApprove(approver, application)) {
            throw new AccessDeniedException("현재 단계에서 승인할 권한이 없습니다.");
        }
        LocalDateTime now = LocalDateTime.now();

        // 전결 승인 처리
        application.setIsFinalApproved(true);
        application.setFinalApproverId(approverId);
        application.setFinalApprovalDate(now);
        application.setFinalApprovalStep(currentStep);

        application.setStatus(LeaveApplicationStatus.APPROVED);
        // renderer/프론트가 "완료" 판단을 currentApprovalStep == null 로 하면 null로 설정
        application.setCurrentApprovalStep(null);
        application.setCurrentApproverId(null);
        application.setPrintable(true);

        switch (currentStep) {
            case "DEPARTMENT_HEAD_APPROVAL":
                application.setIsDeptHeadApproved(true);
                application.setIsHrStaffApproved(true);
                application.setIsCenterDirectorApproved(true);
                application.setIsAdminDirectorApproved(true);
                application.setIsCeoDirectorApproved(true);
                break;
            case "HR_STAFF_APPROVAL":
                application.setIsHrStaffApproved(true);
                application.setIsCenterDirectorApproved(true);
                application.setIsAdminDirectorApproved(true);
                application.setIsCeoDirectorApproved(true);
                break;
            case "CENTER_DIRECTOR_APPROVAL":
                application.setIsCenterDirectorApproved(true);
                application.setIsAdminDirectorApproved(true);
                application.setIsCeoDirectorApproved(true);
                break;
            case "HR_FINAL_APPROVAL":
                application.setIsHrFinalApproved(true);
                application.setIsAdminDirectorApproved(true);
                application.setIsCeoDirectorApproved(true);
                break;
            case "ADMIN_DIRECTOR_APPROVAL":
                application.setIsAdminDirectorApproved(true);
                application.setIsCeoDirectorApproved(true);
                break;
            case "CEO_DIRECTOR_APPROVAL":
                application.setIsCeoDirectorApproved(true);
                break;
        }

        // formDataJson 업데이트: 기존 로직 유지 + signatureNode 키도 함께 업데이트
        try {
            Map<String, Object> formData = new HashMap<>();
            if (application.getFormDataJson() != null && !application.getFormDataJson().isEmpty()) {
                formData = objectMapper.readValue(application.getFormDataJson(),
                        new TypeReference<Map<String, Object>>() {});
            }

            formData.put("isFinalApproved", true);
            formData.put("finalApproverId", approverId);
            formData.put("finalApprovalDate", now.toString());
            formData.put("finalApprovalStep", currentStep);
            formData.put("finalApprovalStatus", "APPROVED");

            // 기존 서명 구조(문서마다 다르므로 both-approach): "signatures" 와 "signatureNode" 둘 다 업데이트
            Map<String, List<Map<String, Object>>> signatures =
                    (Map<String, List<Map<String, Object>>>) formData.getOrDefault("signatures", new HashMap<>());

            List<String> allSteps = List.of("applicant", "substitute", "departmentHead", "hrStaff", "centerDirector", "adminDirector", "ceoDirector");
            for (String stepType : allSteps) {
                if (!signatures.containsKey(stepType)) {
                    Map<String, Object> emptySignature = new HashMap<>();
                    emptySignature.put("text", "");
                    emptySignature.put("imageUrl", null);
                    emptySignature.put("isSigned", false);
                    emptySignature.put("signatureDate", null);
                    signatures.put(stepType, List.of(emptySignature));
                }
            }

            Map<String, Object> approverSignature = new HashMap<>();
            approverSignature.put("text", approver.getUserName());
            approverSignature.put("imageUrl", approver.getSignimage());
            approverSignature.put("isSigned", true);
            approverSignature.put("signatureDate", now.toString());

            String signatureType = getSignatureTypeFromStep(currentStep);
            if (signatureType != null) {
                List<Map<String, Object>> currentStepSignatures = new ArrayList<>();
                currentStepSignatures.add(approverSignature);
                signatures.put(signatureType, currentStepSignatures);
            } else {
                log.warn("전결 승인 단계 '{}'에 해당하는 서명 타입을 찾을 수 없습니다.", currentStep);
            }

            // Put back to formData (both keys)
            formData.put("signatures", signatures);
            formData.put("signatureNode", signatures); // 렌더러가 signatureNode를 읽는 경우를 대비

            application.setFormDataJson(objectMapper.writeValueAsString(formData));
        } catch (Exception e) {
            log.error("전결 승인 시 formDataJson 업데이트 실패", e);
        }

        application.setUpdatedAt(now);
        appendApprovalHistory(application, approver.getUserName() + " (전결)", approver.getJobLevel());

        // 저장 -> 트랜잭션 내에서 실행
        LeaveApplication saved = leaveApplicationRepository.saveAndFlush(application);

        // ✅ [추가] 연차 여부 확인
        boolean isAnnualLeave = false;
        try {
            Map<String, Object> formData = objectMapper.readValue(saved.getFormDataJson(), new TypeReference<Map<String, Object>>() {});
            List<String> leaveTypes = (List<String>) formData.get("leaveTypes");
            if (leaveTypes != null && leaveTypes.contains("연차휴가")) {
                isAnnualLeave = true;
            }
        } catch (Exception e) {
            log.warn("leaveTypes 파싱 실패: {}", e.getMessage());
        }

        // ✅ 연차인 경우에만 차감
        if (isAnnualLeave && saved.getTotalDays() != null && saved.getTotalDays() > 0) {
            try {
                deductVacationDaysInSameTransaction(saved.getApplicantId(), saved.getTotalDays());
                log.info("전결 승인 시 연차 차감 완료: userId={}, days={}",
                        saved.getApplicantId(), saved.getTotalDays());
            } catch (Exception e) {
                log.error("연차 차감 실패: {}", e.getMessage(), e);
                throw new IllegalStateException("연차 차감 실패: " + e.getMessage());
            }
        }

        log.info("Saved application id={} isFinalApproved={} status={} currentStep={}",
                saved.getId(), saved.getIsFinalApproved(), saved.getStatus(), saved.getCurrentApprovalStep());

        // PDF 생성은 트랜잭션 커밋 이후에 실행하도록 등록 (다른 스레드가 DB에서 읽을 때 변경분을 보장)
        Long savedId = saved.getId();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    log.info("AfterCommit -> generate PDF for id={}", savedId);
                    pdfGenerationService.generateLeaveApplicationPdfAsync(savedId);
                } catch (Exception ex) {
                    log.error("PDF generation failed after commit for id=" + savedId, ex);
                }
            }
        });
        // ✅ 최종 승인 알림 전송 (신청자에게)
        UserEntity applicant = saved.getApplicant();
        Map<String, String> variables = new HashMap<>();
        variables.put("applicantName", applicant.getUserName());
        variables.put("leaveType", saved.getLeaveType().getDisplayName());
        variables.put("leaveStartDate", saved.getStartDate().toString());
        variables.put("leaveEndDate", saved.getEndDate().toString());

        return saved;
    }

    /**
     * 휴가 정보를 DTO로부터 업데이트하는 헬퍼 메서드 (수정됨 - 연차휴가만 일수 차감)
     */
    private void updateApplicationFromDto(LeaveApplication application, LeaveApplicationUpdateFormRequestDto dto) {
        // 대직자 정보 설정
        String substituteId = (dto.getSubstituteInfo() != null) ? dto.getSubstituteInfo().getUserId() : null;
        application.setSubstituteId(substituteId);

        // 휴가 종류 설정
        LeaveType matchedType = null;
        if (dto.getLeaveTypes() != null && !dto.getLeaveTypes().isEmpty()) {
            String koreanLeaveType = dto.getLeaveTypes().get(0);

            // LeaveType Enum에서 한글 이름으로 찾기
            for (LeaveType type : LeaveType.values()) {
                if (type.getDisplayName().equals(koreanLeaveType)) {
                    matchedType = type;
                    break;
                }
            }

            if (matchedType != null) {
                application.setLeaveType(matchedType);
            } else {
                log.error("Invalid leave type (korean): {}", koreanLeaveType);
                throw new IllegalArgumentException("유효하지 않은 휴가 타입입니다.");
            }
        }

        // 연차휴가가 아닌 경우 totalDays를 0으로 설정하고 일수 계산 로직 건너뛰기 ★★★
        boolean isAnnualLeave = dto.getLeaveTypes() != null &&
                dto.getLeaveTypes().contains("연차휴가") &&
                matchedType == LeaveType.ANNUAL_LEAVE;

        // 제거: if (!isAnnualLeave) { application.setTotalDays(0.0); setStartEndDatesFromDto(application, dto); return; }  // 모든 휴가에서 계산

        // [수정] 연차 여부 무시하고 항상 계산 (다른 휴가도 totalDays 설정)
        // totalDays 설정: DTO의 값이 우선하며, 없는 경우에만 재계산
        Double totalDays = dto.getTotalDays();
        if (totalDays == null || totalDays <= 0) {
            double calculatedTotalDays = 0.0;

            // 개별 기간(flexiblePeriods) 계산
            if (dto.getFlexiblePeriods() != null) {
                for (Map<String, String> period : dto.getFlexiblePeriods()) {
                    String startDateStr = period.get("startDate");
                    String endDateStr = period.get("endDate");

                    if (startDateStr != null && !startDateStr.isEmpty() && endDateStr != null && !endDateStr.isEmpty()) {
                        LocalDate startDate = LocalDate.parse(startDateStr);
                        LocalDate endDate = LocalDate.parse(endDateStr);
                        long days = ChronoUnit.DAYS.between(startDate, endDate) + 1;
                        String halfDayOption = period.get("halfDayOption");
                        if ("morning".equals(halfDayOption) || "afternoon".equals(halfDayOption)) {
                            calculatedTotalDays += 0.5;
                        } else {
                            calculatedTotalDays += days;
                        }
                    }
                }
            }

            // 연속 기간(consecutivePeriod) 계산
            if (dto.getConsecutivePeriod() != null) {
                String startDateStr = dto.getConsecutivePeriod().get("startDate");
                String endDateStr = dto.getConsecutivePeriod().get("endDate");

                if (startDateStr != null && !startDateStr.isEmpty() && endDateStr != null && !endDateStr.isEmpty()) {
                    LocalDate startDate = LocalDate.parse(startDateStr);
                    LocalDate endDate = LocalDate.parse(endDateStr);
                    calculatedTotalDays += ChronoUnit.DAYS.between(startDate, endDate) + 1;
                }
            }

            if (calculatedTotalDays == 0.0) {
                throw new IllegalArgumentException("유효한 휴가 기간이 입력되어야 합니다.");
            }
            application.setTotalDays(calculatedTotalDays);
        } else {
            application.setTotalDays(totalDays);
        }

        // 시작일(startDate)과 종료일(endDate) 설정
        setStartEndDatesFromDto(application, dto);
    }

    /**
     * DTO에서 시작일과 종료일을 설정하는 별도 메서드
     */
    private void setStartEndDatesFromDto(LeaveApplication application, LeaveApplicationUpdateFormRequestDto dto) {
        LocalDate calculatedStartDate = null;
        LocalDate calculatedEndDate = null;

        // 개별 기간에서 시작일/종료일 찾기
        if (dto.getFlexiblePeriods() != null && !dto.getFlexiblePeriods().isEmpty()) {
            for(Map<String, String> period : dto.getFlexiblePeriods()) {
                String startStr = period.get("startDate");
                String endStr = period.get("endDate");

                if (startStr != null && !startStr.isEmpty()) {
                    LocalDate currentStartDate = LocalDate.parse(startStr);
                    if (calculatedStartDate == null || currentStartDate.isBefore(calculatedStartDate)) {
                        calculatedStartDate = currentStartDate;
                    }
                }
                if (endStr != null && !endStr.isEmpty()) {
                    LocalDate currentEndDate = LocalDate.parse(endStr);
                    if (calculatedEndDate == null || currentEndDate.isAfter(calculatedEndDate)) {
                        calculatedEndDate = currentEndDate;
                    }
                }
            }
        }

        // 연속 기간에서 시작일/종료일 찾아서 비교/업데이트
        if (dto.getConsecutivePeriod() != null) {
            String startStr = dto.getConsecutivePeriod().get("startDate");
            String endStr = dto.getConsecutivePeriod().get("endDate");

            if (startStr != null && !startStr.isEmpty()) {
                LocalDate consecutiveStartDate = LocalDate.parse(startStr);
                if (calculatedStartDate == null || consecutiveStartDate.isBefore(calculatedStartDate)) {
                    calculatedStartDate = consecutiveStartDate;
                }
            }
            if (endStr != null && !endStr.isEmpty()) {
                LocalDate consecutiveEndDate = LocalDate.parse(endStr);
                if (calculatedEndDate == null || consecutiveEndDate.isAfter(calculatedEndDate)) {
                    calculatedEndDate = consecutiveEndDate;
                }
            }
        }

        if (calculatedStartDate != null && calculatedEndDate != null) {
            application.setStartDate(calculatedStartDate);
            application.setEndDate(calculatedEndDate);
        }
    }

    /**
     * 휴가원 승인 시 기존 서명 데이터 보존
     */
    @Transactional
    public LeaveApplication approveLeaveApplication(
            Long id,
            String approverId,
            String signatureDate,
            String signatureImageUrl // ✅ 새롭게 추가된 인수
    ) {
        LeaveApplication application = getOrThrow(id);

        // ✅ 결재라인 사용 여부 확인
        if (!application.isUsingApprovalLine()) {
            // 하드코딩 방식 승인 API를 사용하여 결재라인을 사용하지 않는 경우이므로,
            // 이 로직은 결재라인 기반 승인으로 모두 위임하는 것이 좋습니다.
            // 현재 로직이 결재라인 기반으로 강제하고 있으므로 이 부분은 유지합니다.
            throw new IllegalStateException("결재라인을 사용하지 않는 휴가원입니다.");
        }

        // approveWithApprovalLine으로 위임 시 이미지 URL을 전달
        // 이 메서드 시그니처도 확인하여 수정해야 합니다. (아래 2번 참조)
        // 현재 approveWithApprovalLine에 comment는 빈 문자열로, isFinalApproval은 false로 전달하고 있습니다.
        return approveWithApprovalLine(
                id,
                approverId,
                "", // comment
                signatureImageUrl, // ✅ signatureImageUrl 전달
                false // isFinalApproval
        );
    }

    /**
     * 기존 서명 데이터를 보존하면서 현재 단계의 서명을 추가/업데이트하는 메서드
     */
    private void preserveAndUpdateSignatures(LeaveApplication application, String currentStep, UserEntity approver, String signatureDate, String signatureImageUrl) {
        try {
            // 1. 전체 formDataJson을 LeaveApplicationUpdateFormRequestDto로 안전하게 읽어옵니다.
            LeaveApplicationUpdateFormRequestDto formData;
            if (application.getFormDataJson() != null && !application.getFormDataJson().isEmpty()) {
                // 이 DTO는 전체 JSON 구조를 반영해야 합니다.
                formData = objectMapper.readValue(application.getFormDataJson(), LeaveApplicationUpdateFormRequestDto.class);
            } else {
                formData = new LeaveApplicationUpdateFormRequestDto();
            }

            if (formData.getSignatures() == null) {
                formData.setSignatures(new HashMap<>());
            }

            // 현재 단계에 해당하는 서명 타입 결정
            String signatureType = getSignatureTypeFromStep(currentStep);

            if (signatureType != null) {

                // 2. 새로운 서명 항목 생성
                Map<String, Object> newSignature = new HashMap<>();
                newSignature.put("text", "승인");

                // Base64 포맷 보정 (이전 수정사항 유지)
                String correctedImageUrl = signatureImageUrl;
                if (correctedImageUrl != null && !correctedImageUrl.startsWith("data:")) {
                    correctedImageUrl = "data:image/png;base64," + correctedImageUrl;
                }
                newSignature.put("imageUrl", correctedImageUrl);
                newSignature.put("isSigned", true);
                newSignature.put("signatureDate", toIsoString(signatureDate));
                newSignature.put("signerId", approver.getUserId());
                newSignature.put("signerName", approver.getUserName());

                // 3. 서명 리스트 업데이트: 무조건 현재 단계의 서명을 덮어쓰거나 새로 추가합니다.
                // DTO를 사용하면 기존 서명(previous approver)은 자동으로 보존됩니다.
                List<Map<String, Object>> currentSignatures = formData.getSignatures()
                        .getOrDefault(signatureType, new ArrayList<>());
                boolean isAlreadySigned = !currentSignatures.isEmpty() && Boolean.TRUE.equals(currentSignatures.get(0).get("isSigned"));

                // 현재 서명을 덮어씁니다 (최신 서명만 유효). 기존 리스트의 첫 번째 항목을 업데이트하는 것이 일반적입니다.
                if (isAlreadySigned && (signatureImageUrl == null || signatureImageUrl.isEmpty())) {
                    // 이미 서명된 데이터가 있고, 새로운 서명 이미지가 제공되지 않았으므로 (예: 단순 단계 이동 후 데이터 보존 호출),
                    // JSON 데이터를 건드리지 않고 기존 서명 보존.
                    log.info("기존 서명 데이터 보존: Step={}, SignatureType={}", currentStep, signatureType);
                    // 다음 JSON 저장 로직으로 이동
                } else {
                    // 서명되지 않았거나 (최초 서명), 새로운 이미지 URL이 제공된 경우 (재서명) 업데이트 실행

                    if (isAlreadySigned) {
                        // 이미 서명되어 있었다면 (재서명): 기존 항목을 새로운 항목으로 대체
                        currentSignatures.set(0, newSignature);
                    } else {
                        // 서명되지 않았다면 (최초 서명): 새로 추가
                        currentSignatures.add(0, newSignature);
                    }

                    formData.getSignatures().put(signatureType, currentSignatures);
                }
            }

            // 4. 전체 DTO를 JSON으로 저장 (다른 필드(signatures 외)도 모두 보존됨)
            application.setFormDataJson(objectMapper.writeValueAsString(formData));

        } catch (Exception e) {
            log.error("서명 데이터 보존 중 오류 발생: {}", e.getMessage(), e);
        }
    }

    /**
     * 승인 단계에서 서명 타입으로 변환
     */
    private String getSignatureTypeFromStep(String step) {
        return switch (step) {
            case "SUBSTITUTE_APPROVAL" -> "substitute";
            case "DEPARTMENT_HEAD_APPROVAL" -> "departmentHead";
            case "HR_STAFF_APPROVAL" -> "hrStaff";
            case "CENTER_DIRECTOR_APPROVAL" -> "centerDirector";
            case "ADMIN_DIRECTOR_APPROVAL" -> "adminDirector";
            case "CEO_DIRECTOR_APPROVAL" -> "ceoDirector";
            default -> null;
        };
    }

    private void appendApprovalHistory(LeaveApplication application, String approverName, String approverJobLevel) {
        try {
            ObjectNode historyEntry = objectMapper.createObjectNode();
            historyEntry.put("approverName", approverName);
            historyEntry.put("approverJobLevel", approverJobLevel);
            historyEntry.put("approvedAt", LocalDateTime.now().toString());

            String formDataJson = application.getFormDataJson();
            ObjectNode rootNode;
            if (formDataJson == null || formDataJson.isEmpty()) {
                rootNode = objectMapper.createObjectNode();
            } else {
                rootNode = (ObjectNode) objectMapper.readTree(formDataJson);
            }

            ArrayNode approvalHistoryArray;
            if (rootNode.has("approvalHistory") && rootNode.get("approvalHistory").isArray()) {
                approvalHistoryArray = (ArrayNode) rootNode.get("approvalHistory");
            } else {
                approvalHistoryArray = objectMapper.createArrayNode();
                rootNode.set("approvalHistory", approvalHistoryArray);
            }
            approvalHistoryArray.add(historyEntry);

            application.setFormDataJson(objectMapper.writeValueAsString(rootNode));
        } catch (JsonProcessingException e) {
            log.error("Failed to append approval history to formDataJson", e);
        }
    }

    /**
     * 휴가원 반려
     */
    @Transactional
    public LeaveApplication rejectLeaveApplication(Long id, String approverId, String rejectionReason) {
        LeaveApplication application = getOrThrow(id);

        UserEntity approver = userRepository.findByUserId(approverId)
                .orElseThrow(() -> new EntityNotFoundException("승인자 정보를 찾을 수 없습니다: " + approverId));

        if (!canApprove(approver, application)) {
            throw new AccessDeniedException("해당 휴가원을 반려할 권한이 없습니다.");
        }

        // ✅ [추가] APPROVED 상태의 연차휴가를 반려하는 경우 복구
        if (application.getStatus() == LeaveApplicationStatus.APPROVED
                && application.getLeaveType() == LeaveType.ANNUAL_LEAVE
                && application.getTotalDays() != null
                && application.getTotalDays() > 0) {

            try {
                restoreVacationDaysInSameTransaction(application.getApplicantId(), application.getTotalDays());
                log.info("반려 시 연차 복구: userId={}, days={}",
                        application.getApplicantId(), application.getTotalDays());
            } catch (Exception e) {
                log.error("연차 복구 실패", e);
                throw new IllegalStateException("연차 복구 실패: " + e.getMessage());
            }
        }

        application.setStatus(LeaveApplicationStatus.REJECTED);
        application.setRejectionReason(rejectionReason);
        application.setCurrentApprovalStep(null);
        application.setPrintable(false);
        application.setUpdatedAt(LocalDateTime.now());

        LeaveApplication savedApplication = leaveApplicationRepository.save(application);

        // 알림 전송
        UserEntity applicant = savedApplication.getApplicant();
        Map<String, String> variables = new HashMap<>();
        variables.put("applicantName", applicant.getUserName());
        variables.put("leaveType", savedApplication.getLeaveType().getDisplayName());
        variables.put("rejectionReason", rejectionReason);

        return savedApplication;
    }

    /**
     * 휴가원 삭제 (작성중만 삭제가능)
     */
    @Transactional
    public void deleteLeaveApplication(Long id, String userId) {
        LeaveApplication application = getOrThrow(id);
        if (!application.getApplicantId().equals(userId)) {
            throw new AccessDeniedException("휴가원 작성자만 삭제할 수 있습니다.");
        }
        if (application.getStatus() != LeaveApplicationStatus.DRAFT) {
            throw new IllegalStateException("작성중 상태의 휴가원만 삭제할 수 있습니다.");
        }
        // 실제 삭제 대신 상태 변경
        application.setStatus(LeaveApplicationStatus.DELETED);
        leaveApplicationRepository.save(application);
    }

    /**
     * 특정 휴가원 조회 (관리자 또는 관련자만 조회 가능)
     */
    public LeaveApplicationResponseDto getLeaveApplicationDetail(Long id, String userId) {
        LeaveApplication application = getOrThrow(id);

        Set<String> ids = new HashSet<>();
        ids.add(application.getApplicantId());
        if (application.getSubstituteId() != null) ids.add(application.getSubstituteId());
        ids.add(userId);

        List<UserEntity> users = userRepository.findByUserIdIn(ids);
        Map<String, UserEntity> userMap = users.stream()
                .collect(Collectors.toMap(UserEntity::getUserId, Function.identity()));

        UserEntity applicant = userMap.get(application.getApplicantId());
        if (applicant == null) throw new EntityNotFoundException("신청자 정보를 찾을 수 없습니다.");

        UserEntity substitute = application.getSubstituteId() != null ? userMap.get(application.getSubstituteId()) : null;
        UserEntity currentUser = userMap.get(userId);
        if (currentUser == null) throw new EntityNotFoundException("사용자 정보를 찾을 수 없습니다.");

        if (!canView(currentUser, application, applicant)) {
            throw new AccessDeniedException("해당 휴가원을 조회할 권한이 없습니다.");
        }

        return LeaveApplicationResponseDto.fromEntity(application, applicant, substitute);
    }

    /**
     * 내 휴가원 목록 조회 (신청자 본인)
     */
    @Transactional(readOnly = true)
    public Page<LeaveApplicationResponseDto> getMyApplications(String applicantId, Pageable pageable) {
        // ✅ 수정: DRAFT, PENDING, REJECTED 상태만 조회하도록 변경
        Set<LeaveApplicationStatus> myStatuses = Set.of(
                LeaveApplicationStatus.DRAFT,
                LeaveApplicationStatus.PENDING,
                LeaveApplicationStatus.REJECTED
        );

        Page<LeaveApplication> applicationsPage = leaveApplicationRepository
                .findByApplicantIdAndStatusIn(applicantId, myStatuses, pageable);

        return applicationsPage.map(app -> {
            LeaveApplicationResponseDto dto = new LeaveApplicationResponseDto();
            dto.setId(app.getId());
            dto.setStartDate(app.getStartDate());
            dto.setEndDate(app.getEndDate());
            dto.setTotalDays(app.getTotalDays());
            dto.setStatus(app.getStatus());

            if (app.getApplicant() != null) {
                dto.setApplicantName(app.getApplicant().getUserName());
            }
            if (app.getSubstitute() != null) {
                dto.setSubstituteName(app.getSubstitute().getUserName());
            }

            dto.setCreatedAt(app.getCreatedAt());
            dto.setUpdatedAt(app.getUpdatedAt());
            dto.setFormDataJson(app.getFormDataJson());

            return dto;
        });
    }

    /**
     * 내가 승인해야 할 휴가원 목록 조회
     */
    public Page<LeaveApplicationResponseDto> getPendingApplicationsForMe(String approverId, Pageable pageable) {
        // 1. 로그인한 사용자의 부서 코드를 조회합니다.
        String userDeptCode = userRepository.findByUserId(approverId)
                .map(UserEntity::getDeptCode)
                .orElse(null);

        // 2. 승인 대기 상태 목록을 정의합니다.
        Set<LeaveApplicationStatus> pendingStatuses = Set.of(
                LeaveApplicationStatus.PENDING
        );

        Page<LeaveApplication> page;

        page = leaveApplicationRepository.findByCurrentApproverIdAndStatusIn(
                approverId,
                pendingStatuses,
                pageable
        );
        // 4. 조회된 Page를 DTO로 변환하여 반환합니다.
        return page.map(app -> {
            UserEntity applicant = userService.getUserInfo(app.getApplicantId());
            UserEntity substitute = app.getSubstituteId() != null ? userService.getUserInfo(app.getSubstituteId()) : null;
            return LeaveApplicationResponseDto.fromEntity(app, applicant, substitute);
        });
    }

    /**
     * 특정 휴가원 엔티티 가져오기 (내부용)
     */
    public LeaveApplication getLeaveApplicationEntity(Long id) {
        return getOrThrow(id);
    }

    private LeaveApplication getOrThrow(Long id) {
        // Optional 제거 및 null 체크로 변경 (findById는 여전히 Optional을 반환하므로 주의!)
        // JpaRepository의 findById는 Optional을 반환합니다.
        // 이 부분은 사용자 요청과 직접적인 관련이 없지만, 표준 방식대로 Optional을 사용하도록 유지합니다.
        // 또는 이 메서드도 findByIdCustom (가상의 메서드)으로 바꾸고 Optional을 사용하지 않도록 수정할 수 있습니다.
        // 여기서는 기존의 findById가 Optional을 반환하는 JpaRepository의 표준임을 감안하여 orElseThrow를 유지합니다.
        // 다른 findByUserId()와는 다르게 findById()는 표준적으로 Optional<T>를 반환합니다.
        return leaveApplicationRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("휴가원을 찾을 수 없습니다: " + id));
    }

    /**
     * 휴가원에 서명 (사용자 또는 승인자가 서명)
     * @param leaveApplicationId 휴가원 ID
     * @param request 서명 요청 DTO (signerId, signerType, SignatureEntry 포함)
     * @return 서명이 반영된 LeaveApplicationResponseDto
     */
    @Transactional
    public LeaveApplicationResponseDto signLeaveApplication(Long leaveApplicationId, SignLeaveApplicationRequestDto request) {
        LeaveApplication application = getOrThrow(leaveApplicationId);
        String signerId = request.getSignerId();
        String signerType = request.getSignerType();
        SignLeaveApplicationRequestDto.SignatureEntry signatureEntry = request.getSignatureEntry();

        // 1. Optional을 사용하여 서명자 정보를 안전하게 조회하고, 없으면 예외 발생
        UserEntity signerUser = userRepository.findByUserId(signerId)
                .orElseThrow(() -> new EntityNotFoundException("서명자 정보를 찾을 수 없습니다: " + signerId));

        // 2. Optional을 사용하여 신청자 정보를 안전하게 조회하고, 없으면 예외 발생
        UserEntity applicantUser = userRepository.findByUserId(application.getApplicantId())
                .orElseThrow(() -> new EntityNotFoundException("신청자 정보를 찾을 수 없습니다: " + application.getApplicantId()));


        // 서명 권한 확인
        if (!canSignAtPosition(application, signerId, signerType)) {
            throw new AccessDeniedException("현재 " + signerUser.getUserName() + " (" + signerType + ") 님은 서명할 권한이 없거나, 휴가원 상태가 서명 가능한 상태가 아닙니다.");
        }

        // formDataJson 업데이트
        try {
            LeaveApplicationUpdateFormRequestDto formData;
            if (application.getFormDataJson() != null && !application.getFormDataJson().isEmpty()) {
                formData = objectMapper.readValue(application.getFormDataJson(), LeaveApplicationUpdateFormRequestDto.class);
            } else {
                formData = new LeaveApplicationUpdateFormRequestDto();
            }

            if (formData.getSignatures() == null) {
                formData.setSignatures(new HashMap<>());
            }

// SignatureEntry를 Map으로 변환 (날짜 정규화, imageUrl 보정)
            Map<String, Object> signatureMap = new HashMap<>();
            signatureMap.put("text", signatureEntry.getText() == null ? "" : signatureEntry.getText());
            String imageUrl = signatureEntry.getImageUrl();
            if (imageUrl != null && !imageUrl.startsWith("data:")) {
                // 필요하면 data:image/png;base64, 를 자동 추가 (클라이언트가 순수 base64만 보낼 때)
                imageUrl = "data:image/png;base64," + imageUrl;
            }
            signatureMap.put("imageUrl", imageUrl);
            signatureMap.put("isSigned", signatureEntry.isSigned());

// 날짜: 항상 ISO 문자열로 저장
            signatureMap.put("signatureDate", toIsoString(signatureEntry.getSignatureDate()));

// 기존 리스트가 있으면 append, 없으면 새 리스트 생성
            List<Map<String, Object>> existing = formData.getSignatures().get(signerType);
            List<Map<String, Object>> mutable;
            if (existing == null) {
                mutable = new ArrayList<>();
            } else {
                mutable = new ArrayList<>(existing);
            }
            mutable.add(signatureMap);
            formData.getSignatures().put(signerType, mutable);

            //application.setFormDataJson(objectMapper.writeValueAsString(formData));
            application.setFormDataJson(objectMapper.writeValueAsString(formData));
        } catch (JsonProcessingException e) {
            log.error("Failed to update formDataJson for leave application ID: {}", leaveApplicationId, e);
            throw new RuntimeException("휴가원 폼 데이터 업데이트 실패", e);
        }

        // 서명 플래그 업데이트
        switch (signerType) {
            case "applicant":
                application.setIsApplicantSigned(true);
                break;
            case "substitute":
                application.setIsSubstituteApproved(true);
                break;
            case "departmentHead":
                application.setIsDeptHeadApproved(true);
                break;
            case "centerDirector":
                application.setIsCenterDirectorApproved(true);
                break;
            case "adminDirector":
                application.setIsAdminDirectorApproved(true);
                break;
            case "ceoDirector":
                application.setIsCeoDirectorApproved(true);
                break;
            case "hrStaff":
                application.setIsHrStaffApproved(true);
                break;
        }

        application.setUpdatedAt(LocalDateTime.now());
        LeaveApplication savedApplication = leaveApplicationRepository.save(application);

        return getLeaveApplicationDetail(savedApplication.getId(), signerId);
    }

    /**
     * LeaveApplication을 위한 PDF 관련 메서드 (FormService로 위임)
     */
    public byte[] getLeaveApplicationPdfBytes(Long leaveApplicationId) {
        LeaveApplication application = getOrThrow(leaveApplicationId);
        if (!application.isPrintable()) {
            throw new IllegalStateException("해당 휴가원은 아직 인쇄 가능한 상태가 아닙니다.");
        }
        try {
            return formService.getLeaveApplicationPdfBytes(application);
        } catch (IOException e) {
            log.error("Leave application PDF 생성 중 오류 발생: id={}, error={}", leaveApplicationId, e.getMessage());
            throw new RuntimeException("PDF 생성에 실패했습니다.", e);
        }
    }

    /**
     * 휴가원 조회 권한 확인
     */
    private boolean canView(UserEntity viewer, LeaveApplication application, UserEntity applicant) {
        // 1. 신청자 본인
        if (viewer.getUserId().equals(application.getApplicantId())) {
            return true;
        }
        // 2. 대직자
        if (application.getSubstituteId() != null && viewer.getUserId().equals(application.getSubstituteId())) {
            return true;
        }
        // 3. 현재 승인 단계의 승인자 또는 상위 승인자 (권한 있는 자)
        // applicant 파라미터를 사용하여 canApprove의 DB 호출을 피함
        if (application.getCurrentApprovalStep() != null && canApprove(viewer, application, applicant)) {
            return true;
        }
        // 4. 시스템 관리자
        if (viewer.isAdmin()) {
            return true;
        }
        // 5. 기타 상위 관리자 (부서장 예시)
        if ("1".equals(viewer.getJobLevel()) && viewer.getDeptCode().equals(applicant.getDeptCode())) {
            return true;
        }
        return false;
    }

    @Transactional
    public void setSubstitute(Long id, String substituteId) {
        LeaveApplication app = getOrThrow(id);
        app.setSubstituteId(substituteId);
        leaveApplicationRepository.save(app);
    }

    /**
     * 특정 승인자가 해당 휴가원을 승인할 수 있는지 확인 (권한 로직)
     * 휴가원 자체를 받아서 신청자 정보를 서비스에서 가져와 비교하도록 수정
     */
    private boolean canApprove(UserEntity approver, LeaveApplication application, UserEntity applicant) {
        if (approver == null || applicant == null) return false;
        String step = application.getCurrentApprovalStep();
        if (step == null) return false;

        switch (step) {
            case "SUBSTITUTE_APPROVAL":
                return approver.getUserId().equals(application.getSubstituteId());
            case "DEPARTMENT_HEAD_APPROVAL":
                return "1".equals(approver.getJobLevel())
                        && approver.getDeptCode().equals(applicant.getDeptCode());
            case "HR_STAFF_APPROVAL":
                Set<PermissionType> approverPermissions = permissionService.getAllUserPermissions(approver.getUserId());
                return ("0".equals(approver.getJobLevel()) ||"1".equals(approver.getJobLevel()))
                        && approverPermissions.contains(PermissionType.HR_LEAVE_APPLICATION)
                        && approver.isAdmin();
            case "CENTER_DIRECTOR_APPROVAL":
                return "2".equals(approver.getJobLevel());
            case "HR_FINAL_APPROVAL":
                Set<PermissionType> finalApproverPermissions = permissionService.getAllUserPermissions(approver.getUserId());
                return ("0".equals(approver.getJobLevel()) || "1".equals(approver.getJobLevel()))
                        && finalApproverPermissions.contains(PermissionType.HR_LEAVE_APPLICATION)
                        && approver.isAdmin();
            case "ADMIN_DIRECTOR_APPROVAL":
                return "4".equals(approver.getJobLevel());
            case "CEO_DIRECTOR_APPROVAL":
                return "5".equals(approver.getJobLevel());
            default:
                return false;
        }
    }

    // 2) 기존 시그니처는 호환을 위해 남겨두되, 내부에서 applicant를 한 번만 로드하고 위 오버로드로 위임
    private boolean canApprove(UserEntity approver, LeaveApplication application) {
        Optional<UserEntity> applicantOpt = userRepository.findByUserId(application.getApplicantId());

        if (applicantOpt.isEmpty()) {
            return false;
        }
        // 오버로드된 canApprove 메서드를 호출하여 로직 재활용
        return canApprove(approver, application, applicantOpt.get());
    }

    public Page<LeaveApplicationResponseDto> getCompletedApplications(String userId, Pageable pageable) {
        UserEntity currentUser = userService.getUserInfo(userId);
        Page<LeaveApplication> page;

        Set<PermissionType> currentUserPermissions = permissionService.getAllUserPermissions(currentUser.getUserId());
        boolean isHrStaff = currentUser.isAdmin() && ("0".equals(currentUser.getJobLevel()) || "1".equals(currentUser.getJobLevel()))
                && currentUserPermissions.contains(PermissionType.HR_LEAVE_APPLICATION);

        boolean isCenterDirector = currentUser.isAdmin() && currentUser.getJobLevel() != null && Integer.parseInt(currentUser.getJobLevel()) >= 2;

        if (isHrStaff || isCenterDirector) {
            page = leaveApplicationRepository.findByStatus(LeaveApplicationStatus.APPROVED, pageable);
        } else {
            page = leaveApplicationRepository.findByApplicantIdAndStatusWithPaging(userId, LeaveApplicationStatus.APPROVED, pageable);
        }

        // Map to DTO
        return page.map(app -> {
            UserEntity applicant = userService.getUserInfo(app.getApplicantId());
            UserEntity substitute = app.getSubstituteId() != null ? userService.getUserInfo(app.getSubstituteId()) : null;
            return LeaveApplicationResponseDto.fromEntity(app, applicant, substitute);
        });
    }

    /**
     * 신청자 본인 완료된 휴가원 조회
     */
    public Page<LeaveApplicationResponseDto> getCompletedApplicationsByApplicant(String applicantId, Pageable pageable) {
        Page<LeaveApplication> page = leaveApplicationRepository.findByApplicantIdAndStatusWithPaging(applicantId, LeaveApplicationStatus.APPROVED, pageable);

        // Map to DTO
        return page.map(app -> {
            UserEntity applicant = userService.getUserInfo(app.getApplicantId());
            UserEntity substitute = app.getSubstituteId() != null ? userService.getUserInfo(app.getSubstituteId()) : null;

            // ✅ NullPointerException 방지를 위해 null-safe하게 처리
            if (applicant == null) {
                // 신청자 정보가 없는 경우, 임시 DTO를 반환하거나 에러 로그를 남깁니다.
                log.warn("신청자 ID에 해당하는 사용자 정보가 없습니다: {}", app.getApplicantId());
                // 대체 객체를 생성하거나, null을 안전하게 처리할 수 있는 DTO를 반환합니다.
                return LeaveApplicationResponseDto.fromEntity(app, null, substitute);
            }

            return LeaveApplicationResponseDto.fromEntity(app, applicant, substitute);
        });
    }

    @Transactional
    @CacheEvict(value = "userCache", key = "#result.applicantId")
    public LeaveApplication approveWithApprovalLine(
            Long id,
            String approverId,
            String comment,
            String signatureImageUrl,
            boolean isFinalApproval
    ) {
        LeaveApplication application = getOrThrow(id);

        if (!application.isUsingApprovalLine()) {
            throw new IllegalStateException("결재라인을 사용하지 않는 휴가원입니다.");
        }

        DocumentApprovalProcess process = processRepository
                .findByDocumentIdAndDocumentType(id, DocumentType.LEAVE_APPLICATION)
                .orElseThrow(() -> new EntityNotFoundException("결재 프로세스를 찾을 수 없습니다."));

        String currentStepBeforeApproval = application.getCurrentApprovalStep();
        Integer currentStepOrderBeforeApproval = application.getCurrentStepOrder();

        // 승인 처리
        approvalProcessService.approveStep(
                process.getId(),
                approverId,
                comment,
                signatureImageUrl,
                isFinalApproval
        );

        // 전결 처리 로직
        if (isFinalApproval) {
            application.setIsFinalApproved(true);
            application.setFinalApprovalStep(currentStepBeforeApproval);
            application.setFinalApproverId(approverId);
            application.setFinalApprovalDate(LocalDateTime.now());

            List<String> remainingSteps = approvalProcessService.getRemainingSteps(
                    process.getId(),
                    currentStepOrderBeforeApproval
            );

            for (String step : remainingSteps) {
                String signatureType = getSignatureTypeFromStep(step);
                if (signatureType != null) {
                    updateSignatureForFinalApproval(
                            application,
                            signatureType,
                            "전결처리!",
                            LocalDateTime.now().toString(),
                            approverId
                    );
                }
            }
        }

        // 프로세스 재조회
        process = processRepository.findById(process.getId()).orElseThrow();

        // ✅ 수정: 최종 승인 완료 시 연차 차감
        if (process.getStatus() == ApprovalProcessStatus.APPROVED) {
            application.setStatus(LeaveApplicationStatus.APPROVED);
            application.setPrintable(true);
            application.setUpdatedAt(LocalDateTime.now());

            // ✅ 먼저 application 저장 (flush하여 DB에 반영)
            LeaveApplication savedApp = leaveApplicationRepository.saveAndFlush(application);

            // ✅ [추가] 연차 여부 확인
            boolean isAnnualLeave = false;
            try {
                Map<String, Object> formData = objectMapper.readValue(savedApp.getFormDataJson(), new TypeReference<Map<String, Object>>() {});
                List<String> leaveTypes = (List<String>) formData.get("leaveTypes");
                if (leaveTypes != null && leaveTypes.contains("연차휴가")) {
                    isAnnualLeave = true;
                }
            } catch (Exception e) {
                log.warn("leaveTypes 파싱 실패: {}", e.getMessage());
            }

            // ✅ 연차인 경우에만 차감
            if (isAnnualLeave && savedApp.getTotalDays() != null && savedApp.getTotalDays() > 0) {
                try {
                    deductVacationDaysInSameTransaction(savedApp.getApplicantId(), savedApp.getTotalDays());
                } catch (Exception e) {
                    log.error("연차 차감 실패: {}", e.getMessage(), e);
                    throw new IllegalStateException("연차 차감 실패: " + e.getMessage());
                }
            }

            return savedApp;
        }

        application.setUpdatedAt(LocalDateTime.now());
        return leaveApplicationRepository.save(application);
    }

    /**
     * ✅ 같은 트랜잭션 내에서 연차 차감 (REQUIRES_NEW 제거)
     */
    private void deductVacationDaysInSameTransaction(String applicantId, Double days) {
        // ✅ 비관적 락을 사용하여 UserEntity 조회
        UserEntity applicant = userRepository.findByUserIdWithLock(applicantId)
                .orElseThrow(() -> new EntityNotFoundException("신청자를 찾을 수 없습니다."));

        try {
            applicant.useVacationDays(days);
            userRepository.save(applicant);
            log.info("연차 차감 완료: userId={}, totalDays={}, remaining={}",
                    applicant.getUserId(),
                    days,
                    applicant.getRemainingAnnualLeave());
        } catch (IllegalStateException e) {
            log.error("연차 차감 실패: {}", e.getMessage());
            throw new IllegalStateException("연차 차감 실패: " + e.getMessage());
        }
    }

    /**
     * 전결 처리 시 잔여 단계의 서명 필드를 강제로 업데이트합니다.
     */
    private void updateSignatureForFinalApproval(
            LeaveApplication application,
            String signatureType,
            String text,
            String signatureDate,
            String finalApproverId  // ✅ 추가: 전결 처리자 ID
    ) {
        try {
            Map<String, Object> formData;
            if (application.getFormDataJson() != null && !application.getFormDataJson().isEmpty()) {
                formData = objectMapper.readValue(
                        application.getFormDataJson(),
                        new TypeReference<Map<String, Object>>() {}
                );
            } else {
                formData = new HashMap<>();
            }

            // signatures 맵 가져오기 또는 생성
            Map<String, List<Map<String, Object>>> signatures =
                    (Map<String, List<Map<String, Object>>>) formData.get("signatures");
            if (signatures == null) {
                signatures = new HashMap<>();
            }

            // ✅ 전결 처리 서명 데이터 생성
            Map<String, Object> newSignature = new HashMap<>();
            newSignature.put("text", text); // "전결처리!"
            newSignature.put("imageUrl", null);
            newSignature.put("isSigned", false);  // ✅ 실제 서명이 아니므로 false
            newSignature.put("isSkipped", true);   // ✅ 건너뛴 단계
            newSignature.put("isFinalApproval", true);  // ✅ 전결로 처리됨
            newSignature.put("signatureDate", toIsoString(signatureDate));
            newSignature.put("skippedBy", finalApproverId);  // ✅ 전결 처리한 사람
            newSignature.put("skippedReason", "전결 승인으로 생략됨");

            // ✅ 전결 처리자 정보 추가
            try {
                UserEntity finalApprover = userRepository.findByUserId(finalApproverId)
                        .orElse(null);
                if (finalApprover != null) {
                    newSignature.put("skippedByName", finalApprover.getUserName());
                    newSignature.put("signerId", finalApproverId);
                    newSignature.put("signerName", finalApprover.getUserName());
                } else {
                    newSignature.put("signerId", "system");
                    newSignature.put("signerName", "시스템");
                }
            } catch (Exception e) {
                log.warn("전결 처리자 정보 조회 실패", e);
                newSignature.put("signerId", "system");
                newSignature.put("signerName", "시스템");
            }

            // ✅ 서명 리스트 생성 및 저장
            List<Map<String, Object>> signatureList = new ArrayList<>();
            signatureList.add(newSignature);
            signatures.put(signatureType, signatureList);

            // ✅ formData에 signatures 저장
            formData.put("signatures", signatures);

            // ✅ JSON 저장
            application.setFormDataJson(objectMapper.writeValueAsString(formData));

        } catch (JsonProcessingException e) {
            log.error("전결처리 서명 업데이트 실패: applicationId={}, signatureType={}",
                    application.getId(), signatureType, e);
        }
    }

    /**
     * ✅ 새 메서드: 결재라인 기반 반려 처리
     */
    @Transactional
    @CacheEvict(value = "userCache", key = "#result.applicantId")
    public LeaveApplication rejectWithApprovalLine(
            Long id,
            String approverId,
            String rejectionReason
    ) {
        LeaveApplication application = getOrThrow(id);

        if (!application.isUsingApprovalLine()) {
            throw new IllegalStateException("결재라인을 사용하지 않는 휴가원입니다.");
        }

        DocumentApprovalProcess process = processRepository
                .findByDocumentIdAndDocumentType(id, DocumentType.LEAVE_APPLICATION)
                .orElseThrow(() -> new EntityNotFoundException("결재 프로세스를 찾을 수 없습니다."));

        approvalProcessService.rejectStep(process.getId(), approverId, rejectionReason);

        // ✅ [추가] APPROVED 상태의 연차휴가를 반려하는 경우 복구
        if (application.getStatus() == LeaveApplicationStatus.APPROVED
                && application.getLeaveType() == LeaveType.ANNUAL_LEAVE
                && application.getTotalDays() != null
                && application.getTotalDays() > 0) {

            try {
                restoreVacationDaysInSameTransaction(application.getApplicantId(), application.getTotalDays());
                log.info("결재라인 반려 시 연차 복구: userId={}, days={}",
                        application.getApplicantId(), application.getTotalDays());
            } catch (Exception e) {
                log.error("연차 복구 실패", e);
                throw new IllegalStateException("연차 복구 실패: " + e.getMessage());
            }
        }

        application.setStatus(LeaveApplicationStatus.REJECTED);
        application.setRejectionReason(rejectionReason);
        application.setPrintable(false);
        application.setUpdatedAt(LocalDateTime.now());

        return leaveApplicationRepository.save(application);
    }


    /**
     * ✅ 같은 트랜잭션 내에서 연차 복구
     */
    private void restoreVacationDaysInSameTransaction(String userId, Double days) {
        UserEntity user = userRepository.findByUserIdWithLock(userId)
                .orElseThrow(() -> new EntityNotFoundException("사용자를 찾을 수 없습니다: " + userId));

        try {
            user.restoreVacationDays(days);
            userRepository.save(user);
            log.info("연차 복구 완료: userId={}, restoredDays={}, remaining={}",
                    user.getUserId(), days, user.getRemainingAnnualLeave());
        } catch (Exception e) {
            log.error("연차 복구 실패: {}", e.getMessage());
            throw new IllegalStateException("연차 복구 실패: " + e.getMessage());
        }
    }

    @Transactional
    @CacheEvict(value = "userCache", key = "#result.applicantId")
    public LeaveApplication cancelApprovedLeaveApplication(
            Long applicationId,
            String requesterId,
            String cancellationReason
    ) {
        // ✅ 권한 확인: HR_LEAVE_APPLICATION 권한만 체크
        Set<PermissionType> permissions = permissionService.getAllUserPermissions(requesterId);
        if (!permissions.contains(PermissionType.HR_LEAVE_APPLICATION)) {
            throw new AccessDeniedException("완료된 휴가원을 취소할 권한이 없습니다.");
        }

        // ✅ 휴가원 조회 및 상태 확인
        LeaveApplication application = leaveApplicationRepository.findById(applicationId)
                .orElseThrow(() -> new EntityNotFoundException("휴가원을 찾을 수 없습니다."));

        if (application.getStatus() != LeaveApplicationStatus.APPROVED) {
            throw new IllegalStateException("승인 완료된 휴가원만 취소할 수 있습니다.");
        }

        // ✅ ANNUAL_LEAVE만 복구
        if (application.getLeaveType() == LeaveType.ANNUAL_LEAVE && application.getTotalDays() != null && application.getTotalDays() > 0) {
            UserEntity applicant = userRepository.findByUserIdWithLock(application.getApplicantId())
                    .orElseThrow(() -> new EntityNotFoundException("신청자를 찾을 수 없습니다."));

            try {
                applicant.restoreVacationDays(application.getTotalDays());
                userRepository.save(applicant);
                log.info("연차 복구 완료: userId={}, days={}", applicant.getUserId(), application.getTotalDays());
            } catch (Exception e) {
                log.error("연차 복구 실패", e);
                throw new IllegalStateException("연차 복구 중 오류가 발생했습니다.");
            }
        }

        // ✅ 상태 변경
        application.setStatus(LeaveApplicationStatus.REJECTED);
        application.setRejectionReason("관리자 취소: " + cancellationReason);
        application.setPrintable(false);

        return leaveApplicationRepository.save(application);
    }

    private LeaveApplicationResponseDto enrichDtoWithDeptName(
            LeaveApplication app,
            LeaveApplicationResponseDto dto,
            UserEntity applicant // 꼭 applicant를 전달하도록 변경
    ) {
        try {
            if (applicant != null) {
                String applicantDeptCode = applicant.getDeptCode(); // UserEntity에서 가져옴 (예: "OS1")
                dto.setApplicantDept(applicantDeptCode); // 기존 필드도 안전하게 채움

                if (applicantDeptCode != null && !applicantDeptCode.isEmpty()) {
                    String baseDeptCode = applicantDeptCode.replaceAll("\\d+$", ""); // 숫자 제거 (OS1 -> OS)
                    String deptName = departmentRepository.findByDeptCode(baseDeptCode)
                            .map(d -> d.getDeptName())
                            .orElse(applicantDeptCode); // 조회 실패 시 원래 코드 보존
                    dto.setApplicantDeptName(deptName);
                } else {
                    dto.setApplicantDeptName(null);
                }
            } else {
                // applicant null이면 기존 엔티티에서 dept code 가져올 수 없음 -> 안전하게 처리
                dto.setApplicantDeptName(null);
            }
        } catch (Exception e) {
            log.warn("부서명 조회 실패: deptCode={}, error={}",
                    applicant != null ? applicant.getDeptCode() : "null", e.getMessage());
            dto.setApplicantDeptName(applicant != null ? applicant.getDeptCode() : null);
        }
        return dto;
    }
}