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
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;
import sunhan.sunhanbackend.dto.request.LeaveSummaryDto;
import sunhan.sunhanbackend.dto.response.AttachmentResponseDto;
import sunhan.sunhanbackend.dto.response.LeaveApplicationResponseDto;
import sunhan.sunhanbackend.dto.request.LeaveApplicationUpdateFormRequestDto;
import sunhan.sunhanbackend.dto.request.SignLeaveApplicationRequestDto;
import sunhan.sunhanbackend.dto.response.ReportsResponseDto;
import sunhan.sunhanbackend.entity.mysql.LeaveApplication;
import sunhan.sunhanbackend.entity.mysql.LeaveApplicationAttachment;
import sunhan.sunhanbackend.entity.mysql.UserEntity;
import sunhan.sunhanbackend.enums.*;
import sunhan.sunhanbackend.repository.mysql.LeaveApplicationAttachmentRepository;
import sunhan.sunhanbackend.repository.mysql.LeaveApplicationRepository;
import sunhan.sunhanbackend.repository.mysql.UserRepository;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.stream.Collectors.toList;

@Service
@Slf4j
@RequiredArgsConstructor
public class LeaveApplicationService {

    private final LeaveApplicationRepository leaveApplicationRepository;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;
    private final FormService formService;
    private final UserService userService;
    private static final DateTimeFormatter ISO_LOCAL = DateTimeFormatter.ISO_LOCAL_DATE_TIME;
    private final PdfGenerationService pdfGenerationService; // 비동기 서비스 주입
    private final PermissionService permissionService;
    private final LeaveApplicationAttachmentRepository attachmentRepository;
    @Value("${file.upload-dir}") // application.properties에 경로 설정 필요
    private String uploadDir;
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
        // Optional.ofNullable을 사용해 널 안전하게 조회
        UserEntity signer = userRepository.findByUserId(userId).orElse(null);
        UserEntity applicant = userRepository.findByUserId(app.getApplicantId()).orElse(null);

        // 사용자가 존재하지 않으면 즉시 false 반환
        if (signer == null || applicant == null) return false;
        log.debug("서명자 ID: {}, jobLevel: '{}', deptCode: '{}', isAdmin: {}",
                signer.getUserId(), signer.getJobLevel(), signer.getDeptCode(), signer.isAdmin());
        String step = app.getCurrentApprovalStep();
        switch (signatureType) {
            case "applicant":
                return userId.equals(app.getApplicantId()) && app.getStatus() == LeaveApplicationStatus.DRAFT;
            case "substitute":
                return userId.equals(app.getSubstituteId()) && "SUBSTITUTE_APPROVAL".equals(step);
            case "departmentHead":
                return "1".equals(signer.getJobLevel())
                        && signer.getDeptCode().equals(applicant.getDeptCode())
                        && "DEPARTMENT_HEAD_APPROVAL".equals(step);
            case "hrStaff":
                return ("0".equals(signer.getJobLevel()) || "1".equals(signer.getJobLevel()))
                        && permissionService.hasPermission(userId, PermissionType.HR_LEAVE_APPLICATION)
                        && signer.isAdmin()
                        && "HR_STAFF_APPROVAL".equals(step);
            case "centerDirector":
                return "2".equals(signer.getJobLevel())
                        && "CENTER_DIRECTOR_APPROVAL".equals(step);
            case "adminDirector":
                return "4".equals(signer.getJobLevel())
                        && "ADMIN_DIRECTOR_APPROVAL".equals(step);
            case "ceoDirector":
                return "5".equals(signer.getJobLevel())
                        && "CEO_DIRECTOR_APPROVAL".equals(step);
            default:
                return false;
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
     * 휴가원 제출 (드래프트 -> 첫 승인 단계)
     */
    @Transactional
    public LeaveApplication submitLeaveApplication(Long id, String userId) {
        LeaveApplication application = getOrThrow(id);

        if (!application.getApplicantId().equals(userId)) {
            throw new AccessDeniedException("휴가원 작성자만 제출할 수 있습니다.");
        }
        if (application.getStatus() != LeaveApplicationStatus.DRAFT && application.getStatus() != LeaveApplicationStatus.REJECTED) {
            throw new IllegalStateException("드래프트 또는 반려 상태의 휴가원만 제출할 수 있습니다.");
        }

        UserEntity applicant = userRepository.findByUserId(userId)
                .orElseThrow(() -> new EntityNotFoundException("신청자 정보를 찾을 수 없습니다: " + userId));

        if (applicant == null) {
            throw new EntityNotFoundException("신청자 정보를 찾을 수 없습니다: " + userId);
        }

        try {
            // formDataJson을 Map으로 파싱
            Map<String, Object> formDataMap = new HashMap<>();
            if (application.getFormDataJson() != null && !application.getFormDataJson().isEmpty()) {
                formDataMap = objectMapper.readValue(application.getFormDataJson(), new TypeReference<Map<String, Object>>() {});
            }

            // 폼 데이터에서 DTO 변환
            LeaveApplicationUpdateFormRequestDto formDataDto = objectMapper.convertValue(formDataMap, LeaveApplicationUpdateFormRequestDto.class);

            // 폼 데이터가 없거나 부족한 경우 기본값 설정
            if (formDataDto == null) {
                throw new IllegalArgumentException("휴가원 폼 데이터가 없습니다. 먼저 휴가 정보를 입력해주세요.");
            }

            // 필수 데이터 검증
            if (formDataDto.getLeaveTypes() == null || formDataDto.getLeaveTypes().isEmpty()) {
                throw new IllegalArgumentException("휴가 종류를 선택해주세요.");
            }
            // 연차휴가인지 확인
            boolean isAnnualLeave = formDataDto.getLeaveTypes().contains("연차휴가");
            // 연차휴가인 경우에만 totalDays 검증
            if (isAnnualLeave) {
                if (formDataDto.getTotalDays() == null || formDataDto.getTotalDays() <= 0) {
                    throw new IllegalArgumentException("연차휴가 신청 시 휴가 기간을 입력해주세요.");
                }
            } else {
                // 연차휴가가 아닌 경우, totalDays가 없거나 0이어도 허용
                // 다만, 날짜는 여전히 필요할 수 있으므로 날짜 검증은 별도로 수행
                boolean hasValidDates = false;

                // 개별 기간 확인
                if (formDataDto.getFlexiblePeriods() != null) {
                    for (Map<String, String> period : formDataDto.getFlexiblePeriods()) {
                        if (period.get("startDate") != null && !period.get("startDate").isEmpty() &&
                                period.get("endDate") != null && !period.get("endDate").isEmpty()) {
                            hasValidDates = true;
                            break;
                        }
                    }
                }

                // 연속 기간 확인
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

            if (formDataDto.getTotalDays() == null || formDataDto.getTotalDays() <= 0) {
                throw new IllegalArgumentException("휴가 기간을 입력해주세요.");
            }

            // 신청자 서명 검증 - 서명 데이터에서 확인
            boolean hasApplicantSignature = false;
            if (formDataDto.getSignatures() != null && formDataDto.getSignatures().get("applicant") != null) {
                List<Map<String, Object>> applicantSigs = formDataDto.getSignatures().get("applicant");
                if (!applicantSigs.isEmpty()) {
                    Map<String, Object> firstSig = applicantSigs.get(0);
                    hasApplicantSignature = Boolean.TRUE.equals(firstSig.get("isSigned"));
                }
            }

            if (!hasApplicantSignature) {
                throw new IllegalStateException("신청자 서명이 완료되지 않았습니다. 서명을 먼저 진행해주세요.");
            }

            // 폼 데이터 기반으로 휴가 정보 업데이트
            updateApplicationFromDto(application, formDataDto);

            // 폼 데이터에서 applicationDate 값을 가져와 엔티티에 설정
            if (formDataDto.getApplicationDate() != null) {
                application.setApplicationDate(formDataDto.getApplicationDate());
            }

            // 첫 승인 단계 및 승인자 설정 로직
            String substituteId = application.getSubstituteId();
            String initialStep = determineInitialApprovalStep(applicant.getJobLevel(), substituteId != null);

            application.setCurrentApprovalStep(initialStep);
            application.setStatus(getPendingStatusForStep(initialStep));

            // 다음 승인자 ID 설정
            String nextApproverId = findNextApproverId(initialStep, applicant, substituteId);
            application.setCurrentApproverId(nextApproverId);

            // 신청자가 서명했다는 플래그 설정
            application.setIsApplicantSigned(true);

            // 6) **서명 데이터 보존**: formDataDto.signatures 포함해 JSON 직렬화
            String mergedJson = objectMapper.writeValueAsString(formDataDto);
            application.setFormDataJson(mergedJson);

        } catch (JsonProcessingException e) {
            log.error("Failed to parse formDataJson for submission: {}", e.getMessage());
            throw new IllegalArgumentException("제출용 폼 데이터 형식이 올바르지 않습니다.");
        } catch (DateTimeParseException e) {
            log.error("날짜 파싱 오류: 폼 데이터의 날짜 형식이 잘못되었습니다.", e);
            throw new IllegalArgumentException("날짜 형식이 올바르지 않거나 비어있습니다. 날짜를 확인해주세요.", e);
        }
        return leaveApplicationRepository.save(application);
    }

    /**
     * 전결 승인 처리 메서드 수정
     */
    @Transactional
    @CacheEvict(value = "userCache", key = "#approverId")
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
        boolean isHrAdmin = approver.getRole() == Role.ADMIN &&
                permissionService.hasPermission(approverId, PermissionType.HR_LEAVE_APPLICATION);


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
        LeaveApplication saved = leaveApplicationRepository.save(application);
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

        // ★★★ 연차휴가가 아닌 경우 totalDays를 0으로 설정하고 일수 계산 로직 건너뛰기 ★★★
        boolean isAnnualLeave = dto.getLeaveTypes() != null &&
                dto.getLeaveTypes().contains("연차휴가") &&
                matchedType == LeaveType.ANNUAL_LEAVE;

        if (!isAnnualLeave) {
            application.setTotalDays(0.0);
            // 시작일/종료일은 여전히 설정 (다른 휴가 유형도 날짜 정보는 필요할 수 있음)
            setStartEndDatesFromDto(application, dto);
            return; // 일수 계산 로직 건너뛰고 메서드 종료
        }

        // 연차휴가인 경우에만 아래 일수 계산 로직 실행
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
                throw new IllegalArgumentException("연차휴가 신청 시 유효한 휴가 기간이 입력되어야 합니다.");
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
    public LeaveApplication approveLeaveApplication(Long id, String approverId, String signatureDate) {
        LeaveApplication application = getOrThrow(id);

        // 2. 승인자 및 신청자 정보 조회
        UserEntity approver = userRepository.findByUserId(approverId)
                .orElseThrow(() -> new EntityNotFoundException("승인자 정보를 찾을 수 없습니다: " + approverId));
        UserEntity applicant = userRepository.findByUserId(application.getApplicantId())
                .orElseThrow(() -> new EntityNotFoundException("신청자 정보를 찾을 수 없습니다: " + application.getApplicantId()));

        // 3. 승인 권한 확인 로직
        boolean isAuthorized = false;

        // case 1: 현재 지정된 승인자와 요청한 승인자가 일치하는 경우
        // 이는 '개인 결재'를 의미합니다 (예: 대직자, 부서장 등)
        if (approverId.equals(application.getCurrentApproverId())) {
            isAuthorized = true;
        }
        // case 2: 그룹 승인 로직 (현재 승인자가 지정되지 않은 특정 단계)
        // 예: 인사팀(AD)의 그룹 결재
        else if (LeaveApplicationStatus.PENDING_HR_STAFF.equals(application.getStatus())) {
            // 인사팀(AD) 부서 소속이며, 특정 권한을 가진 사용자인지 확인
            // 예시: jobLevel 0 또는 1
            boolean isHrGroupApprover = Arrays.asList("0", "1").contains(approver.getJobLevel()) &&
                    permissionService.hasPermission(approverId, PermissionType.HR_LEAVE_APPLICATION);
            if (isHrGroupApprover) {
                isAuthorized = true;
            }
        }
        // case 3: (추가) 관리자(admin) 권한으로 강제 승인하는 로직
        // 필요에 따라 추가할 수 있습니다. 예를 들어, is_admin 필드나 jobLevel이 "0"인 경우
        else if ("0".equals(approver.getJobLevel()) || approver.isAdmin()) {
            isAuthorized = true;
        }


        if (!isAuthorized) {
            throw new AccessDeniedException("승인 권한이 없거나 처리할 단계가 아닙니다.");
        }

        // **중요: 기존 서명 데이터를 보존하면서 현재 단계 서명 추가**
        preserveAndUpdateSignatures(application, application.getCurrentApprovalStep(), approver, signatureDate);

        // 현재 단계 승인 처리
        completeCurrentApprovalStep(application, application.getCurrentApprovalStep());

        // 다음 승인 단계 결정
        String nextStep = determineNextApprovalStepAfter(
                application.getCurrentApprovalStep(),
                applicant.getJobLevel(),
                application.getApplicantId(),
                application.getCurrentApproverId()); // 이 인수는 사용되지 않을 수 있으므로 확인 필요

        if (nextStep == null || "APPROVED".equals(nextStep)) {
            // 최종 승인
            application.setStatus(LeaveApplicationStatus.APPROVED);
            application.setPrintable(true);
            application.setCurrentApprovalStep("APPROVED");
            application.setCurrentApproverId(null);
        } else {
            // 다음 단계로 전환
            String nextApproverId = findNextApproverId(nextStep, applicant, application.getSubstituteId());
            application.setCurrentApprovalStep(nextStep);
            application.setStatus(getPendingStatusForStep(nextStep));
            application.setCurrentApproverId(nextApproverId);
        }

        application.setUpdatedAt(LocalDateTime.now());
        appendApprovalHistory(application, approver.getUserName(), approver.getJobLevel());

        return leaveApplicationRepository.save(application);
    }

    /**
     * 기존 서명 데이터를 보존하면서 현재 단계의 서명을 추가/업데이트하는 메서드
     */
    private void preserveAndUpdateSignatures(LeaveApplication application, String currentStep, UserEntity approver, String signatureDate) {
        try {
            // 현재 formDataJson에서 서명 데이터 가져오기
            Map<String, List<Map<String, Object>>> existingSignatures = new HashMap<>();

            if (application.getFormDataJson() != null && !application.getFormDataJson().isEmpty()) {
                Map<String, Object> formData = objectMapper.readValue(
                        application.getFormDataJson(),
                        new TypeReference<Map<String, Object>>() {}
                );

                Object signaturesObject = formData.get("signatures");
                if (signaturesObject != null) {
                    existingSignatures = objectMapper.convertValue(
                            signaturesObject,
                            new TypeReference<Map<String, List<Map<String, Object>>>>() {}
                    );
                }
            }

            // 현재 단계에 해당하는 서명 타입 결정
            String signatureType = getSignatureTypeFromStep(currentStep);

            if (signatureType != null) {
                // 현재 단계의 서명이 이미 있는지 확인
                List<Map<String, Object>> currentSignatures = existingSignatures.get(signatureType);
                if (currentSignatures == null || currentSignatures.isEmpty() ||
                        !Boolean.TRUE.equals(currentSignatures.get(0).get("isSigned"))) {

                    // 서명이 없거나 완료되지 않은 경우에만 추가
                    Map<String, Object> newSignature = new HashMap<>();
                    newSignature.put("text", "승인");
                    newSignature.put("imageUrl", approver.getSignimage());
                    newSignature.put("isSigned", true);
                    newSignature.put("signatureDate", toIsoString(signatureDate));

                    // ★★★ 추가: 실제 서명자의 사용자 ID를 저장 ★★★
                    newSignature.put("signerId", approver.getUserId());
                    newSignature.put("signerName", approver.getUserName());

                    // 기존 서명 리스트 존재하면 append, 아니면 새 리스트
                    if (currentSignatures == null) {
                        existingSignatures.put(signatureType, List.of(newSignature));
                    } else {
                        // 이미 서명 항목이 있고 첫 항목이 true이면 추가하지 않는 기존 정책을 유지하려면 조건 사용
                        boolean firstIsSigned = !currentSignatures.isEmpty() && Boolean.TRUE.equals(currentSignatures.get(0).get("isSigned"));
                        if (!firstIsSigned) {
                            List<Map<String, Object>> mut = new ArrayList<>(currentSignatures);
                            mut.add(0, newSignature); // 최근 서명을 앞에 넣고 싶으면 0, 아니면 add(last)
                            existingSignatures.put(signatureType, mut);
                        }
                    }
                }
            }

            // 전체 formData 재구성 (기존 데이터 유지)
            Map<String, Object> completeFormData;
            if (application.getFormDataJson() != null && !application.getFormDataJson().isEmpty()) {
                completeFormData = objectMapper.readValue(
                        application.getFormDataJson(),
                        new TypeReference<Map<String, Object>>() {}
                );
            } else {
                completeFormData = new HashMap<>();
            }

            // 서명 데이터만 업데이트
            completeFormData.put("signatures", existingSignatures);

            // JSON으로 저장
            application.setFormDataJson(objectMapper.writeValueAsString(completeFormData));

        } catch (Exception e) {
            log.error("서명 데이터 보존 중 오류 발생: {}", e.getMessage(), e);
            // 서명 보존 실패해도 승인 프로세스는 계속 진행
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

        // Optional을 사용하여 승인자 정보를 안전하게 조회하고, 없으면 예외를 발생시킵니다.
        UserEntity approver = userRepository.findByUserId(approverId)
                .orElseThrow(() -> new EntityNotFoundException("승인자 정보를 찾을 수 없습니다: " + approverId));

        // 반려 권한 확인 (승인 권한과 동일하게 확인)
        if (!canApprove(approver, application)) {
            throw new AccessDeniedException("해당 휴가원을 반려할 권한이 없습니다.");
        }

        application.setStatus(LeaveApplicationStatus.REJECTED);
        application.setRejectionReason(rejectionReason);
        application.setCurrentApprovalStep(null); // 반려 시 승인 단계 초기화
        application.setPrintable(false); // 반려 시 인쇄 불가
        application.setUpdatedAt(LocalDateTime.now());
        return leaveApplicationRepository.save(application);
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
        Page<LeaveApplication> applicationsPage = leaveApplicationRepository.findByApplicant_UserId(applicantId, pageable);

        // 엔티티를 DTO로 직접 변환하며 모든 필드를 채워줍니다.
        return applicationsPage.map(app -> {
            // LeaveApplicationResponseDto 객체를 직접 생성하고 필드를 설정합니다.
            // (LeaveApplicationResponseDto에 적절한 생성자나 빌더가 있다면 그것을 사용해도 됩니다.)
            LeaveApplicationResponseDto dto = new LeaveApplicationResponseDto();
            dto.setId(app.getId());
            dto.setStartDate(app.getStartDate());
            dto.setEndDate(app.getEndDate());
            dto.setTotalDays(app.getTotalDays());
            dto.setStatus(app.getStatus());
            // UserEntity가 null일 수 있는 상황을 안전하게 처리합니다.
            if (app.getApplicant() != null) {
                dto.setApplicantName(app.getApplicant().getUserName());
            }
            if (app.getSubstitute() != null) {
                dto.setSubstituteName(app.getSubstitute().getUserName());
            }

            // *** 중요: 누락되었던 생성일과 수정일 필드를 DTO에 추가합니다. ***
            dto.setCreatedAt(app.getCreatedAt());
            dto.setUpdatedAt(app.getUpdatedAt());

            // formDataJson도 필요하다면 여기서 설정할 수 있습니다.
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
                LeaveApplicationStatus.PENDING_SUBSTITUTE,
                LeaveApplicationStatus.PENDING_DEPT_HEAD,
                LeaveApplicationStatus.PENDING_CENTER_DIRECTOR,
                LeaveApplicationStatus.PENDING_HR_FINAL,
                LeaveApplicationStatus.PENDING_ADMIN_DIRECTOR,
                LeaveApplicationStatus.PENDING_CEO_DIRECTOR,
                LeaveApplicationStatus.PENDING_HR_STAFF
        );

        Page<LeaveApplication> page;

        // 3. 사용자가 인사팀("AD") 소속인지에 따라 다른 쿼리 로직을 실행합니다.
        if (permissionService.hasPermission(approverId, PermissionType.HR_LEAVE_APPLICATION)) {
            page = leaveApplicationRepository.findByStatusIn(
                    Set.of(LeaveApplicationStatus.PENDING_HR_STAFF, LeaveApplicationStatus.PENDING_HR_FINAL),
                    pageable
            );
        } else {
            page = leaveApplicationRepository.findByCurrentApproverIdAndStatusIn(
                    approverId,
                    pendingStatuses,
                    pageable
            );
        }

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
     * 신청자의 JobLevel에 따라 초기 승인 단계 결정
     */
    private String determineInitialApprovalStep(String applicantJobLevel, boolean hasSubstitute) {
        // jobLevel 0 (사원)
        if ("0".equals(applicantJobLevel)) {
            return hasSubstitute ? "SUBSTITUTE_APPROVAL" : "DEPARTMENT_HEAD_APPROVAL";
        }

        // jobLevel 1 (부서장) -> 진료센터장(2)으로 시작
        if ("1".equals(applicantJobLevel)) {
            return "HR_STAFF_APPROVAL";
        }

        // jobLevel 2 (진료센터장)
        // -> 작성자(센터장) 본인에게 보내면 안 됨. 요구하신 흐름 대로 센터장은 자신의 단계 건너뛰고
        // 바로 행정원장(4)로 요청하도록 변경.
        if ("2".equals(applicantJobLevel)) {
            return "ADMIN_DIRECTOR_APPROVAL";
        }

        // jobLevel 3 (원장)
        // -> 원장은 센터장(2)에게 먼저 보내고, 그 다음 행정원장(4) 으로 진행
        if ("3".equals(applicantJobLevel)) {
            return "CENTER_DIRECTOR_APPROVAL";
        }

        // jobLevel 4 -> 행정원장 승인부터
        if ("4".equals(applicantJobLevel)) {
            return "ADMIN_DIRECTOR_APPROVAL";
        }

        // jobLevel 5 이상 -> 대표원장 승인부터
        if ("5".equals(applicantJobLevel)) {
            return "CEO_DIRECTOR_APPROVAL";
        }

        // 기본값
        return "DEPARTMENT_HEAD_APPROVAL";
    }

    /**
     * [신규] 다음 승인자의 ID를 찾는 중앙화된 메서드
     */
    private String findNextApproverId(String step, UserEntity applicant, String substituteId) {
        String applicantId = applicant.getUserId();

        switch (step) {
            case "SUBSTITUTE_APPROVAL":
                return substituteId;

            case "DEPARTMENT_HEAD_APPROVAL": {
                // 같은 부서의 부서장(jobLevel=1), 신청자 제외
                Optional<UserEntity> deptHead = userRepository.findFirstByDeptCodeAndJobLevel(applicant.getDeptCode(), "1")
                        .filter(u -> !u.getUserId().equals(applicantId));
                if (deptHead.isPresent()) return deptHead.get().getUserId();

                // 부서장이 없거나 부서장이 신청자 본인인 경우 HR로 폴백
                Optional<UserEntity> hr = userRepository.findFirstByJobLevelAndDeptCodeAndRole("0", "AD", Role.ADMIN)
                        .filter(u -> !u.getUserId().equals(applicantId));
                if (hr.isPresent()) return hr.get().getUserId();

                throw new EntityNotFoundException("부서장 또는 인사 담당자를 찾을 수 없습니다.");
            }

            case "HR_STAFF_APPROVAL": {
                // 1. 현재 신청자(applicantId)의 사용자 정보 조회
                Optional<UserEntity> applicantUser = userRepository.findByUserId(applicantId);

                // 2. 신청자가 인사팀 권한이 있는 소속인지 확인
                boolean isApplicantInHR = applicantUser.isPresent() &&
                        permissionService.hasPermission(applicantUser.get().getUserId(), PermissionType.HR_LEAVE_APPLICATION);

                // 3. 신청자가 인사팀 소속일 경우, 다음 승인자를 null로 설정하여 그룹 승인 대기로 표시
                if (isApplicantInHR) {
                    // 이 로직은 실제 DB에 휴가원 상태를 PENDING_HR_STAFF로 업데이트하고
                    // currentApproverId를 null로 설정하는 비즈니스 로직을 호출해야 합니다.
                    return null; // 승인자가 없음을 반환
                }

                // 4. 신청자가 인사팀이 아닐 경우, 기존 로직 유지 (단일 인사 담당자 지정)
                List<String> hrJobLevels = Arrays.asList("0", "1");
                Optional<UserEntity> hr = userRepository.findFirstByJobLevelInAndDeptCodeAndRole(hrJobLevels, "AD", Role.ADMIN)
                        .filter(u -> !u.getUserId().equals(applicantId));
                if (hr.isPresent()) {
                    return hr.get().getUserId();
                }

                // 5. 인사 담당자가 없을 경우, 센터장으로 폴백
                Optional<UserEntity> center = userRepository.findFirstByJobLevel("2")
                        .filter(u -> !u.getUserId().equals(applicantId));
                if (center.isPresent()) {
                    return center.get().getUserId();
                }

                throw new EntityNotFoundException("인사 담당자 또는 진료센터장을 찾을 수 없습니다.");
            }

            case "CENTER_DIRECTOR_APPROVAL": {
                // 센터장 후보( jobLevel=2 ), 신청자 제외
                Optional<UserEntity> center = userRepository.findFirstByJobLevel("2")
                        .filter(u -> !u.getUserId().equals(applicantId));
                if (center.isPresent()) return center.get().getUserId();

                // 센터장이 없거나 신청자가 같은 레벨일 경우 행정원장으로 폴백
                Optional<UserEntity> admin = userRepository.findFirstByJobLevel("4")
                        .filter(u -> !u.getUserId().equals(applicantId));
                if (admin.isPresent()) return admin.get().getUserId();

                // 최후 폴백: 대표원장
                return userRepository.findFirstByJobLevel("5")
                        .filter(u -> !u.getUserId().equals(applicantId))
                        .map(UserEntity::getUserId)
                        .orElseThrow(() -> new EntityNotFoundException("승인자를 찾을 수 없습니다."));
            }

            case "HR_FINAL_APPROVAL": {
                List<String> hrJobLevels = Arrays.asList("0", "1");
                Optional<UserEntity> hr = userRepository.findFirstByJobLevelInAndDeptCodeAndRole(hrJobLevels, "AD", Role.ADMIN)
                        .filter(u -> !u.getUserId().equals(applicantId));
                if (hr.isPresent()) {
                    return hr.get().getUserId();
                }

                // 인사 담당자가 없을 경우, 행정원장으로 폴백
                Optional<UserEntity> admin = userRepository.findFirstByJobLevel("4")
                        .filter(u -> !u.getUserId().equals(applicantId));
                if (admin.isPresent()) return admin.get().getUserId();

                throw new EntityNotFoundException("최종 인사 담당자를 찾을 수 없습니다.");
            }

            case "ADMIN_DIRECTOR_APPROVAL": {
                Optional<UserEntity> admin = userRepository.findFirstByJobLevel("4")
                        .filter(u -> !u.getUserId().equals(applicantId));
                if (admin.isPresent()) return admin.get().getUserId();

                // 폴백: 대표원장
                return userRepository.findFirstByJobLevel("5")
                        .filter(u -> !u.getUserId().equals(applicantId))
                        .map(UserEntity::getUserId)
                        .orElseThrow(() -> new EntityNotFoundException("행정원장 또는 대표원장을 찾을 수 없습니다."));
            }

            case "CEO_DIRECTOR_APPROVAL": {
                return userRepository.findFirstByJobLevel("5")
                        .filter(u -> !u.getUserId().equals(applicantId))
                        .map(UserEntity::getUserId)
                        .orElseThrow(() -> new EntityNotFoundException("대표원장을 찾을 수 없습니다."));
            }

            default:
                throw new IllegalStateException("다음 승인자를 찾을 수 없는 단계입니다: " + step);
        }
    }

    /**
     * [신규] 특정 단계 이후의 다음 단계를 결정하는 메서드
     */
    private String determineNextApprovalStepAfter(String currentStep, String applicantJobLevel,   String applicantId,
                                                  String currentApproverId) {
        switch (currentStep) {
            case "SUBSTITUTE_APPROVAL":
                return "DEPARTMENT_HEAD_APPROVAL";
            case "DEPARTMENT_HEAD_APPROVAL":
                return "HR_STAFF_APPROVAL";
            case "HR_STAFF_APPROVAL":
                return "CENTER_DIRECTOR_APPROVAL";
            case "CENTER_DIRECTOR_APPROVAL":
                // 진료센터장(2) 본인 또는 그 이상 직급 신청 시 최종 승인
                if (Integer.parseInt(applicantJobLevel) >= 2 && !applicantId.equals(currentApproverId)) {
                    return "APPROVED";
                }
                return "HR_FINAL_APPROVAL";
            case "HR_FINAL_APPROVAL": // 새로 추가
                return "ADMIN_DIRECTOR_APPROVAL";
            case "ADMIN_DIRECTOR_APPROVAL":
                // 행정원장(4) 본인 또는 그 이상 직급 신청 시 최종 승인
                if (Integer.parseInt(applicantJobLevel) >= 4 && !applicantId.equals(currentApproverId)) {
                    return "APPROVED";
                }
                return "CEO_DIRECTOR_APPROVAL";
            case "CEO_DIRECTOR_APPROVAL":
                return "APPROVED";
            default:
                return null;
        }
    }

    /**
     * 현재 승인 단계에 따른 isApproved 필드 업데이트
     */
    private void completeCurrentApprovalStep(LeaveApplication application, String completedStep) {
        switch (completedStep) {
            case "SUBSTITUTE_APPROVAL":       application.setIsSubstituteApproved(true); break;
            case "DEPARTMENT_HEAD_APPROVAL":  application.setIsDeptHeadApproved(true); break;
            case "HR_STAFF_APPROVAL":         application.setIsHrStaffApproved(true); break;
            case "CENTER_DIRECTOR_APPROVAL":  application.setIsCenterDirectorApproved(true); break;
            case "HR_FINAL_APPROVAL":         application.setIsHrFinalApproved(true); break;
            case "ADMIN_DIRECTOR_APPROVAL":   application.setIsAdminDirectorApproved(true); break;
            case "CEO_DIRECTOR_APPROVAL":     application.setIsCeoDirectorApproved(true); break;
            default:
                log.warn("Unknown step when completing approval: {}", completedStep);
                break;
        }
    }


    /**
     * 현재 승인 단계 문자열에 해당하는 LeaveApplicationStatus 반환
     */
    private LeaveApplicationStatus getPendingStatusForStep(String step) {
        return switch (step) {
            case "SUBSTITUTE_APPROVAL" -> LeaveApplicationStatus.PENDING_SUBSTITUTE;
            case "DEPARTMENT_HEAD_APPROVAL" -> LeaveApplicationStatus.PENDING_DEPT_HEAD;
            case "HR_STAFF_APPROVAL" -> LeaveApplicationStatus.PENDING_HR_STAFF;
            case "CENTER_DIRECTOR_APPROVAL" -> LeaveApplicationStatus.PENDING_CENTER_DIRECTOR;
            case "HR_FINAL_APPROVAL" -> LeaveApplicationStatus.PENDING_HR_FINAL;
            case "ADMIN_DIRECTOR_APPROVAL" -> LeaveApplicationStatus.PENDING_ADMIN_DIRECTOR;
            case "CEO_DIRECTOR_APPROVAL" -> LeaveApplicationStatus.PENDING_CEO_DIRECTOR;
            default -> LeaveApplicationStatus.DRAFT; // 적절한 기본값 또는 예외
        };
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
                return ("0".equals(approver.getJobLevel()) ||"1".equals(approver.getJobLevel()))
                        && permissionService.hasPermission(approver.getUserId(), PermissionType.HR_LEAVE_APPLICATION)
                        && approver.isAdmin();
            case "CENTER_DIRECTOR_APPROVAL":
                return "2".equals(approver.getJobLevel());
            case "HR_FINAL_APPROVAL":
                return ("0".equals(approver.getJobLevel()) || "1".equals(approver.getJobLevel()))
                        && permissionService.hasPermission(approver.getUserId(), PermissionType.HR_LEAVE_APPLICATION)
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

        boolean isHrStaff = currentUser.isAdmin() && ("0".equals(currentUser.getJobLevel()) || "1".equals(currentUser.getJobLevel()))
                && permissionService.hasPermission(currentUser.getUserId(), PermissionType.HR_LEAVE_APPLICATION);
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
            return LeaveApplicationResponseDto.fromEntity(app, applicant, substitute);
        });
    }
}