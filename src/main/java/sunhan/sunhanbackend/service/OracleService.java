package sunhan.sunhanbackend.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sunhan.sunhanbackend.entity.oracle.OracleEntity;
import sunhan.sunhanbackend.entity.mysql.UserEntity;
import sunhan.sunhanbackend.enums.Role;
import sunhan.sunhanbackend.repository.mysql.UserRepository;
import sunhan.sunhanbackend.repository.oracle.OracleRepository;
import sunhan.sunhanbackend.util.DateUtil;

import java.time.LocalDate;
import java.util.Optional;

@Slf4j
@Service
@Transactional("oracleTransactionManager")
@RequiredArgsConstructor
public class OracleService {

    private final OracleRepository oracleRepository;
    private final UserRepository userRepository;

    /**
     * Oracle에서 사용자 정보를 조회하는 메서드
     */
    public OracleEntity getOracleUserInfo(String usrId) {
        Optional<OracleEntity> oracleUser = oracleRepository.findByUsrId(usrId);
        if (oracleUser.isPresent()) {
            return oracleUser.get();
        } else {
            throw new RuntimeException("Oracle에서 사용자 정보를 찾을 수 없습니다: " + usrId);
        }
    }

    /**
     * Oracle에서 사용자 정보를 가져와서 MySQL에 저장하는 메서드
     * ✅ MySQL 트랜잭션 (저장 대상이 MySQL이므로)
     */
    @Transactional // ✅ 기본 MySQL 트랜잭션 매니저 사용
    public UserEntity migrateUserFromOracle(String usrId, String password) {
        if ("administrator".equalsIgnoreCase(usrId)) {
            log.info("administrator 사용자는 Oracle에서 마이그레이션되지 않습니다.");
            return userRepository.findByUserId(usrId).orElse(null);
        }

        try {
            // ✅ 1. 먼저 MySQL에 이미 있는지 확인
            Optional<UserEntity> existingUserOpt = userRepository.findByUserId(usrId);
            if (existingUserOpt.isPresent()) {
                log.info("✅ 사용자가 이미 MySQL에 존재합니다: {}", usrId);
                return existingUserOpt.get();
            }

            // ✅ 2. Oracle 조회 (읽기 전용)
            OracleEntity oracleUser = getOracleUserInfo(usrId);

            if (!"1".equals(oracleUser.getUseFlag())) {
                log.warn("Oracle 사용자가 비활성 상태이므로 마이그레이션을 중단합니다. userId: {}", usrId);
                throw new RuntimeException("비활성 상태의 사용자입니다.");
            }

            // ✅ 3. 새 사용자 엔티티 생성
            UserEntity newUser = new UserEntity();
            newUser.setUserId(oracleUser.getUsrId());
            newUser.setUserName(oracleUser.getUsrKorName());
            newUser.setPasswd(password);
            newUser.setJobType(oracleUser.getJobType());
            newUser.setDeptCode(oracleUser.getDeptCode());
            newUser.setUseFlag(oracleUser.getUseFlag());

            LocalDate startDate = DateUtil.parseOracleDate(oracleUser.getStartDate());
            newUser.setStartDate(startDate);

            if (startDate != null) {
                log.info("입사일자 설정: {} (원본: {})", startDate, oracleUser.getStartDate());
            } else {
                log.warn("입사일자를 파싱할 수 없습니다: {}", oracleUser.getStartDate());
            }

            // JobLevel 설정
            if ("0".equals(oracleUser.getJobType())) {
                newUser.setJobLevel("0");
            } else if ("1".equals(oracleUser.getJobType())) {
                newUser.setJobLevel("3");
            } else {
                log.warn("알 수 없는 jobType 값: {} for user {}. jobLevel을 '0'으로 설정합니다.",
                        oracleUser.getJobType(), usrId);
                newUser.setJobLevel("0");
            }

            // Role 설정
            try {
                int jobLevelInt = Integer.parseInt(newUser.getJobLevel());
                if (jobLevelInt >= 2) {
                    newUser.setRole(Role.ADMIN);
                    log.info("JobLevel {}인 사용자 {}에게 ADMIN 권한 부여", jobLevelInt, usrId);
                } else {
                    newUser.setRole(Role.USER);
                    log.info("JobLevel {}인 사용자 {}에게 USER 권한 부여", jobLevelInt, usrId);
                }
            } catch (NumberFormatException e) {
                newUser.setRole(Role.USER);
                log.warn("JobLevel 파싱 실패, USER 권한으로 설정: {}", usrId);
            }

            // ✅ 4. 저장 전 마지막 중복 체크 (동시성 대비)
            if (userRepository.existsById(usrId)) {
                log.warn("⚠️ 저장 직전 중복 감지: {}", usrId);
                return userRepository.findByUserId(usrId).get();
            }

            UserEntity savedUser = userRepository.saveAndFlush(newUser); // ✅ flush 추가
            log.info("✅ Oracle에서 MySQL로 사용자 정보 이전 완료: {} (Role: {}, StartDate: {})",
                    usrId, savedUser.getRole(), savedUser.getStartDate());

            return savedUser;

        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            log.error("❌ 중복 키 오류 발생: {}. 기존 데이터 반환합니다.", usrId);
            return userRepository.findByUserId(usrId)
                    .orElseThrow(() -> new RuntimeException("사용자 저장 실패: " + usrId));
        } catch (Exception e) {
            log.error("❌ Oracle에서 MySQL로 사용자 정보 이전 실패: {}", usrId, e);
            throw new RuntimeException("사용자 정보 이전에 실패했습니다: " + e.getMessage());
        }
    }

    /**
     * Oracle에 사용자가 존재하는지 확인하는 메서드
     */
    public boolean isUserExistsInOracle(String usrId) {
        return oracleRepository.findByUsrId(usrId).isPresent();
    }
}