package sunhan.sunhanbackend.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import sunhan.sunhanbackend.entity.mysql.EmploymentContract;
import sunhan.sunhanbackend.entity.mysql.LeaveApplication;
import sunhan.sunhanbackend.enums.ContractType;
import sunhan.sunhanbackend.enums.Role;
import sunhan.sunhanbackend.repository.mysql.UserRepository;
import sunhan.sunhanbackend.entity.mysql.UserEntity;
import sunhan.sunhanbackend.util.HtmlPdfRenderer;
import sunhan.sunhanbackend.util.LeaveApplicationPdfRenderer;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.Optional;

@Service
@Slf4j
public class FormService {

    private final Path uploadsRoot = Paths.get("C:", "sunhan_electronic_payment").toAbsolutePath().normalize();
    private final Path employmentUploadDir = uploadsRoot.resolve("employment_contract");
    private final Path leaveApplicationUploadDir = uploadsRoot.resolve("leave_application");

    private final UserRepository userRepository;

    public FormService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    private final ObjectMapper objectMapper = new ObjectMapper();

    @PostConstruct
    public void init() {
        try {
            if (Files.notExists(uploadsRoot)) {
                Files.createDirectories(uploadsRoot);
                log.info("Created uploads root: {}", uploadsRoot);
            }
            if (Files.notExists(employmentUploadDir)) {
                Files.createDirectories(employmentUploadDir);
                log.info("Created employment upload dir: {}", employmentUploadDir);
            }
            if (Files.notExists(leaveApplicationUploadDir)) {
                Files.createDirectories(leaveApplicationUploadDir);
                log.info("Created leave application upload dir: {}", leaveApplicationUploadDir);
            }
        } catch (IOException e) {
            log.error("Failed to create upload directories", e);
            throw new RuntimeException(e);
        }
    }

    /**
     * EmploymentContract 엔티티를 PdfRenderer가 처리할 수 있는 JSON 형태로 변환
     * formDataJson 필드에서 JSON 데이터를 그대로 반환
     */
    private String convertToJson(EmploymentContract contract) {
        try {
            // formDataJson이 이미 올바른 JSON 형태라면 그대로 반환
            String formDataJson = contract.getFormDataJson();

            if (formDataJson == null || formDataJson.isEmpty()) {
                throw new IllegalStateException("계약서 데이터가 비어있습니다");
            }

            // JSON 유효성 검사
            try {
                JsonNode jsonNode = objectMapper.readTree(formDataJson);
                // 다시 문자열로 변환하여 포맷팅 정리
                return objectMapper.writeValueAsString(jsonNode);
            } catch (Exception e) {
                throw new IllegalStateException("계약서 JSON 데이터가 유효하지 않습니다: " + e.getMessage(), e);
            }

        } catch (Exception e) {
            log.error("JSON 변환 실패: contractId={}, error={}", contract.getId(), e.getMessage(), e);
            throw new IllegalStateException("계약서 데이터를 처리하는데 실패했습니다: " + e.getMessage(), e);
        }
    }

    // signatures 노드에 개별 서명 항목이 존재하는지 확인하고 업데이트/생성하는 헬퍼 메서드
    private void ensureSignatureEntry(ObjectNode signaturesNode, String role, String userId, Boolean isSigned) {
        // null이나 빈 문자열 체크 강화
        if (userId == null || userId.trim().isEmpty()) {
            log.debug("Skipping signature entry for role '{}' - userId is null or empty", role);
            // 빈 userId라도 기본 서명 구조는 생성
            ArrayNode roleSignatures = objectMapper.createArrayNode();
            ObjectNode signatureEntry = objectMapper.createObjectNode();
            signatureEntry.put("text", "");
            signatureEntry.put("imageUrl", (String) null);
            signatureEntry.put("isSigned", isSigned != null ? isSigned : false);
            signatureEntry.put("signerId", "");
            signatureEntry.put("signerName", "");
            roleSignatures.add(signatureEntry);
            signaturesNode.set(role, roleSignatures);
            return;
        }

        try {
            UserEntity user = userRepository.findByUserId(userId.trim()).orElse(null);
            String signatureImageUrl = null;

            if (user != null && user.getSignimage() != null) {
                signatureImageUrl = "data:image/png;base64," + java.util.Base64.getEncoder().encodeToString(user.getSignimage());
            }

            ArrayNode roleSignatures = signaturesNode.has(role) && signaturesNode.get(role).isArray() ?
                    (ArrayNode) signaturesNode.get(role) : objectMapper.createArrayNode();

            ObjectNode signatureEntry;
            if (roleSignatures.isEmpty()) {
                signatureEntry = objectMapper.createObjectNode();
                roleSignatures.add(signatureEntry);
            } else {
                signatureEntry = (ObjectNode) roleSignatures.get(0);
            }

            // 서명 데이터 설정
            signatureEntry.put("text", user != null ? user.getUserName() : "");
            signatureEntry.put("imageUrl", signatureImageUrl);
            signatureEntry.put("isSigned", isSigned != null ? isSigned : false);
            signatureEntry.put("signerId", userId.trim());
            if (user != null) {
                signatureEntry.put("signerName", user.getUserName());
            }

            // 서명된 경우 서명 날짜도 추가
            if (isSigned != null && isSigned) {
                signatureEntry.put("signatureDate", LocalDate.now().toString());
            }

            signaturesNode.set(role, roleSignatures);

            log.info("서명 엔트리 생성 완료 - role: {}, userId: {}, userName: {}, isSigned: {}, hasImage: {}",
                    role, userId, user != null ? user.getUserName() : "", isSigned, signatureImageUrl != null);

        } catch (Exception e) {
            log.error("Failed to ensure signature entry for role '{}', userId '{}': {}", role, userId, e.getMessage());
            // 에러가 발생해도 기본 서명 엔트리는 생성
            ArrayNode roleSignatures = objectMapper.createArrayNode();
            ObjectNode signatureEntry = objectMapper.createObjectNode();
            signatureEntry.put("text", "");
            signatureEntry.put("imageUrl", (String) null);
            signatureEntry.put("isSigned", isSigned != null ? isSigned : false);
            signatureEntry.put("signerId", userId != null ? userId.trim() : "");
            signatureEntry.put("signerName", "");
            roleSignatures.add(signatureEntry);
            signaturesNode.set(role, roleSignatures);
        }
    }

    // Approver ID를 jobLevel과 deptCode로 찾는 헬퍼 메서드 (간단한 예시, UserService에 있어야 함)
    private String findApproverIdByJobLevel(String jobLevel, String deptCode) {
        try {
            if (jobLevel == null || jobLevel.trim().isEmpty()) {
                log.debug("findApproverIdByJobLevel: jobLevel is null or empty");
                return null;
            }

            if (deptCode != null && !deptCode.trim().isEmpty()) {
                return userRepository.findByDeptCodeAndJobLevel(deptCode.trim(), jobLevel.trim())
                        .stream().findFirst().map(UserEntity::getUserId).orElse(null);
            } else {
                return userRepository.findByJobLevel(jobLevel.trim())
                        .stream().findFirst().map(UserEntity::getUserId).orElse(null);
            }
        } catch (Exception e) {
            log.error("Failed to find approver by jobLevel '{}', deptCode '{}': {}", jobLevel, deptCode, e.getMessage());
            return null;
        }
    }

    private String convertToJson(LeaveApplication application) throws IOException {
        JsonNode rootNode;
        if (application.getFormDataJson() != null && !application.getFormDataJson().isEmpty()) {
            rootNode = objectMapper.readTree(application.getFormDataJson());
        } else {
            rootNode = objectMapper.createObjectNode();
        }

        if (rootNode.isObject()) {
            ObjectNode objectNode = (ObjectNode) rootNode;

            // 신청자 정보 추가 (null 체크 강화)
            if (application.getApplicantId() != null && !application.getApplicantId().trim().isEmpty()) {
                Optional<UserEntity> applicant = userRepository.findByUserId(application.getApplicantId().trim());
                applicant.ifPresent(a -> {
                    objectNode.put("applicantName", a.getUserName());
                    objectNode.put("applicantDept", a.getDeptCode());
                    objectNode.put("applicantPosition", a.getJobLevel());
                    objectNode.put("applicantContact", a.getPhone());
                    objectNode.put("applicantPhone", a.getPhone());
                });
            }

            // 대직자 정보 추가 (null 체크 강화)
            if (application.getSubstituteId() != null && !application.getSubstituteId().trim().isEmpty()) {
                Optional<UserEntity> substitute = userRepository.findByUserId(application.getSubstituteId().trim());
                substitute.ifPresent(s -> {
                    objectNode.put("substituteName", s.getUserName());
                });
            }

            // LeaveApplication 엔티티의 다른 필드 추가
            objectNode.put("id", application.getId());
            objectNode.put("applicantId", application.getApplicantId());
            objectNode.put("substituteId", application.getSubstituteId());
            objectNode.put("leaveType", application.getLeaveType() != null ? application.getLeaveType().name() : null);
            objectNode.put("leaveDetail", application.getLeaveDetail());
            objectNode.put("startDate", application.getStartDate() != null ? application.getStartDate().toString() : null);
            objectNode.put("endDate", application.getEndDate() != null ? application.getEndDate().toString() : null);
            objectNode.put("totalDays", application.getTotalDays());
            if (!objectNode.has("applicationDate")) {
                objectNode.put("applicationDate", application.getApplicationDate() != null ? application.getApplicationDate().toString() : null);
            }
            objectNode.put("status", application.getStatus() != null ? application.getStatus().name() : null);
            objectNode.put("currentApprovalStep", application.getCurrentApprovalStep());
            objectNode.put("rejectionReason", application.getRejectionReason());
            objectNode.put("pdfUrl", application.getPdfUrl());
            objectNode.put("isPrintable", application.isPrintable());

            // 서명 상태 플래그 추가 (null 안전)
            objectNode.put("isApplicantSigned", application.getIsApplicantSigned() != null ? application.getIsApplicantSigned() : false);
            objectNode.put("isSubstituteApproved", application.getIsSubstituteApproved() != null ? application.getIsSubstituteApproved() : false);
            objectNode.put("isDeptHeadApproved", application.getIsDeptHeadApproved() != null ? application.getIsDeptHeadApproved() : false);
            objectNode.put("isHrStaffApproved", application.getIsHrStaffApproved() != null ? application.getIsHrStaffApproved() : false);
            objectNode.put("isCenterDirectorApproved", application.getIsCenterDirectorApproved() != null ? application.getIsCenterDirectorApproved() : false);
            objectNode.put("isAdminDirectorApproved", application.getIsAdminDirectorApproved() != null ? application.getIsAdminDirectorApproved() : false);
            objectNode.put("isCeoDirectorApproved", application.getIsCeoDirectorApproved() != null ? application.getIsCeoDirectorApproved() : false);

            // ========== 수정된 부분: 실제 서명자 정보 사용 ==========
            // signatures 객체가 이미 formDataJson에 있다면 그것을 우선 사용
            if (!objectNode.has("signatures") || !objectNode.get("signatures").isObject()) {
                objectNode.set("signatures", objectMapper.createObjectNode());
            }
            ObjectNode signaturesNode = (ObjectNode) objectNode.get("signatures");

            // 신청자 정보를 다시 가져와서 부서코드 확인
            Optional<UserEntity> applicantOpt = Optional.empty();
            if (application.getApplicantId() != null && !application.getApplicantId().trim().isEmpty()) {
                applicantOpt = userRepository.findByUserId(application.getApplicantId().trim());
            }

            // 기존 서명 데이터가 있는지 확인하고, 없을 때만 기본값으로 채움
            ensureSignatureEntryFromExistingOrDefault(signaturesNode, "applicant",
                    application.getApplicantId(), application.getIsApplicantSigned());

            ensureSignatureEntryFromExistingOrDefault(signaturesNode, "substitute",
                    application.getSubstituteId(), application.getIsSubstituteApproved());

            // 부서장: 기존 서명이 있으면 그대로, 없으면 기본값
            String deptCode = applicantOpt.map(UserEntity::getDeptCode).orElse(null);
            ensureSignatureEntryFromExistingOrDefault(signaturesNode, "departmentHead",
                    findApproverIdByJobLevel("1", deptCode), application.getIsDeptHeadApproved());

            // ★★★ 인사팀 서명: 기존 서명 데이터에서 실제 서명자를 찾아서 사용 ★★★
            ensureHrStaffSignatureFromExisting(signaturesNode, application.getIsHrStaffApproved());

            // 나머지 승인자들도 기존 서명 우선
            ensureSignatureEntryFromExistingOrDefault(signaturesNode, "centerDirector",
                    findApproverIdByJobLevel("2", null), application.getIsCenterDirectorApproved());

            ensureSignatureEntryFromExistingOrDefault(signaturesNode, "adminDirector",
                    findApproverIdByJobLevel("4", null), application.getIsAdminDirectorApproved());

            ensureSignatureEntryFromExistingOrDefault(signaturesNode, "ceoDirector",
                    findCeoDirectorId(), application.getIsCeoDirectorApproved());
        }
        return objectMapper.writeValueAsString(rootNode);
    }
    private String findCeoDirectorId() {
        return userRepository.findByJobLevelAndRole("5", Role.ADMIN)
                .stream()
                .filter(UserEntity::isAdmin) // 여기서 계산
                .findFirst()
                .map(UserEntity::getUserId)
                .orElse(null);
    }

    /**
     * 인사팀 서명을 기존 서명 데이터에서 찾아서 설정하는 특별 메서드
     */
    private void ensureHrStaffSignatureFromExisting(ObjectNode signaturesNode, Boolean isHrStaffApproved) {
        // 이미 인사팀 서명 데이터가 있고 서명이 완료되어 있다면 그대로 유지
        if (signaturesNode.has("hrStaff") && signaturesNode.get("hrStaff").isArray()) {
            ArrayNode existingHrSignatures = (ArrayNode) signaturesNode.get("hrStaff");
            if (!existingHrSignatures.isEmpty()) {
                JsonNode firstSignature = existingHrSignatures.get(0);
                if (firstSignature.has("isSigned") && firstSignature.get("isSigned").asBoolean()) {
                    // 이미 서명이 완료된 데이터가 있으므로 그대로 유지
                    // 실제 서명한 사람의 정보가 이미 저장되어 있음
                    log.debug("HR staff signature already exists and is signed, keeping existing data");
                    return;
                }
            }
        }

        // 기존 서명이 없거나 완료되지 않은 경우에만 기본 인사팀원으로 설정
        String defaultHrStaffId = findApproverIdByJobLevel("0", "AD");
        ensureSignatureEntry(signaturesNode, "hrStaff", defaultHrStaffId, isHrStaffApproved);
    }
    /**
     * 기존 서명 데이터가 있으면 그대로 사용하고, 없으면 기본값으로 채우는 메서드
     */
    private void ensureSignatureEntryFromExistingOrDefault(ObjectNode signaturesNode, String role,
                                                           String defaultUserId, Boolean isSigned) {
        // 이미 서명 데이터가 있고 실제로 서명되어 있다면 그대로 유지
        if (signaturesNode.has(role) && signaturesNode.get(role).isArray()) {
            ArrayNode existingSignatures = (ArrayNode) signaturesNode.get(role);
            if (!existingSignatures.isEmpty()) {
                JsonNode firstSignature = existingSignatures.get(0);
                if (firstSignature.has("isSigned") && firstSignature.get("isSigned").asBoolean()) {
                    // 이미 서명이 완료된 데이터가 있으므로 그대로 유지
                    return;
                }
            }
        }

        // 기존 서명이 없거나 완료되지 않은 경우에만 기본값으로 설정
        ensureSignatureEntry(signaturesNode, role, defaultUserId, isSigned);
    }
    @Cacheable("formTemplate")
    public String getPublishedForm(ContractType type) {
        String resourcePath;
        switch (type) {
            case EMPLOYMENT_CONTRACT:
                resourcePath = "forms/employment-contract.json";
                break;
            case LEAVE_APPLICATION:
                resourcePath = "forms/leave-application.json";
                break;
            default:
                throw new IllegalArgumentException("Unknown form type: " + type);
        }
        return loadResourceAsString(resourcePath);
    }

    private String loadResourceAsString(String path) {
        try (InputStream is = new ClassPathResource(path).getInputStream()) {
            byte[] bytes = is.readAllBytes();
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.error("Failed to load resource '{}'", path, e);
            throw new RuntimeException("폼 파일 로딩 실패: " + path, e);
        }
    }

    private String getFilenamePrefix(EmploymentContract contract) {
        return switch (contract.getContractType()) {
            case EMPLOYMENT_CONTRACT -> "employment_contract";
            case LEAVE_APPLICATION   -> "leave_application";
            default                  -> "contract";
        };
    }

    public String generatePdf(EmploymentContract contract) {
        String date = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy"));
        Optional<UserEntity> employeeOpt = userRepository.findByUserId(contract.getEmployee().getUserId());
        String empNameRaw = employeeOpt.map(UserEntity::getUserName).orElse("");
        String empNameFiltered = empNameRaw.replaceAll("[^\\p{L}0-9\\s]", "").trim().replaceAll("\\s+", "_").replaceAll("_+", "_").replaceAll("^_+|_+$", "");
        String rawFolder;
        if (empNameFiltered.isEmpty()) {
            rawFolder = contract.getEmployee().getUserId();
        } else {
            rawFolder = empNameFiltered + "_" + contract.getEmployee().getUserId();
        }
        String safeFolderName = rawFolder.replaceAll("[^\\p{L}0-9_\\-\\.]", "_").replaceAll("_+", "_").replaceAll("^_+|_+$", "");
        log.info("PDF 폴더명 생성 debug: empNameRaw='{}', empNameFiltered='{}', rawFolder='{}', safeFolderName='{}'", empNameRaw, empNameFiltered, rawFolder, safeFolderName);
        String type = getFilenamePrefix(contract);
        String filename = String.format("%s_%s_%s.pdf", empNameFiltered, date, type); // ✅ 수정: empNameFiltered 사용
        Path userDir = employmentUploadDir.resolve(safeFolderName);
        try {
            if (Files.notExists(userDir)) {
                Files.createDirectories(userDir);
            }
            Path target = userDir.resolve(filename);

            // ✅ 이 한 줄을 수정해 주세요!
            byte[] pdfBytes = getPdfBytes(contract);

            Files.write(target, pdfBytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            log.info("Saved PDF: {}", target);
            String encodedFolder = URLEncoder.encode(safeFolderName, StandardCharsets.UTF_8);
            String encodedFilename = URLEncoder.encode(filename, StandardCharsets.UTF_8);
            return "/uploads/employment_contract/" + encodedFolder + "/" + encodedFilename;

        } catch (IOException e) {
            log.error("PDF 생성/저장 실패: contractId={}", contract.getId(), e);
            throw new RuntimeException("PDF 생성 실패", e);
        }
    }

    /**
     * LeaveApplication PDF를 생성하고 파일로 저장 후 URL 반환 (새로 추가)
     */
    public String savePdf(LeaveApplication application) {
        String date = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));

        // 1. Optional을 사용하여 사용자 정보를 직접 조회
        Optional<UserEntity> applicantOpt = userRepository.findByUserId(application.getApplicantId());
        String applicantNameRaw = applicantOpt
                .map(UserEntity::getUserName)
                .orElse("");


        // 1) 허용 문자만 남기기(한글/영문/숫자/공백)
        String applicantNameFiltered = applicantNameRaw.replaceAll("[^\\p{L}0-9\\s]", "")
                .trim()
                .replaceAll("\\s+", "_")          // 공백 -> 언더스코어
                .replaceAll("_+", "_")           // 연속 언더스코어 축약
                .replaceAll("^_+|_+$", "");      // 앞뒤 언더스코어 제거

        // raw folder : applicantId 우선, 이름 있으면 뒤에 붙임
        String rawFolder;
        if (applicantNameFiltered.isEmpty()) {
            rawFolder = application.getApplicantId();
        } else {
            rawFolder = applicantNameFiltered+ "_" + application.getApplicantId();
        }

        String safeFolderName = rawFolder.replaceAll("[^\\p{L}0-9_\\-\\.]", "_")
                .replaceAll("_+", "_")
                .replaceAll("^_+|_+$", "");

        // 사용자 폴더 경로
        Path userDir = leaveApplicationUploadDir.resolve(safeFolderName);

        try {
            // 폴더 없으면 생성
            if (Files.notExists(userDir)) {
                Files.createDirectories(userDir);
            }

            // prefix: 사용자명/아이디 포함으로 충돌 줄임
            String prefix = String.format("%s_%s_leave-application_", applicantNameFiltered, date);

            long countToday = Files.list(userDir)
                    .filter(p -> p.getFileName().toString().startsWith(prefix))
                    .count();

            int nextNumber = (int) countToday + 1;
            String numberStr = String.format("%02d", nextNumber);

            // filename
            String filename = String.format("%s_%s_leave-application_%s.pdf",
                    applicantNameFiltered, date, numberStr);

            Path target = userDir.resolve(filename);

            // PDF 생성 및 저장
            byte[] pdfBytes = getLeaveApplicationPdfBytes(application);
            Files.write(target, pdfBytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            log.info("Saved LeaveApplication PDF: {}", target);

            // 반환 URL (인코딩)
            String encodedFolder = URLEncoder.encode(safeFolderName, StandardCharsets.UTF_8);
            String encodedFilename = URLEncoder.encode(filename, StandardCharsets.UTF_8);
            return "/uploads/leave_application/" + encodedFolder + "/" + encodedFilename;

        } catch (IOException e) {
            log.error("LeaveApplication PDF 생성/저장 실패: id={}", application.getId(), e);
            throw new RuntimeException("휴가원 PDF 생성 실패", e);
        }
    }


    public byte[] getPdfBytes(EmploymentContract contract) {
        try {
            String formDataJson = contract.getFormDataJson();
            if (formDataJson == null || formDataJson.isEmpty()) {
                throw new IllegalStateException("계약서 데이터가 비어있습니다");
            }

            JsonNode jsonNode = objectMapper.readTree(formDataJson);

            Optional<UserEntity> ceoOpt = userRepository.findFirstByJobLevel("5");
            if (ceoOpt.isPresent()) {
                UserEntity ceo = ceoOpt.get();
                if (ceo.getSignimage() != null) {
                    String signatureImageUrl = "data:image/png;base64," + Base64.getEncoder().encodeToString(ceo.getSignimage());
                    if (jsonNode instanceof ObjectNode) {
                        ObjectNode objectNode = (ObjectNode) jsonNode;
                        objectNode.put("ceoName", ceo.getUserName());
                        objectNode.put("ceoSignatureUrl", signatureImageUrl);
                    }
                } else {
                    log.warn("대표원장 서명 이미지가 DB에 없습니다. userId: {}", ceo.getUserId());
                }
            } else {
                log.warn("대표원장(jobLevel 5) 사용자를 찾을 수 없습니다.");
            }

            String modifiedJson = objectMapper.writeValueAsString(jsonNode);

            // ✅ 이 부분을 추가하여 로그로 JSON 데이터 확인
            log.info("PDF 렌더링에 사용될 최종 JSON 데이터: {}", modifiedJson);

            return HtmlPdfRenderer.render(modifiedJson, contract);
        } catch (IOException e) {
            log.error("PDF 생성 중 오류 발생: contractId={}, error={}", contract.getId(), e.getMessage(), e);
            throw new IllegalStateException("PDF 생성에 실패했습니다: " + e.getMessage(), e);
        }
    }

    /**
     * LeaveApplication PDF 바이트 배열 반환 (새로 추가)
     */
    public byte[] getLeaveApplicationPdfBytes(LeaveApplication application) throws IOException {
        // 1) JSON 데이터 생성 (기존 메서드를 활용하면 필요한 모든 정보가 포함됩니다)
        String jsonData = convertToJson(application);

        // 2) 수정된 LeaveApplicationPdfRenderer를 호출하여 PDF를 생성합니다.
        return LeaveApplicationPdfRenderer.render(jsonData);
    }
}