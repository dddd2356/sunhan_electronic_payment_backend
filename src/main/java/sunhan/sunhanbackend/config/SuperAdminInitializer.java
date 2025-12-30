package sunhan.sunhanbackend.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import sunhan.sunhanbackend.entity.mysql.UserEntity;
import sunhan.sunhanbackend.enums.Role;
import sunhan.sunhanbackend.repository.mysql.UserRepository;

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

    // 슈퍼 어드민 기본 설정
    @Value("${super-admin-id}")
    private String SUPER_ADMIN_ID;
    @Value("${super-admin-password}")
    private String SUPER_ADMIN_PASSWORD;
    private static final String SUPER_ADMIN_NAME = "관리자";

    @Override
    public void run(ApplicationArguments args) {
        try {
            Optional<UserEntity> existingUser = userRepository.findByUserId(SUPER_ADMIN_ID);

            if (existingUser.isEmpty()) { // Optional.isEmpty()를 사용하여 사용자가 없는지 확인
                createNewSuperAdmin();
            } else {
                // Optional.get()으로 UserEntity를 가져와서 사용
                promoteToSuperAdmin(existingUser.get());
            }
        } catch (Exception e) {
            log.error("슈퍼 어드민 초기화 중 오류 발생", e);
        }
    }

    private void createNewSuperAdmin() {
        UserEntity superAdmin = new UserEntity();
        superAdmin.setUserId(SUPER_ADMIN_ID);
        superAdmin.setUserName(SUPER_ADMIN_NAME);
        superAdmin.setPasswd(passwordEncoder.encode(SUPER_ADMIN_PASSWORD));
        superAdmin.setJobType("9");
        superAdmin.setJobLevel("6"); // 슈퍼 어드민 레벨
        superAdmin.setDeptCode("000"); // 시스템 부서
        superAdmin.setPhone("01000000000");
        superAdmin.setAddress("시스템");
        superAdmin.setUseFlag("1"); // 사용 가능
        superAdmin.setRole(Role.ADMIN);
        superAdmin.setPasswordChangeRequired(false); // 첫 로그인 후 비밀번호 변경 권장

        userRepository.save(superAdmin);
    }

    private void promoteToSuperAdmin(UserEntity existingUser) {
        existingUser.setUserName(SUPER_ADMIN_NAME);
        existingUser.setPasswd(passwordEncoder.encode(SUPER_ADMIN_PASSWORD));
        existingUser.setJobType("9");
        existingUser.setJobLevel("6");
        existingUser.setDeptCode("000");
        existingUser.setRole(Role.ADMIN);
        existingUser.setUseFlag("1");
        existingUser.setPasswordChangeRequired(false);

        userRepository.save(existingUser);
    }
}