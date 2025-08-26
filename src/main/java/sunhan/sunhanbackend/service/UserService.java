package sunhan.sunhanbackend.service;

import jakarta.persistence.EntityNotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.CachePut;
import org.springframework.scheduling.annotation.Async;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import sunhan.sunhanbackend.entity.mysql.UserEntity;
import sunhan.sunhanbackend.enums.Role;
import sunhan.sunhanbackend.repository.mysql.UserRepository;
import org.springframework.cache.annotation.Cacheable;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.CompletableFuture;

@Slf4j
@Service
public class UserService {

    private final UserRepository userRepository;
    private final OracleService oracleService;
    private final PasswordEncoder passwdEncoder = new BCryptPasswordEncoder();

    @Value("${file.upload.sign-dir}")
    private String uploadDir;  // "/uploads/signatures/"

    @Autowired
    public UserService(UserRepository userRepository, OracleService oracleService) {
        this.userRepository = userRepository;
        this.oracleService = oracleService;
    }

    /**
     * 🔧 캐시 적용된 사용자 정보 조회
     */
    @Cacheable(value = "userCache", key = "#userId", unless = "#result == null")
    @Transactional(readOnly = true)
    public UserEntity getUserInfo(String userId) {
        return userRepository.findByUserId(userId)
                .orElseThrow(() -> new EntityNotFoundException("User not found: " + userId));
    }


    // 모든 유저 조회 메서드
    /**
     * 캐시 테스트용 메서드
     */
    public List<UserEntity> findAllUsers() {
        System.out.println("=== DB에서 모든 사용자 조회 ===");
        return userRepository.findAll();
    }

    /**
     * 사용자 정보 (전화번호, 주소) 및 비밀번호 업데이트 메서드
     */
    /**
     * 🔧 프로필 업데이트 - 캐시 갱신 적용
     */
    @Transactional
    @CachePut(value = "userCache", key = "#userId")
    public UserEntity updateProfile(String userId,
                                    String phone,
                                    String address,
                                    String currentPassword,
                                    String newPassword) {
        UserEntity user = getUserInfo(userId);
        if (user == null) {
            throw new RuntimeException("User not found: " + userId);
        }

        // 전화번호 업데이트
        if (phone != null && !phone.trim().isEmpty()) {
            user.setPhone(phone.trim());
        }
        // 주소 업데이트
        if (address != null && !address.trim().isEmpty()) {
            user.setAddress(address.trim());
        }

        // 비밀번호 업데이트 로직
        if (newPassword != null && !newPassword.trim().isEmpty()) {
            if (currentPassword == null || currentPassword.isEmpty() ||
                    !passwdEncoder.matches(currentPassword, user.getPasswd())) {
                throw new RuntimeException("현재 비밀번호가 일치하지 않습니다.");
            }

            String encodedNewPasswd = passwdEncoder.encode(newPassword);
            user.setPasswd(encodedNewPasswd);
            user.setPasswordChangeRequired(false);
            log.info("사용자 {} 비밀번호 변경 완료", userId);
        }

        return userRepository.save(user);
    }



    /**
     * 로그인 인증 메서드
     * 1) MySQL에 사용자가 있는지 확인
     * 2) 있으면 BCrypt 검사
     * 3) 없으면, 입력한 비밀번호가 userId와 동일하고 Oracle에 존재할 경우
     *    Oracle에서 유저 정보 가져와 MySQL에 저장 → 인증 성공
     */
    /**
     * 🔧 로그인 인증 - 성능 최적화
     */
    @Transactional
    public boolean authenticateUser(String userId, String password) {
        // 1) MySQL 조회 - 캐시 활용
        Optional<UserEntity> userOpt = userRepository.findByUserId(userId);
        UserEntity user = userOpt.orElse(null);
        if (user != null) {
            return passwdEncoder.matches(password, user.getPasswd());
        }

        // 2) Oracle 조회 및 마이그레이션 - 비동기 처리
        if (password.equals(userId) && oracleService.isUserExistsInOracle(userId)) {
            CompletableFuture.runAsync(() -> {
                try {
                    String encodedPasswordForMigration = passwdEncoder.encode(password);
                    oracleService.migrateUserFromOracle(userId, encodedPasswordForMigration);
                    log.info("[첫 로그인] Oracle 유저 '{}' MySQL 마이그레이션 완료", userId);
                } catch (Exception e) {
                    log.error("Oracle 유저 마이그레이션 실패: {}", userId, e);
                }
            });
            return true;
        }

        return false;
    }

    /**
     * 사용자 권한 조회 (캐시 적용)
     */
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
     */
    /**
     * 🔧 서명 업로드 - 트랜잭션 및 예외 처리 강화
     */
    @Transactional
    @CacheEvict(value = "userCache", key = "#userId")
    public void uploadSignature(String userId, MultipartFile file) throws IOException {
        Optional<UserEntity> userOpt = userRepository.findByUserId(userId);
        UserEntity user = userOpt.orElse(null);
        if (user == null) {
            throw new IllegalArgumentException("사용자 없음: " + userId);
        }

        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("업로드할 파일이 없습니다.");
        }

        try {
            // 파일 크기 제한 (5MB)
            if (file.getSize() > 5 * 1024 * 1024) {
                throw new IllegalArgumentException("파일 크기는 5MB를 초과할 수 없습니다.");
            }

            String original = Objects.requireNonNull(file.getOriginalFilename());
            String ext = original.substring(original.lastIndexOf('.')).toLowerCase();

            // 허용된 확장자 확인
            if (!Arrays.asList(".jpg", ".jpeg", ".png", ".gif").contains(ext)) {
                throw new IllegalArgumentException("지원하지 않는 파일 형식입니다.");
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
            }

            String filename = String.format("%s_sign_image%s", nameFiltered, ext);
            Path targetPath = userDir.resolve(filename);
            Files.copy(file.getInputStream(), targetPath, StandardCopyOption.REPLACE_EXISTING);

            // DB 업데이트
            user.setSignimage(file.getBytes());
            String encodedFolder = URLEncoder.encode(folderName, StandardCharsets.UTF_8);
            String encodedFilename = URLEncoder.encode(filename, StandardCharsets.UTF_8);
            user.setSignpath("/uploads/sign_image/" + encodedFolder + "/" + encodedFilename);

            userRepository.save(user);

        } catch (IOException e) {
            log.error("서명 파일 업로드 실패: userId={}", userId, e);
            throw new IOException("서명 파일 업로드 중 오류가 발생했습니다.", e);
        }
    }


    /**
     * 사용자의 사인 이미지를 Base64 데이터 URL 형식으로 반환
     * 프론트엔드에서 <img> 태그의 src에 직접 사용할 수 있는 형식
     */
    /**
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
            // JobLevel이 2 미만으로 내려가면 USER로 변경 (단, 별도 ADMIN 권한이 있을 수 있으므로 확인 필요)
            target.setRole(Role.USER);
        }

        userRepository.save(target);
        log.info("사용자 {}가 {}의 JobLevel을 {}에서 {}로 변경",
                adminUserId, targetUserId, oldJobLevel, newJobLevel);
    }

    /**
     * 부서별 직원 조회 (JobLevel 1인 사람용)
     */
    public List<UserEntity> getUsersByDeptCode(String adminUserId, String deptCode) {
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

        if (manager.getRole() == Role.ADMIN && managerLevel == 0 && "AD".equalsIgnoreCase(manager.getDeptCode().trim())) {
            return !managerUserId.equals(targetUserId);
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
}
