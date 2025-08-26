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
     */
    public UserEntity migrateUserFromOracle(String usrId, String password) {
        try {
            // 1. Oracle에서 사용자 정보 조회
            OracleEntity oracleUser = getOracleUserInfo(usrId);

            // ⭐ 추가된 로직: Oracle 사용자의 useFlag가 '1'(활성 상태)인지 확인
            if (!"1".equals(oracleUser.getUseFlag())) {
                log.warn("Oracle 사용자가 비활성 상태(useFlag != '1')이므로 마이그레이션을 중단합니다. userId: {}", usrId);
                throw new RuntimeException("비활성 상태의 사용자입니다.");
            }

            // 2. 이미 MySQL에 존재하는지 확인
            Optional<UserEntity> existingUserOpt = userRepository.findByUserId(usrId);
            if (existingUserOpt.isPresent()) {
                log.info("사용자가 이미 MySQL에 존재합니다: {}", usrId);
                return existingUserOpt.get();
            }

            // 3. Oracle 데이터를 MySQL 형태로 변환
            UserEntity newUser = new UserEntity();
            newUser.setUserId(oracleUser.getUsrId());
            newUser.setUserName(oracleUser.getUsrKorName());
            newUser.setPasswd(password); // 사용자가 입력한 비밀번호
            newUser.setJobType(oracleUser.getJobType());
            newUser.setDeptCode(oracleUser.getDeptCode());
            newUser.setUseFlag(oracleUser.getUseFlag());
            newUser.setUseFlag("1"); // 기본값으로 활성 상태 설정

            // jobType 값에 따라 jobLevel 설정
            if ("0".equals(oracleUser.getJobType())) {
                newUser.setJobLevel("0"); // jobType이 "0"이면 jobLevel "0"
            } else if ("1".equals(oracleUser.getJobType())) {
                newUser.setJobLevel("3"); // jobType이 "1"이면 jobLevel "3"
            } else {
                // 그 외의 jobType 값에 대한 기본 jobLevel 설정
                // 비즈니스 로직에 따라 다른 기본값을 주거나, 오류를 발생시킬 수 있습니다.
                log.warn("알 수 없는 jobType 값: {} for user {}. jobLevel을 '0'으로 설정합니다.", oracleUser.getJobType(), usrId);
                newUser.setJobLevel("0");
            }

            // ⭐ 추가: JobLevel에 따라 Role 자동 설정
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
                newUser.setRole(Role.USER); // 기본값
                log.warn("JobLevel 파싱 실패, USER 권한으로 설정: {}", usrId);
            }

            // 4. MySQL에 저장
            UserEntity savedUser = userRepository.save(newUser);
            log.info("Oracle에서 MySQL로 사용자 정보 이전 완료: {} (Role: {})", usrId, savedUser.getRole());

            return savedUser;

        } catch (Exception e) {
            log.error("Oracle에서 MySQL로 사용자 정보 이전 실패: {}", usrId, e);
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