package sunhan.sunhanbackend.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import sunhan.sunhanbackend.entity.mysql.UserEntity;
import sunhan.sunhanbackend.enums.Role;
import sunhan.sunhanbackend.repository.mysql.UserRepository;

import java.time.LocalDate;
import java.util.Optional;

/**
 * 애플리케이션 시작 시 슈퍼 어드민 계정 자동 생성
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SuperAdminInitializer implements ApplicationRunner {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Value("${super-admin-id}")
    private String SUPER_ADMIN_ID;

    @Value("${super-admin-password}")
    private String SUPER_ADMIN_PASSWORD;

    private static final String SUPER_ADMIN_NAME = "관리자";

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        try {
            log.info("🔐 SuperAdmin 초기화 시작...");
            Optional<UserEntity> existingUser = userRepository.findByUserId(SUPER_ADMIN_ID);

            if (existingUser.isEmpty()) {
                createNewSuperAdmin();
            } else {
                promoteToSuperAdmin(existingUser.get());
            }

            // ✅ 초기화 완료 확인
            Optional<UserEntity> verify = userRepository.findByUserId(SUPER_ADMIN_ID);
            if (verify.isPresent()) {
                log.info("✅ SuperAdmin 초기화 완료 확인: ID={}, Role={}",
                        verify.get().getUserId(), verify.get().getRole());
            } else {
                log.error("❌ SuperAdmin 초기화 실패: DB에서 조회 불가");
            }

        } catch (Exception e) {
            log.error("❌ 슈퍼 어드민 초기화 중 오류 발생", e);
            // ✅ 초기화 실패 시 애플리케이션 종료 (선택사항)
            // System.exit(1);
        }
    }

    protected void createNewSuperAdmin() {
        try {
            UserEntity superAdmin = new UserEntity();
            superAdmin.setUserId(SUPER_ADMIN_ID);
            superAdmin.setUserName(SUPER_ADMIN_NAME);
            superAdmin.setPasswd(passwordEncoder.encode(SUPER_ADMIN_PASSWORD));
            superAdmin.setJobType("9");
            superAdmin.setJobLevel("6");
            superAdmin.setDeptCode("000");
            superAdmin.setPhone("01000000000");
            superAdmin.setAddress("시스템");
            superAdmin.setUseFlag("1");
            superAdmin.setRole(Role.ADMIN);
            superAdmin.setPasswordChangeRequired(false);
            superAdmin.setStartDate(LocalDate.now());
            superAdmin.setPrivacyConsent(true);
            superAdmin.setNotificationConsent(true);

            userRepository.saveAndFlush(superAdmin); // ✅ flush 추가
            log.info("✅ 슈퍼 어드민 계정 생성 완료: {}", SUPER_ADMIN_ID);

        } catch (Exception e) {
            log.error("❌ SuperAdmin 생성 실패", e);
            throw e;
        }
    }

    protected void promoteToSuperAdmin(UserEntity existingUser) {
        boolean needsUpdate = false;

        if (!SUPER_ADMIN_NAME.equals(existingUser.getUserName())) {
            existingUser.setUserName(SUPER_ADMIN_NAME);
            needsUpdate = true;
        }

        if (!"9".equals(existingUser.getJobType())) {
            existingUser.setJobType("9");
            needsUpdate = true;
        }

        if (!"6".equals(existingUser.getJobLevel())) {
            existingUser.setJobLevel("6");
            needsUpdate = true;
        }

        if (!"000".equals(existingUser.getDeptCode())) {
            existingUser.setDeptCode("000");
            needsUpdate = true;
        }

        if (existingUser.getRole() != Role.ADMIN) {
            existingUser.setRole(Role.ADMIN);
            needsUpdate = true;
        }

        if (!"1".equals(existingUser.getUseFlag())) {
            existingUser.setUseFlag("1");
            needsUpdate = true;
        }

        if (Boolean.TRUE.equals(existingUser.getPasswordChangeRequired())) {
            existingUser.setPasswordChangeRequired(false);
            needsUpdate = true;
        }

        if (existingUser.getStartDate() == null) {
            existingUser.setStartDate(LocalDate.now());
            needsUpdate = true;
        }

        // ✅ 비밀번호 체크
        if (!passwordEncoder.matches(SUPER_ADMIN_PASSWORD, existingUser.getPasswd())) {
            existingUser.setPasswd(passwordEncoder.encode(SUPER_ADMIN_PASSWORD));
            needsUpdate = true;
            log.info("🔑 비밀번호 변경 필요 - 업데이트 예정");
        }

        if (existingUser.getPrivacyConsent() == null) {
            existingUser.setPrivacyConsent(true);
            needsUpdate = true;
        }

        if (existingUser.getNotificationConsent() == null) {
            existingUser.setNotificationConsent(true);
            needsUpdate = true;
        }

        if (needsUpdate) {
            userRepository.saveAndFlush(existingUser); // ✅ flush 추가
            log.info("✅ 슈퍼 어드민 정보 업데이트 완료: {}", existingUser.getUserId());
        } else {
            log.info("✅ 슈퍼 어드민 정보 확인 완료 (변경사항 없음)");
        }
    }
}