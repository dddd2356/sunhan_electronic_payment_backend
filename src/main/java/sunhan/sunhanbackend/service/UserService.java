package sunhan.sunhanbackend.service;

import jakarta.persistence.EntityNotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.CachePut;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import sunhan.sunhanbackend.dto.response.*;
import sunhan.sunhanbackend.entity.mysql.UserEntity;
import sunhan.sunhanbackend.entity.oracle.OracleEntity;
import sunhan.sunhanbackend.enums.PermissionType;
import sunhan.sunhanbackend.enums.Role;
import sunhan.sunhanbackend.repository.mysql.DepartmentRepository;
import sunhan.sunhanbackend.repository.mysql.UserRepository;
import org.springframework.cache.annotation.Cacheable;
import sunhan.sunhanbackend.util.DateUtil;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Slf4j
@Service
public class UserService {

    private final UserRepository userRepository;
    private final OracleService oracleService;
    private final PasswordEncoder passwdEncoder;
    private final PermissionService permissionService;
    private final VacationService vacationService;
    private final DepartmentRepository departmentRepository;

    @Value("${file.upload.sign-dir}")
    private String uploadDir;  // "/uploads/signatures/"

    @Autowired
    public UserService(UserRepository userRepository, OracleService oracleService, PasswordEncoder passwdEncoder, PermissionService permissionService, DepartmentRepository departmentRepository, @Lazy VacationService vacationService) {
        this.userRepository = userRepository;
        this.oracleService = oracleService;
        this.passwdEncoder = passwdEncoder;
        this.permissionService = permissionService;
        this.departmentRepository = departmentRepository;
        this.vacationService = vacationService;
    }

    /**
     * 사용자 검색
     */
    @Transactional(readOnly = true)
    public List<UserEntity> searchUsers(String query) {
        if (query == null || query.trim().length() < 2) {
            return Collections.emptyList();
        }

        return userRepository.searchUsers(query.trim());
    }

    /**
     * 🔧 캐시 적용된 사용자 정보 조회
     */
    @Cacheable(value = "userCache", key = "#userId", unless = "#result == null")
    @Transactional(readOnly = true)
    public UserEntity getUserInfo(String userId) {
        // fetch-join 사용: department 프록시를 트랜잭션 안에서 초기화
        return userRepository.findByUserIdWithDepartment(userId)
                .orElseThrow(() -> new EntityNotFoundException("User not found: " + userId));
    }

    @Transactional(readOnly = true)
    public UserResponseDto getUserWithPermissions(String userId) {
        UserEntity user = getUserInfo(userId);

        UserResponseDto dto = new UserResponseDto();
        dto.setUserId(user.getUserId());
        dto.setUserName(user.getUserName());
        dto.setDeptCode(user.getDeptCode());
        dto.setJobLevel(user.getJobLevel());
        dto.setPhone(user.getPhone());
        dto.setAddress(user.getAddress());
        dto.setDetailAddress(user.getDetailAddress());
        dto.setSignimage(user.getSignimage());
        dto.setRole(user.getRole() != null ? user.getRole().toString() : null);
        dto.setPrivacyConsent(user.getPrivacyConsent());
        dto.setNotificationConsent(user.getNotificationConsent());
        // 🔹 엔티티를 String으로 변환
        List<String> userPerms = permissionService.getUserPermissions(userId).stream()
                .map(up -> up.getPermissionType().toString())
                .toList();

        List<String> deptPerms = permissionService.getDeptPermissions(user.getDeptCode()).stream()
                .map(dp -> dp.getPermissionType().toString())
                .toList();

        dto.setPermissions(
                Stream.concat(userPerms.stream(), deptPerms.stream())
                        .distinct()
                        .toList()
        );

        return dto;
    }

    //사용자 목록 useflag가 1인경우에 나오게끔 함. 총 휴가 계산하는 페이지, 근로계약서 생성 목록
    public List<UserEntity> findAllUsers() {
        System.out.println("=== DB에서 활성 사용자 조회 ===");
        return userRepository.findByUseFlag("1");
    }

    /**
     * 사용자 정보 (전화번호, 주소) 및 비밀번호 업데이트 메서드, 🔧 프로필 업데이트 - 캐시 갱신 적용
     */
    @Transactional
    @CachePut(value = "userCache", key = "#userId")
    public UserEntity updateProfile(String userId,
                                    String phone,
                                    String address,
                                    String detailAddress,
                                    String currentPassword,
                                    String newPassword,
                                    Boolean privacyConsent,        // 개인정보 동의
                                    Boolean notificationConsent   // 알림 동의
    ) {

        UserEntity user = getUserInfo(userId);

        // 1️⃣ 개인정보 동의 필수
        if (privacyConsent == null || !privacyConsent) {
            throw new RuntimeException("개인정보 수집·이용에 동의해야 합니다.");
        }
        user.setPrivacyConsent(true);

        // 2️⃣ 핸드폰 변경 처리 (인증 로직 완전 제거)
        if (phone != null && !phone.trim().isEmpty()) {
            user.setPhone(phone.trim());
        }

        // 3️⃣ 주소 업데이트
        if (address != null && !address.trim().isEmpty()) {
            user.setAddress(address.trim());
        }
        if (detailAddress != null && !detailAddress.trim().isEmpty()) {
            user.setDetailAddress(detailAddress.trim());
        }

        // 4️⃣ 비밀번호 업데이트
        if (newPassword != null && !newPassword.trim().isEmpty()) {
            if (currentPassword == null || currentPassword.isEmpty() ||
                    !passwdEncoder.matches(currentPassword, user.getPasswd())) {
                throw new RuntimeException("현재 비밀번호가 일치하지 않습니다.");
            }
            String encodedNewPasswd = passwdEncoder.encode(newPassword);
            user.setPasswd(encodedNewPasswd);
            user.setPasswordChangeRequired(false);
        }

        // 5️⃣ 알림 동의 선택적 업데이트
        if (notificationConsent != null) {
            user.setNotificationConsent(notificationConsent);
        }

        UserEntity savedUser = userRepository.save(user);

        return savedUser;
    }

    /**
     * 로그인 인증 메서드
     * 1) MySQL에 사용자가 있는지 확인
     * 2) 있으면 BCrypt 검사
     * 3) 없으면, 입력한 비밀번호가 userId와 동일하고 Oracle에 존재할 경우
     *    Oracle에서 유저 정보 가져와 MySQL에 저장 → 인증 성공
     *    🔧 로그인 인증 - 성능 최적화
     */
    @Transactional
    public boolean authenticateUser(String userId, String password) {
        // 1. 'administrator' 계정 예외 처리
        if ("administrator".equalsIgnoreCase(userId)) {
            log.info("administrator 로그인: Oracle 동기화 과정을 건너뜁니다.");
            Optional<UserEntity> adminUser = userRepository.findByUserId(userId);
            if (adminUser.isPresent()) {
                boolean matches = passwdEncoder.matches(password, adminUser.get().getPasswd());
                log.info("✅ Administrator 인증 결과: {}", matches);
                return matches;
            } else {
                log.error("❌ Administrator 계정이 MySQL에 존재하지 않습니다.");
                return false;
            }
        }

        // 2. MySQL에 사용자가 존재하는지 확인
        Optional<UserEntity> userOpt = userRepository.findByUserId(userId);

        if (userOpt.isPresent()) {
            UserEntity user = userOpt.get();

            try {
                OracleEntity oracleUser = oracleService.getOracleUserInfo(userId);

                boolean needsUpdate = false;

                if (!Objects.equals(user.getUseFlag(), oracleUser.getUseFlag())) {
                    user.setUseFlag(oracleUser.getUseFlag());
                    needsUpdate = true;
                }

                if (!Objects.equals(user.getUserName(), oracleUser.getUsrKorName())) {
                    user.setUserName(oracleUser.getUsrKorName());
                    needsUpdate = true;
                }

                if (!Objects.equals(user.getDeptCode(), oracleUser.getDeptCode())) {
                    user.setDeptCode(oracleUser.getDeptCode());
                    needsUpdate = true;
                }

                if (!Objects.equals(user.getJobType(), oracleUser.getJobType())) {
                    user.setJobType(oracleUser.getJobType());
                    needsUpdate = true;
                }

                LocalDate oracleStartDate = DateUtil.parseOracleDate(oracleUser.getStartDate());
                if (!Objects.equals(user.getStartDate(), oracleStartDate)) {
                    user.setStartDate(oracleStartDate);
                    needsUpdate = true;
                }

                if (needsUpdate) {
                    log.info("Oracle의 정보가 MySQL과 다릅니다. 사용자 {}의 정보를 동기화합니다.", userId);
                    userRepository.saveAndFlush(user);
                }

                if (!"1".equals(oracleUser.getUseFlag())) {
                    log.warn("사용자 {}의 로그인이 차단되었습니다. Oracle상 비활성 상태입니다.", userId);
                    return false;
                }

            } catch (Exception e) {
                log.error("로그인 중 Oracle DB 동기화 실패: {}. 로그인 프로세스를 중단합니다.", userId, e);
                return false;
            }

            boolean result = passwdEncoder.matches(password, user.getPasswd());
            log.info("✅ 기존 사용자 인증 결과 ({}): {}", userId, result);
            return result;

        } else {
            // 3. MySQL에 없으면 Oracle 마이그레이션
            if (password.equals(userId) && oracleService.isUserExistsInOracle(userId)) {
                try {
                    String encodedPasswordForMigration = passwdEncoder.encode(password);

                    // ✅ 마이그레이션 전 한 번 더 체크
                    if (userRepository.existsById(userId)) {
                        log.warn("⚠️ 마이그레이션 직전 중복 감지: {}", userId);
                        UserEntity existing = userRepository.findByUserId(userId).get();
                        return passwdEncoder.matches(password, existing.getPasswd());
                    }

                    // ✅ 마이그레이션 실행 후 즉시 flush
                    UserEntity migratedUser = oracleService.migrateUserFromOracle(userId, encodedPasswordForMigration);
                    userRepository.flush(); // ✅ 강제 DB 동기화

                    log.info("✅ [첫 로그인] Oracle 유저 '{}' MySQL 마이그레이션 완료 (ID: {})",
                            userId, migratedUser.getUserId());

                    return true;

                } catch (org.springframework.dao.DataIntegrityViolationException e) {
                    log.error("❌ 중복 키 오류로 마이그레이션 실패: {}. 기존 계정으로 로그인 시도", userId);
                    userRepository.flush(); // ✅ 에러 발생 시에도 flush

                    Optional<UserEntity> existing = userRepository.findByUserId(userId);
                    if (existing.isPresent()) {
                        return passwdEncoder.matches(password, existing.get().getPasswd());
                    }
                    return false;
                } catch (Exception e) {
                    log.error("❌ Oracle 유저 마이그레이션 실패: {}", userId, e);
                    return false;
                }
            }
        }

        log.warn("❌ 인증 실패: {} (비밀번호 불일치 또는 Oracle에 없음)", userId);
        return false;
    }

    /**
     * 🔧 사용자 권한 조회 (캐시 적용)
     */
    @Cacheable(value = "userRoleCache", key = "#userId")
    public String getUserRole(String userId) {
        UserEntity user = getUserInfo(userId);
        return user.getRole().toString();
    }


    /**
     * 사인 이미지 업로드 (개선된 버전)
     * - 파일시스템에 저장
     * - DB에 BLOB(signimage) + 경로(signpath) 저장 (YYYYMMDD_사번_이름.ext)
     * - 🔧 서명 업로드 - 트랜잭션 및 예외 처리 강화
     */
    @Transactional
    @CacheEvict(value = "userCache", key = "#userId", beforeInvocation = true)
    public void uploadSignature(String userId, MultipartFile file) throws IOException {
        log.info("=== 서명 업로드 요청 시작 === userId={}", userId);

        // 1. 사용자 조회
        Optional<UserEntity> userOpt = userRepository.findByUserIdNoCache(userId);
        if (userOpt.isEmpty()) {
            log.error("사용자 조회 실패 - userId={}", userId);
            throw new IllegalArgumentException("사용자를 찾을 수 없습니다: " + userId);
        }
        UserEntity user = userOpt.get();
        log.info("사용자 조회 성공 - userId={}, name={}", userId, user.getUserName());

        // 2. 파일 검증 (가장 중요한 부분)
        if (file == null) {
            log.error("MultipartFile 객체가 null입니다 - userId={}", userId);
            throw new IllegalArgumentException("파일 파라미터 'file'이 전송되지 않았습니다 (MultipartFile is null)");
        }

        log.info("파일 기본 정보 - originalFilename={}, size={}, contentType={}",
                file.getOriginalFilename(), file.getSize(), file.getContentType());

        if (file.isEmpty()) {
            log.error("파일이 비어있습니다 - userId={}, filename={}, size=0",
                    userId, file.getOriginalFilename());
            throw new IllegalArgumentException("업로드된 파일이 비어 있습니다 (파일 크기 0 byte)");
        }

        // 3. 파일 크기 제한
        if (file.getSize() > 5 * 1024 * 1024) {
            log.warn("파일 크기 초과 - size={} bytes", file.getSize());
            throw new IllegalArgumentException("파일 크기는 5MB를 초과할 수 없습니다.");
        }

        try {
            String original = Objects.requireNonNull(file.getOriginalFilename(), "파일 이름이 null입니다");
            String ext = original.substring(original.lastIndexOf('.')).toLowerCase();

            if (!Arrays.asList(".jpg", ".jpeg", ".png", ".gif").contains(ext)) {
                log.warn("지원하지 않는 확장자 - ext={}", ext);
                throw new IllegalArgumentException("지원하지 않는 파일 형식입니다. (jpg, jpeg, png, gif만 가능)");
            }

            String nameFiltered = user.getUserName()
                    .replaceAll("[^\\p{L}0-9\\s]", "")
                    .trim()
                    .replaceAll("\\s+", "_")
                    .replaceAll("_+", "_")
                    .replaceAll("^_+|_+$", "");

            String folderName = String.format("%s_%s", nameFiltered, userId);
            Path userDir = Paths.get("C:", "sunhan_electronic_payment", "sign_image", folderName)
                    .toAbsolutePath().normalize();

            if (!Files.exists(userDir)) {
                Files.createDirectories(userDir);
                log.info("디렉토리 생성 완료: {}", userDir);
            }

            String filename = String.format("%s_sign_image%s", nameFiltered, ext);
            Path targetPath = userDir.resolve(filename);

            // 실제 파일 복사
            Files.copy(file.getInputStream(), targetPath, StandardCopyOption.REPLACE_EXISTING);
            log.info("파일 저장 성공: {}", targetPath);

            // DB 업데이트
            user.setSignimage(file.getBytes());
            String encodedFolder = URLEncoder.encode(folderName, StandardCharsets.UTF_8);
            String encodedFilename = URLEncoder.encode(filename, StandardCharsets.UTF_8);
            user.setSignpath("/uploads/sign_image/" + encodedFolder + "/" + encodedFilename);

            userRepository.save(user);
            log.info("DB 업데이트 완료 - signpath={}", user.getSignpath());

        } catch (IOException e) {
            log.error("서명 파일 업로드 중 IO 오류 발생 - userId={}", userId, e);
            throw new IOException("서명 파일 업로드 중 오류가 발생했습니다.", e);
        } catch (Exception e) {
            log.error("서명 업로드 중 예기치 않은 오류 - userId={}", userId, e);
            throw e;
        }
    }


    /**
     * 사용자의 사인 이미지를 Base64 데이터 URL 형식으로 반환
     * 프론트엔드에서 <img> 태그의 src에 직접 사용할 수 있는 형식
     * 🔧 서명 이미지 조회 - 캐시 적용
     */
    @Cacheable(value = "signatureCache", key = "#userId",
            condition = "#result != null", unless = "#result.isEmpty()")
    public Map<String, String> getUserSignatureAsBase64(String userId) {
        Optional<UserEntity> userOpt = userRepository.findByUserId(userId);
        UserEntity user = userOpt.orElse(null);
        if (user == null) {
            throw new IllegalArgumentException("사용자를 찾을 수 없습니다: " + userId);
        }

        byte[] imageBytes = user.getSignimage();
        if (imageBytes == null || imageBytes.length == 0) {
            throw new IllegalArgumentException("사용자의 서명 이미지가 없습니다: " + userId);
        }

        String base64 = Base64.getEncoder().encodeToString(imageBytes);
        String dataUrl = "data:image/png;base64," + base64;

        Map<String, String> result = new HashMap<>();
        result.put("imageUrl", dataUrl);
        result.put("signPath", user.getSignpath());
        result.put("userId", userId);
        result.put("userName", user.getUserName());

        return result;
    }

    /**
     * 사용자의 사인 이미지를 바이너리 데이터로 반환
     * HTTP 응답으로 직접 이미지를 전송할 때 사용
     */
    public byte[] getUserSignatureImage(String userId) {
        Optional<UserEntity> userOpt = userRepository.findByUserId(userId);
        UserEntity user = userOpt.orElse(null);
        if (user == null) {
            throw new IllegalArgumentException("사용자를 찾을 수 없습니다: " + userId);
        }

        byte[] imageBytes = user.getSignimage();
        if (imageBytes == null || imageBytes.length == 0) {
            throw new IllegalArgumentException("사용자의 서명 이미지가 없습니다: " + userId);
        }

        return imageBytes;
    }

    /**
     * 사용자의 서명 정보 (경로, 존재 여부 등)를 간단히 반환
     */
    public Map<String, Object> getUserSignatureInfo(String userId) {
        Optional<UserEntity> userOpt = userRepository.findByUserId(userId);
        UserEntity user = userOpt.orElse(null);
        Map<String, Object> info = new HashMap<>();

        if (user == null) {
            info.put("exists", false);
            info.put("error", "사용자를 찾을 수 없습니다");
            return info;
        }

        boolean hasSignature = user.getSignimage() != null && user.getSignimage().length > 0;
        info.put("exists", hasSignature);
        info.put("signPath", user.getSignpath());
        info.put("userId", userId);
        info.put("userName", user.getUserName());

        return info;
    }

    /**
     * 🔧 권한 부여 - 트랜잭션 및 캐시 갱신 적용
     */
    @Transactional
    @CacheEvict(value = {"userCache", "userRoleCache"}, key = "#targetUserId")
    public void grantAdminRole(String adminUserId, String targetUserId) {
        if (!canManageUser(adminUserId, targetUserId)) {
            throw new RuntimeException("해당 사용자의 권한을 변경할 수 없습니다.");
        }

        try {
            // 최신 데이터를 강제로 DB에서 조회
            UserEntity target = userRepository.findByUserId(targetUserId)
                    .orElseThrow(() -> new RuntimeException("대상 사용자를 찾을 수 없습니다: " + targetUserId));

            // 이미 ADMIN 역할인 경우 중복 처리 방지
            if (target.getRole() == Role.ADMIN) {
                log.info("사용자 {}는 이미 ADMIN 권한입니다", targetUserId);
                return;
            }

            target.setRole(Role.ADMIN);
            userRepository.saveAndFlush(target); // 즉시 DB에 반영

            log.info("사용자 {}가 {}에게 ADMIN 권한 부여 완료", adminUserId, targetUserId);

        } catch (org.springframework.dao.OptimisticLockingFailureException e) {
            log.warn("낙관적 락킹 실패 - 재시도: {}", targetUserId);
            throw new RuntimeException("다른 사용자가 동시에 수정하고 있습니다. 잠시 후 다시 시도해주세요.");
        } catch (Exception e) {
            log.error("권한 부여 중 오류 발생: userId={}", targetUserId, e);
            throw new RuntimeException("권한 부여 중 오류가 발생했습니다: " + e.getMessage());
        }
    }


    /**
     * 권한 부여 (JobLevel + DeptCode 기반)
     */
    @Transactional
    public void grantAdminRoleByCondition(String adminUserId, String jobLevel, String deptCode) {
        Optional<UserEntity> userOpt = userRepository.findByUserId(adminUserId);
        UserEntity admin = userOpt.orElse(null);
        if (admin == null || !admin.isAdmin()) {
            throw new RuntimeException("권한이 없습니다.");
        }

        int adminLevel = Integer.parseInt(admin.getJobLevel());
        int targetLevel = Integer.parseInt(jobLevel);

        if (adminLevel < targetLevel) {
            throw new RuntimeException("자신보다 높은 JobLevel의 사용자에게 권한을 부여할 수 없습니다.");
        }

        if (adminLevel == 1 && !admin.getDeptCode().equals(deptCode)) {
            throw new RuntimeException("같은 부서의 사용자만 관리할 수 있습니다.");
        }

        // 배치 처리로 성능 최적화
        List<UserEntity> targets = userRepository.findByJobLevelAndDeptCode(jobLevel, deptCode);
        if (targets.isEmpty()) {
            throw new RuntimeException("해당 조건에 맞는 사용자가 없습니다.");
        }

        targets.forEach(target -> {
            target.setRole(Role.ADMIN);
            log.info("사용자 {}가 {}에게 ADMIN 권한 부여", adminUserId, target.getUserId());
        });

        userRepository.saveAll(targets); // 배치 저장
    }

    /**
     * JobLevel 변경
     */
    public void updateJobLevel(String adminUserId, String targetUserId, String newJobLevel) {
        // 권한 검증
        if (!canManageUser(adminUserId, targetUserId)) {
            throw new RuntimeException("해당 사용자의 JobLevel을 변경할 수 없습니다.");
        }

        // Optional을 사용하여 안전하게 사용자 정보 조회
        UserEntity admin = userRepository.findByUserId(adminUserId)
                .orElseThrow(() -> new RuntimeException("관리자를 찾을 수 없습니다: " + adminUserId));
        UserEntity target = userRepository.findByUserId(targetUserId)
                .orElseThrow(() -> new RuntimeException("대상 사용자를 찾을 수 없습니다: " + targetUserId));


        if (target == null) {
            throw new RuntimeException("대상 사용자를 찾을 수 없습니다: " + targetUserId);
        }

        // JobLevel 유효성 검사
        int newLevel;
        try {
            newLevel = Integer.parseInt(newJobLevel);
            if (newLevel < 0 || newLevel > 6) {
                throw new RuntimeException("JobLevel은 0-6 사이의 값이어야 합니다.");
            }
        } catch (NumberFormatException e) {
            throw new RuntimeException("올바르지 않은 JobLevel 형식입니다.");
        }

        // 관리자보다 높은 레벨로 설정 불가
        int adminLevel = Integer.parseInt(admin.getJobLevel());
        if (adminLevel < newLevel && adminLevel != 6) { // 최고관리자(6) 제외
            throw new RuntimeException("자신보다 높은 JobLevel로 설정할 수 없습니다.");
        }

        String oldJobLevel = target.getJobLevel();
        target.setJobLevel(newJobLevel);

        // JobLevel에 따른 Role 자동 조정
        if (newLevel >= 2) {
            target.setRole(Role.ADMIN);
        } else if (target.getRole() == Role.ADMIN && newLevel < 2) {
            log.info("사용자 {} jobLevel이 {}로 변경되었으나 role은 변경하지 않습니다 (자동 강등 방지).",
            targetUserId, newJobLevel);
        }

        userRepository.saveAndFlush(target);
        log.info("사용자 {}가 {}의 JobLevel을 {}에서 {}로 변경",
                adminUserId, targetUserId, oldJobLevel, newJobLevel);
    }

    /**
     * 부서별 활성 직원 조회 (대직자 후보용)
     */
    public List<UserEntity> getActiveUsersByDeptCode(String adminUserId, String deptCode) {
        Optional<UserEntity> userOpt = userRepository.findByUserId(adminUserId);
        UserEntity admin = userOpt.orElseThrow(() -> new EntityNotFoundException("사용자를 찾을 수 없습니다: " + adminUserId));

        int adminLevel;
        try {
            adminLevel = Integer.parseInt(admin.getJobLevel());
        } catch (NumberFormatException e) {
            log.warn("관리자 {}의 JobLevel이 올바르지 않습니다: {}", adminUserId, admin.getJobLevel());
            throw new RuntimeException("관리자의 JobLevel이 올바르지 않습니다.");
        }

        if (adminLevel == 1) {
            if (!admin.getDeptCode().equals(deptCode)) {
                throw new RuntimeException("자신의 부서만 조회할 수 있습니다.");
            }
        }

        // useFlag가 "1"인 활성 사용자만 조회
        List<UserEntity> users = userRepository.findByDeptCodeAndUseFlag(deptCode, "1");
        log.info("사용자 {}가 부서 {} 활성 직원 목록 조회 ({}명)", adminUserId, deptCode, users.size());

        return users;
    }

    /**
     * 부서별 직원 조회 (JobLevel 1인 사람용)
     */
    public List<UserEntity> getUsersByDeptForAdmin(String adminUserId, String deptCode) {
        Optional<UserEntity> userOpt = userRepository.findByUserId(adminUserId);
        // 사용자가 존재하지 않으면 예외를 던지는 것이 더 안전합니다.
        UserEntity admin = userOpt.orElseThrow(() -> new EntityNotFoundException("사용자를 찾을 수 없습니다: " + adminUserId));

        // *** 수정된 부분: isAdmin() 체크를 제거합니다. ***
        // 권한 확인은 이 메서드를 호출하는 컨트롤러에서 이미 수행했거나,
        // 아래의 jobLevel별 로직에서 처리하는 것이 더 적합합니다.

        int adminLevel;
        try {
            adminLevel = Integer.parseInt(admin.getJobLevel());
        } catch (NumberFormatException e) {
            // 관리자의 JobLevel이 유효하지 않은 경우에도 오류를 로깅하고 진행할 수 있습니다.
            log.warn("관리자 {}의 JobLevel이 올바르지 않습니다: {}", adminUserId, admin.getJobLevel());
            throw new RuntimeException("관리자의 JobLevel이 올바르지 않습니다.");
        }

        // JobLevel 1(부서장)인 경우, 자신의 부서만 조회 가능하도록 제한하는 로직은 그대로 유지합니다.
        // 이 로직은 여전히 유효하며, 대직자 조회와는 다른 케이스(부서원 조회)에 필요할 수 있습니다.
        if (adminLevel == 1) {
            if (!admin.getDeptCode().equals(deptCode)) {
                throw new RuntimeException("자신의 부서만 조회할 수 있습니다.");
            }
        }

        List<UserEntity> users = userRepository.findByDeptCode(deptCode);
        log.info("사용자 {}가 부서 {} 직원 목록 조회 ({}명)", adminUserId, deptCode, users.size());

        return users;
    }

    /**
     * 권한 검증 - 관리자가 대상 사용자를 관리할 수 있는지 확인
     */
    /**
     * 권한 검증 메서드
     */
    public boolean canManageUser(String managerUserId, String targetUserId) {
        Optional<UserEntity> managerOpt = userRepository.findByUserId(managerUserId);
        Optional<UserEntity> targetOpt = userRepository.findByUserId(targetUserId);

        if (!managerOpt.isPresent() || !targetOpt.isPresent()) {
            return false;
        }
        UserEntity manager = managerOpt.get();
        UserEntity target = targetOpt.get();

        int managerLevel = Integer.parseInt(manager.getJobLevel());

        if (manager.getRole() == Role.ADMIN && managerLevel >= 2) {
            return !managerUserId.equals(targetUserId);
        }

        if (manager.getRole() == Role.ADMIN && (managerLevel == 0 || managerLevel == 1)) {
            Set<PermissionType> managerPermissions = permissionService.getAllUserPermissions(managerUserId);
            boolean hasLeavePermission = managerPermissions.contains(PermissionType.HR_LEAVE_APPLICATION);
            boolean hasContractPermission = managerPermissions.contains(PermissionType.HR_CONTRACT);
            return hasLeavePermission || hasContractPermission;
        }

        if (manager.getRole() == Role.ADMIN && managerLevel == 1) {
            return manager.getDeptCode().equals(target.getDeptCode());
        }

        return false;
    }

    /**
     * 관리 가능한 사용자 목록 조회
     */
    @Transactional(readOnly = true)
    public List<UserEntity> getManageableUsers(String adminUserId) {
        // 1) 안전하게 관리자 조회
        UserEntity admin;
        try {
            admin = getUserInfo(adminUserId); // getUserInfo는 EntityNotFoundException을 던질 수 있음
        } catch (EntityNotFoundException ex) {
            log.warn("getManageableUsers: admin not found: {}", adminUserId);
            throw new RuntimeException("권한이 없습니다.");
        }

        if (admin == null || !admin.isAdmin()) {
            log.warn("getManageableUsers: user is not admin or null: {}", adminUserId);
            throw new RuntimeException("권한이 없습니다.");
        }

        // 2) jobLevel 파싱 - 안전하게 처리
        int adminLevel = 0;
        try {
            String jl = admin.getJobLevel();
            if (jl != null && !jl.isBlank()) {
                adminLevel = Integer.parseInt(jl.trim());
            } else {
                log.warn("getManageableUsers: jobLevel is null/blank for admin {}", adminUserId);
                adminLevel = 0; // 기본값
            }
        } catch (NumberFormatException nfe) {
            log.warn("getManageableUsers: invalid jobLevel for admin {}: {}", adminUserId, admin.getJobLevel());
            adminLevel = 0; // 기본값으로 안전하게 동작
        }

        // 3) 조건별로 효율적인 쿼리 실행
        List<UserEntity> manageableUsers;
        if (adminLevel >= 6) { // 최고 관리자: 전체 조회
            manageableUsers = userRepository.findAll();
        } else if (adminLevel >= 2) { // 상위 관리자: 특정 레벨들만 조회
            List<String> managableLevels = Arrays.asList("0", "1");
            manageableUsers = userRepository.findByJobLevelIn(managableLevels);
        } else if (adminLevel == 1) { // 부서 관리자: 같은 부서만 조회
            String deptCode = admin.getDeptCode();
            if (deptCode == null || deptCode.isBlank()) {
                log.warn("getManageableUsers: admin {} has no deptCode", adminUserId);
                manageableUsers = new ArrayList<>();
            } else {
                manageableUsers = userRepository.findByDeptCodeOrderByJobLevelAndName(deptCode);
            }
        } else { // adminLevel == 0 등: 기본적으로 빈 목록
            manageableUsers = new ArrayList<>();
        }

        // 4) 자신을 목록에 추가 (중복 방지)
        if (manageableUsers.stream().noneMatch(u -> u.getUserId().equals(adminUserId))) {
            manageableUsers.add(admin);
        }

        return manageableUsers;
    }

    /**
     * 전체 부서 조회 (사용자가 접근 가능)
     */
    public List<DepartmentDto> getAllDepartments() {
        // deptCode 중복 제거하고 List로 변환
        List<String> deptCodes = userRepository.findAllActiveDeptCodes();

        // deptName은 deptCode 기준으로 UserEntity 첫번째 값에서 가져오기
        return deptCodes.stream()
                .map(code -> userRepository.findFirstByDeptCodeAndUseFlag(code, "1")
                        .map(u -> new DepartmentDto(
                                u.getDeptCode(),
                                u.getDeptCode(), // deptName이 따로 없으면 코드 사용
                                null // 상위 부서 정보 없으면 null
                        ))
                        .orElse(null))
                .filter(d -> d != null)
                .collect(Collectors.toList());
    }

    /**
     * 부서 코드로 직원 목록 조회
     */
    public List<UserEntity> getActiveUsersByDept(String currentUserId, String deptCode) {

        return userRepository.findByDeptCodeAndUseFlag(deptCode, "1").stream()
                .filter(u -> !"1".equals(u.getJobType())) // ✅ jobtype=1 제외
                .collect(Collectors.toList());
    }


    /**
     * 권한 제거 (ADMIN -> USER로 변경) - 동시성 문제 해결
     */
    @Transactional
    @CacheEvict(value = {"userCache", "userRoleCache"}, key = "#targetUserId")
    public void revokeAdminRole(String adminUserId, String targetUserId) {
        // 권한 검증
        if (!canManageUser(adminUserId, targetUserId)) {
            throw new RuntimeException("해당 사용자의 권한을 변경할 수 없습니다.");
        }

        try {
            // 최신 데이터를 강제로 DB에서 조회 (캐시 우회)
            UserEntity target = userRepository.findByUserId(targetUserId)
                    .orElseThrow(() -> new RuntimeException("대상 사용자를 찾을 수 없습니다: " + targetUserId));

            // 이미 USER 역할인 경우 중복 처리 방지
            if (target.getRole() == Role.USER) {
                log.info("사용자 {}는 이미 USER 권한입니다", targetUserId);
                return;
            }

            target.setRole(Role.USER);
            userRepository.saveAndFlush(target); // 즉시 DB에 반영

            log.info("사용자 {}가 {}의 ADMIN 권한 제거 완료", adminUserId, targetUserId);

        } catch (org.springframework.dao.OptimisticLockingFailureException e) {
            log.warn("낙관적 락킹 실패 - 재시도: {}", targetUserId);
            throw new RuntimeException("다른 사용자가 동시에 수정하고 있습니다. 잠시 후 다시 시도해주세요.");
        } catch (Exception e) {
            log.error("권한 제거 중 오류 발생: userId={}", targetUserId, e);
            throw new RuntimeException("권한 제거 중 오류가 발생했습니다: " + e.getMessage());
        }
    }

    /**
     * [권한 체크 O] 부서 패턴 조회
     * 일반적인 부서 직원 조회에 사용
     */
    public List<UserEntity> getActiveUsersByDeptPattern(String requestUserId, String baseDeptCode) {
        UserEntity requester = getUserInfo(requestUserId);
        int requesterLevel = Integer.parseInt(requester.getJobLevel());

        // ✅ 부서장은 자기 부서 계열만 조회 가능
        if (requesterLevel == 1) {
            String requesterDept = requester.getDeptCode();
            String requesterBase = requesterDept.replaceAll("\\d+$", "");
            String targetBase = baseDeptCode.replaceAll("\\d+$", "");

            if (!requesterBase.equals(targetBase)) {
                throw new RuntimeException("자신의 부서만 조회할 수 있습니다.");
            }
        }

        return userRepository.findAll().stream()
                .filter(u -> "1".equals(u.getUseFlag()))
                .filter(u -> !"1".equals(u.getJobType()))
                .filter(u -> {
                    String deptCode = u.getDeptCode();
                    if (deptCode == null) return false;
                    return deptCode.equals(baseDeptCode) ||
                            (deptCode.startsWith(baseDeptCode) &&
                                    deptCode.substring(baseDeptCode.length()).matches("^\\d+$"));
                })
                .collect(Collectors.toList());
    }

    /**
     * [권한 체크 X] 전체 부서 패턴 조회
     * 커스텀 근무표, 결재라인 생성 등에 사용
     */
    public List<UserEntity> getAllUsersByDeptPattern(String baseDeptCode) {
        String base = baseDeptCode.replaceAll("\\d+$", ""); // 숫자 제외
        return userRepository.findAll().stream()
                .filter(u -> "1".equals(u.getUseFlag()))
                .filter(u -> {
                    String deptCode = u.getDeptCode();
                    if (deptCode == null) return false;
                    String userBase = deptCode.replaceAll("\\d+$", ""); // 사용자 deptCode도 숫자 제외
                    return userBase.equals(base); // base로 묶음
                })
                .collect(Collectors.toList());
    }
    @Transactional(readOnly = true)
    @Cacheable(value = "userDtoCache", key = "#targetUserId", unless = "#result == null")
    public UserResponseDto getUserResponseDto(String targetUserId, String requesterId) {
        UserEntity user = userRepository.findByUserIdWithDepartment(targetUserId)
                .orElseThrow(() -> new EntityNotFoundException("User not found: " + targetUserId));

        UserResponseDto dto = new UserResponseDto();
        dto.setUserId(user.getUserId());
        dto.setUserName(user.getUserName());
        dto.setDeptCode(user.getDeptCode());
        dto.setJobType(user.getJobType());
        dto.setJobLevel(user.getJobLevel());
        dto.setPhone(user.getPhone());
        dto.setAddress(user.getAddress());
        dto.setDetailAddress(user.getDetailAddress());
        dto.setRole(user.getRole() != null ? user.getRole().toString() : null);
        dto.setSignimage(null);
        dto.setPrivacyConsent(user.getPrivacyConsent());
        dto.setNotificationConsent(user.getNotificationConsent());
        dto.setUseFlag(user.getUseFlag());

        List<String> userPerms = permissionService.getUserPermissions(user.getUserId()).stream()
                .map(up -> up.getPermissionType().toString())
                .collect(Collectors.toList());
        List<String> deptPerms = permissionService.getDeptPermissions(user.getDeptCode()).stream()
                .map(dp -> dp.getPermissionType().toString())
                .collect(Collectors.toList());
        dto.setPermissions(Stream.concat(userPerms.stream(), deptPerms.stream()).distinct().collect(Collectors.toList()));

        // ✅ 연차 정보 조회 - 예외 처리 강화
        try {
            VacationStatusResponseDto vacationStatus = vacationService.getVacationStatus(
                    user.getUserId(),
                    LocalDate.now().getYear()
            );
            dto.setTotalVacationDays(vacationStatus.getAnnualTotalDays());
            dto.setUsedVacationDays(vacationStatus.getAnnualUsedDays());
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            // ✅ 중복 키 오류 무시하고 기본값 설정
            log.warn("연차 정보 중복 키 오류 무시: userId={}", user.getUserId());
            dto.setTotalVacationDays(0.0);
            dto.setUsedVacationDays(0.0);
        } catch (Exception e) {
            log.warn("연차 정보 조회 실패: userId={}", user.getUserId(), e);
            dto.setTotalVacationDays(0.0);
            dto.setUsedVacationDays(0.0);
        }

        return dto;
    }

    /**
     * UserEntity를 UserResponseDto로 변환 (부서 이름 포함)
     */
    private UserResponseDto convertToUserResponseDto(UserEntity user) {
        UserResponseDto dto = new UserResponseDto();
        dto.setUserId(user.getUserId());
        dto.setUserName(user.getUserName());
        dto.setDeptCode(user.getDeptCode());
        dto.setJobType(user.getJobType());
        dto.setJobLevel(user.getJobLevel());
        dto.setPhone(user.getPhone());
        dto.setAddress(user.getAddress());
        dto.setDetailAddress(user.getDetailAddress());
        dto.setRole(user.getRole() != null ? user.getRole().toString() : null);
        dto.setUseFlag(user.getUseFlag());

        // ✅ 부서 이름 설정 (OS1 -> OS로 매칭)
        String baseDeptCode = user.getDeptCode().replaceAll("\\d+$", ""); // OS1 -> OS

        // ✅ DepartmentRepository에서 직접 조회
        departmentRepository.findByDeptCode(baseDeptCode)
                .ifPresent(dept -> dto.setDeptName(dept.getDeptName()));

        return dto;
    }

    /**
     * 활성 사용자 목록을 DTO로 변환하여 반환 (계약서 생성 모달용)
     */
    @Transactional(readOnly = true)
    public List<UserResponseDto> findAllUsersAsDto() {
        List<UserEntity> users = userRepository.findByUseFlag("1");
        return users.stream()
                .map(this::convertToUserResponseDto)
                .collect(Collectors.toList());
    }

    /**
     * 💡 [NEW] Admin용 사용자 목록 조회 (Server-side Pagination 적용)
     * 전체 사용자 데이터를 한 번에 가져오지 않고 요청된 페이지의 데이터만 가져옵니다.
     */
    public UserListResponseDto getAllUsersByAdminWithPaging(boolean showAll, String searchTerm, Pageable pageable) {
        String finalSearchTerm = searchTerm != null && !searchTerm.trim().isEmpty() ? searchTerm.toLowerCase() : null;

        Page<UserEntity> userPage = userRepository.findAllUsersWithPaging(showAll, finalSearchTerm, pageable);

        List<UserResponseDto> userDtos = userPage.getContent().stream()
                .map(u -> {
                    UserResponseDto dto = convertToUserResponseDto(u); // 기존 DTO 변환 로직 사용
                    // 여기서는 DeptCode base 그룹화만 다시 처리 (AdminController에서 하던 로직)
                    dto.setDeptCode(u.getDeptCode().replaceAll("\\d+$", ""));
                    return dto;
                })
                .collect(Collectors.toList());

        return new UserListResponseDto(
                userDtos,
                userPage.getTotalElements(),
                userPage.getTotalPages(),
                userPage.getNumber(),
                userPage.getSize()
        );
    }

    @Transactional(readOnly = true)
    public List<UserEntity> getAllUsersForStats() {
        return userRepository.findAll().stream()
                .filter(u -> !"000".equals(u.getDeptCode()))
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public AdminStatsDto getAdminDashboardStats() {
        // 💡 Repository의 전용 COUNT 쿼리를 호출합니다.
        long total = userRepository.count();
        long active = userRepository.countByUseFlag("1");
        long inactive = userRepository.countByUseFlag("0");

        int totalDepartments = userRepository.findAllActiveDeptCodes().size();

        return AdminStatsDto.builder()
                .totalUsers(total)
                .activeUsers(active)
                .inactiveUsers(inactive)
                .totalDepartments(totalDepartments)
                .build();
    }

    /**
     * 💡 [NEW] 부서별 사용자 목록 조회 (Server-side Pagination 적용)
     * jobLevel 1 부서장 전용
     */
    @Transactional(readOnly = true)
    public UserListResponseDto getDepartmentUsersByAdminWithPaging(
            String deptBase, boolean showAll, String searchTerm, Pageable pageable) {

        String finalSearchTerm = searchTerm != null && !searchTerm.trim().isEmpty()
                ? searchTerm.toLowerCase() : null;

        // ✅ 부서 기반 필터링이 추가된 쿼리 실행
        Page<UserEntity> userPage = userRepository.findDepartmentUsersWithPaging(
                deptBase, showAll, finalSearchTerm, pageable);

        List<UserResponseDto> userDtos = userPage.getContent().stream()
                .map(u -> {
                    UserResponseDto dto = convertToUserResponseDto(u);
                    dto.setDeptCode(u.getDeptCode().replaceAll("\\d+$", ""));
                    return dto;
                })
                .collect(Collectors.toList());

        return new UserListResponseDto(
                userDtos,
                userPage.getTotalElements(),
                userPage.getTotalPages(),
                userPage.getNumber(),
                userPage.getSize()
        );
    }
}
