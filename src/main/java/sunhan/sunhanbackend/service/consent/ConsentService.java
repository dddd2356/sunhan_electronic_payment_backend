package sunhan.sunhanbackend.service.consent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sunhan.sunhanbackend.entity.mysql.UserEntity;
import sunhan.sunhanbackend.entity.mysql.consent.ConsentAgreement;
import sunhan.sunhanbackend.entity.mysql.consent.ConsentForm;
import sunhan.sunhanbackend.enums.PermissionType;
import sunhan.sunhanbackend.enums.consent.ConsentStatus;
import sunhan.sunhanbackend.enums.consent.ConsentType;
import sunhan.sunhanbackend.repository.mysql.UserRepository;
import sunhan.sunhanbackend.repository.mysql.consent.ConsentAgreementRepository;
import sunhan.sunhanbackend.repository.mysql.consent.ConsentFormRepository;
import sunhan.sunhanbackend.service.PdfGenerationService;
import sunhan.sunhanbackend.service.PermissionService;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class ConsentService {

    private final ConsentAgreementRepository agreementRepository;
    private final ConsentFormRepository formRepository;
    private final UserRepository userRepository;
    private final PdfGenerationService pdfGenerationService;
    private final PermissionService permissionService;

    // ==================== 권한 체크 ====================

    /**
     * 동의서 생성 권한 확인
     */
    public boolean hasCreatePermission(String userId) {
        Set<PermissionType> permissions = permissionService.getAllUserPermissions(userId);
        return permissions.contains(PermissionType.CONSENT_CREATE);
    }

    /**
     * 동의서 관리 권한 확인
     */
    public boolean hasManagePermission(String userId) {
        Set<PermissionType> permissions = permissionService.getAllUserPermissions(userId);
        return permissions.contains(PermissionType.CONSENT_MANAGE);
    }

    /**
     * 특정 동의서 조회 권한 확인
     */
    public boolean canViewAgreement(String userId, Long agreementId) {
        // 관리 권한이 있으면 모두 조회 가능
        if (hasManagePermission(userId)) {
            return true;
        }

        ConsentAgreement agreement = agreementRepository.findById(agreementId)
                .orElseThrow(() -> new RuntimeException("동의서를 찾을 수 없습니다."));

        return agreement.canBeViewedBy(userId, false);
    }

    // ==================== 동의서 발송 ====================

    /**
     * 동의서 발송 (생성)
     * @param creatorId 발송자 (생성 권한 필요)
     * @param targetUserId 작성 대상자
     * @param type 동의서 타입
     */
    @Transactional
    public ConsentAgreement issueConsent(String creatorId, String targetUserId, ConsentType type) {
        // 1. 권한 체크
        if (!hasCreatePermission(creatorId)) {
            throw new RuntimeException("동의서 생성 권한이 없습니다.");
        }

        // 2. 대상자 존재 확인
        UserEntity targetUser = userRepository.findByUserIdWithDepartment(targetUserId)
                .orElseThrow(() -> new RuntimeException("대상 사용자를 찾을 수 없습니다: " + targetUserId));

        // 3. 중복 발급 방지: 이미 완료된 동의서가 있는지 확인
        boolean alreadyCompleted = agreementRepository.existsByTargetUserIdAndConsentFormTypeAndStatus(
                targetUserId, type, ConsentStatus.COMPLETED
        );
        if (alreadyCompleted) {
            throw new RuntimeException("해당 사용자는 이미 이 동의서를 완료했습니다.");
        }

        // 4. 현재 활성화된 최신 양식 가져오기
        ConsentForm form = formRepository.findTopByTypeAndIsActiveTrueOrderByVersionDesc(type)
                .orElseThrow(() -> new RuntimeException("해당 동의서 양식이 존재하지 않습니다: " + type));

        // 5. 동의서 인스턴스 생성
        ConsentAgreement agreement = new ConsentAgreement();
        agreement.setConsentForm(form);
        agreement.setType(type);
        agreement.setTargetUserId(targetUserId);
        agreement.setTargetUserName(targetUser.getUserName());
        agreement.setCreatorId(creatorId);
        agreement.setStatus(ConsentStatus.ISSUED);
        // ✅ Department 정보 저장 (이제 안전하게 가져올 수 있음)
        String deptName = targetUser.getDepartmentName(); // Department가 로드되어 있음
        agreement.setDeptName(deptName != null ? deptName : "");
        agreement.setPhone(targetUser.getPhone());
        log.info("🔍 부서명 조회: userId={}, deptCode={}, deptName={}",
                targetUserId, targetUser.getDeptCode(), deptName);

        Map<String, String> extraData = new HashMap<>();
        extraData.put("userName", targetUser.getUserName());
        extraData.put("userId", targetUser.getUserId());
        extraData.put("phone", targetUser.getPhone() != null ? targetUser.getPhone() : "");
        extraData.put("deptName", deptName != null ? deptName : "");
        extraData.put("deptCode", targetUser.getDeptCode() != null ? targetUser.getDeptCode() : "");

        agreement.setExtraData(extraData);

        ConsentAgreement saved = agreementRepository.save(agreement);
        log.info("동의서 발송 완료: id={}, type={}, target={}, creator={}",
                saved.getId(), type, targetUserId, creatorId);

        return saved;
    }

    /**
     * 배치 발송 (여러 대상자에게 동일 동의서 발송)
     */
    @Transactional
    public List<ConsentAgreement> issueBatchConsents(String creatorId, List<String> targetUserIds, ConsentType type) {
        return targetUserIds.stream()
                .map(targetId -> {
                    try {
                        return issueConsent(creatorId, targetId, type);
                    } catch (Exception e) {
                        log.warn("동의서 발송 실패: targetId={}, error={}", targetId, e.getMessage());
                        return null;
                    }
                })
                .filter(agreement -> agreement != null)
                .toList();
    }

    // ==================== 동의서 작성 ====================

    /**
     * 동의서 작성 완료 (제출)
     * @param agreementId 동의서 PK
     * @param userId 작성자 (대상자 본인이어야 함)
     * @param formDataJson 작성한 폼 데이터 (JSON)
     */
    @Transactional
    public void completeConsent(Long agreementId, String userId, String formDataJson) {
        // 1. 동의서 조회
        ConsentAgreement agreement = agreementRepository.findByIdWithForm(agreementId)
                .orElseThrow(() -> new RuntimeException("해당 동의서를 찾을 수 없습니다."));

        // 2. 작성 권한 확인 (본인만 작성 가능)
        if (!agreement.getTargetUserId().equals(userId)) {
            throw new RuntimeException("본인만 해당 동의서를 작성할 수 있습니다.");
        }

        // 3. 상태 확인 (이미 완료된 경우 재작성 불가)
        if (agreement.getStatus() == ConsentStatus.COMPLETED) {
            throw new RuntimeException("이미 완료된 동의서입니다.");
        }

        // ✅ 추가: 타입별 필수 필드 검증
        validateFormData(agreement.getType(), formDataJson);

        // 4. 작성 완료 처리
        agreement.complete(formDataJson);
        agreementRepository.save(agreement);

        // 5. PDF 생성 (비동기)
        pdfGenerationService.generateConsentPdf(agreement);

        log.info("동의서 작성 완료: id={}, userId={}", agreementId, userId);
    }

    // ✅ 추가: 검증 로직
    private void validateFormData(ConsentType type, String formDataJson) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(formDataJson);

            JsonNode data = root.has("formData") ? root.get("formData") : root;

            // 타입별 검증
            switch(type) {
                case PRIVACY_POLICY:
                    validateRequiredField(data, "essentialInfoAgree", "필수적 정보 동의 여부를 선택해주세요.");
                    validateRequiredField(data, "optionalInfoAgree", "선택적 정보 동의 여부를 선택해주세요.");
                    validateRequiredField(data, "uniqueIdAgree", "고유식별정보 동의 여부를 선택해주세요.");
                    validateRequiredField(data, "sensitiveInfoAgree", "민감정보 동의 여부를 선택해주세요.");
                    break;

                case MEDICAL_INFO_SECURITY:
                    validateRequiredField(data, "jobType", "직종을 선택해주세요.");
                    validateRequiredField(data, "residentNumber", "주민등록번호를 입력해주세요.");
                    validateRequiredField(data, "email", "이메일을 입력해주세요.");
                    break;
            }

            validateRequiredField(data, "agreementDate", "작성일을 입력해주세요.");

            // ✅ 서명 검증 강화
            boolean hasSignature = false;

            // 최상위 레벨 확인
            if (root.has("signature")) {
                String sig = root.get("signature").asText();
                if (sig != null && !sig.trim().isEmpty() && sig.startsWith("data:image")) {
                    hasSignature = true;
                }
            }

            // formData 내부 확인
            if (!hasSignature && data.has("signature")) {
                String sig = data.get("signature").asText();
                if (sig != null && !sig.trim().isEmpty() && sig.startsWith("data:image")) {
                    hasSignature = true;
                }
            }

            if (!hasSignature) {
                throw new RuntimeException("유효한 서명 이미지가 필요합니다.");
            }

            log.info("동의서 폼 데이터 검증 성공: type={}", type);

        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            log.error("폼 데이터 검증 중 오류 발생", e);
            throw new RuntimeException("폼 데이터 검증 실패: " + e.getMessage());
        }
    }

    /**
     * ✅ 개별 필드 검증 헬퍼 메서드
     */
    private void validateRequiredField(JsonNode data, String fieldName, String errorMessage) {
        if (!data.has(fieldName) || data.get(fieldName).asText().trim().isEmpty()) {
            throw new RuntimeException(errorMessage);
        }
    }

    // ==================== 조회 (권한별) ====================

    /**
     * 관리자용: 전체 동의서 목록
     */
    @Transactional(readOnly = true)
    public List<ConsentAgreement> findAllForAdmin(String adminUserId) {
        if (!hasManagePermission(adminUserId)) {
            throw new RuntimeException("관리 권한이 없습니다.");
        }
        return agreementRepository.findAllByOrderByCreatedAtDesc();
    }

    /**
     * 관리자용: 검색/필터링
     */
    @Transactional(readOnly = true)
    public Page<ConsentAgreement> searchAgreements(
            String adminUserId,
            ConsentStatus status,
            ConsentType type,
            String searchTerm,
            Pageable pageable
    ) {
        if (!hasManagePermission(adminUserId)) {
            throw new RuntimeException("관리 권한이 없습니다.");
        }
        return agreementRepository.searchAgreements(status, type, searchTerm, pageable);
    }

    /**
     * 생성자용: 내가 발송한 동의서 목록
     */
    @Transactional(readOnly = true)
    public List<ConsentAgreement> findByCreator(String creatorId) {
        if (!hasCreatePermission(creatorId)) {
            throw new RuntimeException("동의서 생성 권한이 없습니다.");
        }
        return agreementRepository.findByCreatorId(creatorId);
    }

    /**
     * 생성자용: 내가 발송한 완료 동의서만
     */
    @Transactional(readOnly = true)
    public List<ConsentAgreement> findCompletedByCreator(String creatorId) {
        if (!hasCreatePermission(creatorId)) {
            throw new RuntimeException("동의서 생성 권한이 없습니다.");
        }
        return agreementRepository.findByCreatorIdAndStatus(creatorId, ConsentStatus.COMPLETED);
    }

    /**
     * 대상자용: 나에게 온 동의서 목록 (작성 대기 중)
     */
    @Transactional(readOnly = true)
    public List<ConsentAgreement> findPendingByTargetUser(String userId) {
        return agreementRepository.findByTargetUserIdAndStatus(userId, ConsentStatus.ISSUED);
    }

    /**
     * 대상자용: 내가 작성한 모든 동의서
     */
    @Transactional(readOnly = true)
    public List<ConsentAgreement> findAllByTargetUser(String userId) {
        return agreementRepository.findByTargetUserId(userId);
    }

    /**
     * 동의서 상세 조회
     */
    @Transactional(readOnly = true)
    public ConsentAgreement getAgreementDetail(Long agreementId, String userId) {
        ConsentAgreement agreement = agreementRepository.findByIdWithForm(agreementId)
                .orElseThrow(() -> new RuntimeException("동의서를 찾을 수 없습니다."));

        // 조회 권한 확인
        boolean hasManage = hasManagePermission(userId);
        if (!agreement.canBeViewedBy(userId, hasManage)) {
            throw new RuntimeException("해당 동의서를 조회할 권한이 없습니다.");
        }

        return agreement;
    }

    // ==================== 통계 ====================

    /**
     * 동의서 현황 통계
     */
    @Transactional(readOnly = true)
    public Map<String, Object> getStatistics(String adminUserId) {
        if (!hasManagePermission(adminUserId)) {
            throw new RuntimeException("관리 권한이 없습니다.");
        }

        long totalIssued = agreementRepository.countByStatus(ConsentStatus.ISSUED);
        long totalCompleted = agreementRepository.countByStatus(ConsentStatus.COMPLETED);

        Map<ConsentType, Long> completedByType = Map.of(
                ConsentType.PRIVACY_POLICY, agreementRepository.countByTypeAndStatus(ConsentType.PRIVACY_POLICY, ConsentStatus.COMPLETED),
                ConsentType.SOFTWARE_USAGE, agreementRepository.countByTypeAndStatus(ConsentType.SOFTWARE_USAGE, ConsentStatus.COMPLETED),
                ConsentType.MEDICAL_INFO_SECURITY, agreementRepository.countByTypeAndStatus(ConsentType.MEDICAL_INFO_SECURITY, ConsentStatus.COMPLETED)
        );

        return Map.of(
                "totalIssued", totalIssued,
                "totalCompleted", totalCompleted,
                "completedByType", completedByType
        );
    }

    /**
     * 사용자별 동의서 완료 현황 조회
     */
    @Transactional(readOnly = true)
    public Map<ConsentType, Boolean> getUserConsentStatus(String userId) {
        return Map.of(
                ConsentType.PRIVACY_POLICY,
                agreementRepository.existsByTargetUserIdAndConsentFormTypeAndStatus(userId, ConsentType.PRIVACY_POLICY, ConsentStatus.COMPLETED),
                ConsentType.SOFTWARE_USAGE,
                agreementRepository.existsByTargetUserIdAndConsentFormTypeAndStatus(userId, ConsentType.SOFTWARE_USAGE, ConsentStatus.COMPLETED),
                ConsentType.MEDICAL_INFO_SECURITY,
                agreementRepository.existsByTargetUserIdAndConsentFormTypeAndStatus(userId, ConsentType.MEDICAL_INFO_SECURITY, ConsentStatus.COMPLETED)
        );
    }

    // ==================== 동의서 취소 (추후 확장) ====================

    /**
     * 동의서 발송 취소 (아직 작성되지 않은 경우만)
     */
    @Transactional
    public void cancelConsent(Long agreementId, String userId) {
        ConsentAgreement agreement = agreementRepository.findById(agreementId)
                .orElseThrow(() -> new RuntimeException("동의서를 찾을 수 없습니다."));

        // 발송자 또는 관리자만 취소 가능
        if (!agreement.getCreatorId().equals(userId) && !hasManagePermission(userId)) {
            throw new RuntimeException("동의서를 취소할 권한이 없습니다.");
        }

        // 이미 완료된 경우 취소 불가
        if (agreement.getStatus() == ConsentStatus.COMPLETED) {
            throw new RuntimeException("이미 완료된 동의서는 취소할 수 없습니다.");
        }

        agreement.setStatus(ConsentStatus.CANCELLED);
        agreement.setUpdatedAt(LocalDateTime.now());
        agreementRepository.save(agreement);

        log.info("동의서 취소: id={}, cancelledBy={}", agreementId, userId);
    }
}