package sunhan.sunhanbackend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import sunhan.sunhanbackend.entity.mysql.EmploymentContract;
import sunhan.sunhanbackend.entity.mysql.LeaveApplication;
import sunhan.sunhanbackend.entity.mysql.workschedule.WorkSchedule;
import sunhan.sunhanbackend.entity.mysql.workschedule.WorkScheduleEntry;
import sunhan.sunhanbackend.enums.ContractType;
import sunhan.sunhanbackend.enums.Role;
import sunhan.sunhanbackend.repository.mysql.DepartmentRepository;
import sunhan.sunhanbackend.repository.mysql.UserRepository;
import sunhan.sunhanbackend.entity.mysql.UserEntity;
import sunhan.sunhanbackend.repository.mysql.workschedule.WorkScheduleRepository;
import sunhan.sunhanbackend.util.HtmlPdfRenderer;
import sunhan.sunhanbackend.util.LeaveApplicationPdfRenderer;
import sunhan.sunhanbackend.util.WorkSchedulePdfRenderer;
import sunhan.sunhanbackend.entity.mysql.Department;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
@Slf4j
public class FormService {

    private final Path uploadsRoot = Paths.get("C:", "sunhan_electronic_payment").toAbsolutePath().normalize();
    private final Path employmentUploadDir = uploadsRoot.resolve("employment_contract");
    private final Path leaveApplicationUploadDir = uploadsRoot.resolve("leave_application");
    private final Path workScheduleUploadDir = uploadsRoot.resolve("work_schedule");

    private final UserRepository userRepository;
    private final WorkScheduleRepository workScheduleRepository;
    private final DepartmentRepository departmentRepository;

    @Autowired
    private ObjectMapper objectMapper;
    public FormService(UserRepository userRepository, WorkScheduleRepository workScheduleRepository, DepartmentRepository departmentRepository, ObjectMapper objectMapper) {
        this.userRepository = userRepository;
        this.workScheduleRepository = workScheduleRepository;
        this.departmentRepository = departmentRepository;
        this.objectMapper = objectMapper;
    }

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
            if (Files.notExists(workScheduleUploadDir)) {
                Files.createDirectories(workScheduleUploadDir);
                log.info("Created work schedule upload dir: {}", workScheduleUploadDir);
            }
        } catch (IOException e) {
            log.error("Failed to create upload directories", e);
            throw new RuntimeException(e);
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
                    String deptName = getDepartmentName(a.getDeptCode());
                    objectNode.put("applicantDeptName", deptName);
                    objectNode.put("applicantPosition", a.getJobLevel());
                    String fullAddress = a.getAddress() != null ? a.getAddress() : "";
                    String detailAddress = a.getDetailAddress() != null ? a.getDetailAddress() : "";
                    objectNode.put("applicantContact", (fullAddress + " " + detailAddress).trim());
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

            // 인사팀 서명: 기존 서명 데이터에서 실제 서명자를 찾아서 사용 ★★★
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

    // ✅ 부서명 조회 헬퍼 메서드 추가
    private String getDepartmentName(String deptCode) {
        if (deptCode == null || deptCode.isEmpty()) {
            return "";
        }

        try {
            // 숫자 제거하여 base 부서 코드 추출 (예: OS1 -> OS)
            String baseDeptCode = deptCode.replaceAll("\\d+$", "");

            return departmentRepository.findByDeptCode(baseDeptCode)
                    .map(Department::getDeptName)
                    .orElse(deptCode); // 조회 실패 시 원래 코드 반환
        } catch (Exception e) {
            log.warn("부서명 조회 실패: deptCode={}", deptCode, e);
            return deptCode;
        }
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

    /**
     * WorkSchedule PDF를 생성하고 파일로 저장 후 URL 반환
     */
    public String saveWorkSchedulePdf(WorkSchedule schedule, Map<String, Object> scheduleDetail) {
        String date = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        String yearMonth = schedule.getScheduleYearMonth().replace("-", "");
        // ✅ 수정: 커스텀 근무표면 customDeptName 사용
        String deptIdentifier;
        if (schedule.getIsCustom() != null && schedule.getIsCustom()) {
            deptIdentifier = schedule.getCustomDeptName().replaceAll("[^\\p{L}0-9_\\-\\.]", "_");
        } else {
            deptIdentifier = schedule.getDeptCode();
        }

        String safeFolderName = deptIdentifier + "_" + yearMonth;
        // 근무표 루트 및 부서 폴더
        Path workScheduleUploadDir = uploadsRoot.resolve("work_schedule").toAbsolutePath().normalize();
        Path deptDir = workScheduleUploadDir.resolve(safeFolderName).toAbsolutePath().normalize();

        try {
            // 디렉터리 보장
            if (Files.notExists(workScheduleUploadDir)) {
                Files.createDirectories(workScheduleUploadDir);
                log.info("Created workSchedule root: {}", workScheduleUploadDir);
            }
            if (Files.notExists(deptDir)) {
                Files.createDirectories(deptDir);
                log.info("Created department dir: {}", deptDir);
            }

            // ✅ 수정 1: 기존 PDF 삭제 로직 추가
            if (schedule.getPdfUrl() != null && !schedule.getPdfUrl().isEmpty()) {
                String oldPath = schedule.getPdfUrl().replaceFirst("^/+uploads/?", "").trim();
                Path oldFile = uploadsRoot.resolve(oldPath).normalize();
                try {
                    if (Files.exists(oldFile)) {
                        Files.delete(oldFile);
                        log.info("기존 PDF 삭제 완료: {}", oldFile);
                    }
                } catch (IOException e) {
                    log.warn("기존 PDF 삭제 실패 (무시하고 진행): {}", oldFile, e);
                }
            }

            // ✅ 수정 2: 타임스탬프 포함 파일명 생성
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
            String filename = String.format("%s_%s_%s.pdf", deptIdentifier, yearMonth, timestamp);
            Path target = deptDir.resolve(filename).toAbsolutePath().normalize();

            log.info("PDF 저장 후보 경로: {}", target);

            // ----- entries 변환 (기존 로직 유지) -----
            Object entriesObj = scheduleDetail.get("entries");
            if (entriesObj instanceof List<?>) {
                List<?> rawEntries = (List<?>) entriesObj;
                List<Map<String, Object>> convertedEntries = new ArrayList<>();

                for (Object item : rawEntries) {
                    if (item instanceof WorkScheduleEntry) {
                        WorkScheduleEntry entry = (WorkScheduleEntry) item;
                        Map<String, Object> entryMap = new HashMap<>();

                        entryMap.put("id", entry.getId());
                        entryMap.put("userId", entry.getUserId());
                        entryMap.put("positionId", entry.getPositionId());
                        entryMap.put("displayOrder", entry.getDisplayOrder());
                        entryMap.put("nightDutyRequired", entry.getNightDutyRequired());
                        entryMap.put("nightDutyActual", entry.getNightDutyActual());
                        entryMap.put("nightDutyAdditional", entry.getNightDutyAdditional());
                        entryMap.put("dutyDetailJson", entry.getDutyDetailJson());
                        entryMap.put("offCount", entry.getOffCount());
                        entryMap.put("vacationTotal", entry.getVacationTotal());
                        entryMap.put("vacationUsedThisMonth", entry.getVacationUsedThisMonth());
                        entryMap.put("vacationUsedTotal", entry.getVacationUsedTotal());
                        entryMap.put("remarks", entry.getRemarks());
                        entryMap.put("workDataJson", entry.getWorkDataJson());

                        String workDataJson = entry.getWorkDataJson();
                        if (workDataJson != null && !workDataJson.isEmpty()) {
                            try {
                                Map<String, Object> workDataMap = objectMapper.readValue(
                                        workDataJson,
                                        new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {}
                                );
                                entryMap.put("workData", workDataMap);
                                log.info("Entry {} workData 변환 완료 - 키 개수: {}", entry.getId(), workDataMap.size());
                            } catch (Exception e) {
                                log.error("Entry {} workDataJson 파싱 실패: {}", entry.getId(), e.getMessage());
                                entryMap.put("workData", new HashMap<>());
                            }
                        } else {
                            entryMap.put("workData", new HashMap<>());
                        }

                        entryMap.remove("workDataJson");
                        convertedEntries.add(entryMap);
                    } else if (item instanceof Map) {
                        convertedEntries.add((Map<String, Object>) item);
                    }
                }

                scheduleDetail.put("entries", convertedEntries);
                log.info("총 {}개의 엔트리 변환 완료", ((List<?>) scheduleDetail.get("entries")).size());
            } else {
                log.warn("entries가 List가 아님: type={}", entriesObj == null ? "null" : entriesObj.getClass().getName());
            }

            String scheduleRemarks = schedule.getRemarks();
            if (scheduleRemarks != null && !scheduleRemarks.isEmpty()) {
                scheduleDetail.put("remarks", scheduleRemarks);
                log.info("📝 비고 데이터 포함: {}", scheduleRemarks.substring(0, Math.min(50, scheduleRemarks.length())));
            } else {
                scheduleDetail.put("remarks", "");
                log.warn("⚠️ 비고 데이터 없음");
            }

            // JSON 준비
            String jsonData = objectMapper.writeValueAsString(scheduleDetail);
            log.info("PDF 생성용 JSON 길이: {} bytes", jsonData.length());
            if (jsonData.length() > 500) {
                log.info("JSON 샘플: {}", jsonData.substring(0, Math.min(500, jsonData.length())));
            }

            // 렌더링
            byte[] pdfBytes;
            try {
                pdfBytes = WorkSchedulePdfRenderer.render(jsonData);
            } catch (Exception e) {
                log.error("WorkSchedule PDF 렌더링 실패: scheduleId={}, err={}", schedule.getId(), e.getMessage(), e);
                throw new RuntimeException("PDF 렌더링 실패", e);
            }

            if (pdfBytes == null) {
                log.error("WorkSchedulePdfRenderer returned null bytes for scheduleId={}", schedule.getId());
                throw new RuntimeException("PDF 바이트가 null 입니다.");
            }
            log.info("생성된 PDF 바이트 길이: {}", pdfBytes.length);
            if (pdfBytes.length == 0) {
                log.error("생성된 PDF 바이트가 0 입니다. scheduleId={}", schedule.getId());
                throw new RuntimeException("빈 PDF 바이트");
            }

            // 파일 쓰기 (옵션 명시)
            try {
                Files.write(target, pdfBytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                log.info("PDF 파일 저장 성공: {}", target);
            } catch (IOException e) {
                log.error("PDF 파일 쓰기 실패: target={}, err={}", target, e.getMessage(), e);
                throw new RuntimeException("PDF 파일 저장 실패", e);
            }

            // 저장 후 확인
            try {
                if (!Files.exists(target)) {
                    log.error("파일이 저장되지 않았습니다 (존재하지 않음): {}", target);
                    throw new RuntimeException("파일 저장 확인 실패");
                }
                long fileSize = Files.size(target);
                log.info("저장된 PDF 파일 크기: {} bytes", fileSize);
                if (fileSize == 0) {
                    log.error("저장된 파일 크기가 0 입니다: {}", target);
                    throw new RuntimeException("저장된 파일이 비어있음");
                }
            } catch (IOException e) {
                log.error("저장된 파일 검사 중 오류: {}", e.getMessage(), e);
                throw new RuntimeException("저장된 파일 검사 실패", e);
            }

            // 반환 URL 생성 (컨트롤러/클라이언트 규약 유지)
            String encodedFolder = URLEncoder.encode(safeFolderName, StandardCharsets.UTF_8);
            String encodedFilename = URLEncoder.encode(filename, StandardCharsets.UTF_8);
            String pdfUrl = "/uploads/work_schedule/" + encodedFolder + "/" + encodedFilename;

            log.info("PDF 생성 완료, 반환 URL: {}", pdfUrl);
            return pdfUrl;

        } catch (IOException e) {
            log.error("WorkSchedule PDF 생성/저장 실패: id={}, err={}", schedule.getId(), e.getMessage(), e);
            throw new RuntimeException("근무표 PDF 생성 실패", e);
        }
    }
}