package sunhan.sunhanbackend.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sunhan.sunhanbackend.dto.response.SyncResult;
import sunhan.sunhanbackend.entity.mysql.UserEntity;
import sunhan.sunhanbackend.entity.oracle.OracleEntity;
import sunhan.sunhanbackend.repository.mysql.UserRepository;
import sunhan.sunhanbackend.repository.oracle.OracleRepository;
import sunhan.sunhanbackend.util.DateUtil;

import java.time.LocalDate;
import java.util.*;


@Service
@Slf4j
@RequiredArgsConstructor
public class UserSyncService {

    private final OracleRepository oracleRepository;
    private final UserRepository userRepository;
    private final OracleService oracleService;

    /**
     * 전체 사용자 데이터 동기화 (없으면 마이그레이션, 있으면 업데이트)
     */
    @Transactional
    public SyncResult syncAllUsers() {
        log.info("=== Oracle -> MySQL 전체 사용자 동기화 시작 ===");

        List<OracleEntity> oracleUsers = oracleRepository.findAll();
        int totalCount = oracleUsers.size();
        int successCount = 0;
        int errorCount = 0;
        List<String> errors = new ArrayList<>();

        for (OracleEntity oracleUser : oracleUsers) {
            try {
                String userId = oracleUser.getUsrId();

                if ("administrator".equalsIgnoreCase(userId)) {
                    log.debug("administrator는 동기화 대상에서 제외");
                    continue;
                }

                Optional<UserEntity> mysqlUserOpt = userRepository.findByUserId(userId);

                if (mysqlUserOpt.isPresent()) {
                    // ✅ 기존 사용자 업데이트
                    UserEntity mysqlUser = mysqlUserOpt.get();
                    boolean updated = syncUserData(mysqlUser, oracleUser);

                    if (updated) {
                        userRepository.save(mysqlUser);
                        successCount++;
                        log.info("사용자 정보 동기화 완료: {}", userId);
                    } else {
                        log.debug("변경사항 없음: {}", userId);
                    }
                } else {
                    // ✅ MySQL에 없으면 마이그레이션 (비밀번호는 userId와 동일하게 설정)
                    log.info("MySQL에 존재하지 않는 사용자 발견, 마이그레이션 시작: {}", userId);
                    try {
                        // 임시 비밀번호로 마이그레이션 (첫 로그인 시 변경 필요)
                        UserEntity newUser = oracleService.migrateUserFromOracle(userId,
                                org.springframework.security.crypto.bcrypt.BCrypt.hashpw(userId,
                                        org.springframework.security.crypto.bcrypt.BCrypt.gensalt()));

                        if (newUser != null) {
                            successCount++;
                            log.info("사용자 마이그레이션 완료: {} (StartDate: {})", userId, newUser.getStartDate());
                        }
                    } catch (Exception e) {
                        log.error("사용자 마이그레이션 실패: {}", userId, e);
                        errorCount++;
                        errors.add(String.format("마이그레이션 실패 [%s]: %s", userId, e.getMessage()));
                    }
                }

            } catch (Exception e) {
                errorCount++;
                String errorMsg = String.format("동기화 실패 [%s]: %s",
                        oracleUser.getUsrId(), e.getMessage());
                errors.add(errorMsg);
                log.error(errorMsg, e);
            }
        }

        log.info("=== 전체 동기화 완료 - 대상: {}, 성공: {}, 실패: {} ===",
                totalCount, successCount, errorCount);

        return new SyncResult(totalCount, successCount, errorCount, errors);
    }

    /**
     * 변경된 사용자만 동기화 (효율적)
     */
    @Transactional
    public SyncResult syncChangedUsers() {
        log.info("=== Oracle -> MySQL 변경된 사용자 동기화 시작 ===");

        List<OracleEntity> oracleUsers = oracleRepository.findAll();
        int totalCount = 0;
        int successCount = 0;
        int errorCount = 0;
        List<String> errors = new ArrayList<>();

        for (OracleEntity oracleUser : oracleUsers) {
            try {
                String userId = oracleUser.getUsrId();

                if ("administrator".equalsIgnoreCase(userId)) {
                    continue;
                }

                Optional<UserEntity> mysqlUserOpt = userRepository.findByUserId(userId);

                if (mysqlUserOpt.isPresent()) {
                    UserEntity mysqlUser = mysqlUserOpt.get();

                    if (hasChanges(mysqlUser, oracleUser)) {
                        totalCount++;
                        syncUserData(mysqlUser, oracleUser);
                        userRepository.save(mysqlUser);
                        successCount++;
                        log.info("변경된 사용자 정보 동기화: {}", userId);
                    }
                } else {
                    // ✅ 변경 동기화에서는 마이그레이션 안 함 (전체 동기화에서만)
                    log.debug("MySQL에 존재하지 않는 사용자 건너뜀: {}", userId);
                }

            } catch (Exception e) {
                errorCount++;
                String errorMsg = String.format("동기화 실패 [%s]: %s",
                        oracleUser.getUsrId(), e.getMessage());
                errors.add(errorMsg);
                log.error(errorMsg, e);
            }
        }

        log.info("=== 변경 동기화 완료 - 변경대상: {}, 성공: {}, 실패: {} ===",
                totalCount, successCount, errorCount);

        return new SyncResult(totalCount, successCount, errorCount, errors);
    }

    /**
     * 특정 사용자 동기화
     */
    @Transactional
    public boolean syncSingleUser(String userId) {
        log.info("개별 사용자 동기화 시작: {}", userId);

        if ("administrator".equalsIgnoreCase(userId)) {
            log.info("administrator는 동기화 대상이 아닙니다.");
            return false;
        }

        try {
            Optional<OracleEntity> oracleUserOpt = oracleRepository.findByUsrId(userId);

            if (oracleUserOpt.isEmpty()) {
                log.warn("Oracle에 사용자가 존재하지 않음: {}", userId);
                return false;
            }

            OracleEntity oracleUser = oracleUserOpt.get();
            Optional<UserEntity> mysqlUserOpt = userRepository.findByUserId(userId);

            if (mysqlUserOpt.isEmpty()) {
                // ✅ MySQL에 없으면 마이그레이션
                log.info("MySQL에 존재하지 않는 사용자, 마이그레이션 시작: {}", userId);
                UserEntity newUser = oracleService.migrateUserFromOracle(userId,
                        org.springframework.security.crypto.bcrypt.BCrypt.hashpw(userId,
                                org.springframework.security.crypto.bcrypt.BCrypt.gensalt()));
                return newUser != null;
            }

            // ✅ 있으면 업데이트
            UserEntity mysqlUser = mysqlUserOpt.get();
            boolean updated = syncUserData(mysqlUser, oracleUser);

            if (updated) {
                userRepository.save(mysqlUser);
                log.info("사용자 동기화 완료: {}", userId);
            } else {
                log.info("변경사항 없음: {}", userId);
            }

            return updated;

        } catch (Exception e) {
            log.error("개별 사용자 동기화 실패: {}", userId, e);
            throw new RuntimeException("동기화 실패: " + e.getMessage());
        }
    }

    /**
     * ✅ 변경사항 확인 로직 (날짜 변환 포함)
     */
    private boolean hasChanges(UserEntity mysqlUser, OracleEntity oracleUser) {
        LocalDate oracleStartDate = DateUtil.parseOracleDate(oracleUser.getStartDate());

        return !equals(mysqlUser.getUseFlag(), oracleUser.getUseFlag())
                || !equals(mysqlUser.getUserName(), oracleUser.getUsrKorName())
                || !equals(mysqlUser.getDeptCode(), oracleUser.getDeptCode())
                || !equals(mysqlUser.getJobType(), oracleUser.getJobType())
                || !equals(mysqlUser.getStartDate(), oracleStartDate);
    }

    /**
     * ✅ 사용자 데이터 동기화 (Oracle → MySQL, 날짜 변환 포함)
     */
    private boolean syncUserData(UserEntity mysqlUser, OracleEntity oracleUser) {
        boolean updated = false;

        if (!equals(mysqlUser.getUseFlag(), oracleUser.getUseFlag())) {
            log.debug("useFlag 변경: {} → {}", mysqlUser.getUseFlag(), oracleUser.getUseFlag());
            mysqlUser.setUseFlag(oracleUser.getUseFlag());
            updated = true;
        }

        if (!equals(mysqlUser.getUserName(), oracleUser.getUsrKorName())) {
            log.debug("userName 변경: {} → {}", mysqlUser.getUserName(), oracleUser.getUsrKorName());
            mysqlUser.setUserName(oracleUser.getUsrKorName());
            updated = true;
        }

        if (!equals(mysqlUser.getDeptCode(), oracleUser.getDeptCode())) {
            log.debug("deptCode 변경: {} → {}", mysqlUser.getDeptCode(), oracleUser.getDeptCode());
            mysqlUser.setDeptCode(oracleUser.getDeptCode());
            updated = true;
        }

        if (!equals(mysqlUser.getJobType(), oracleUser.getJobType())) {
            log.debug("jobType 변경: {} → {}", mysqlUser.getJobType(), oracleUser.getJobType());
            mysqlUser.setJobType(oracleUser.getJobType());
            updated = true;
        }

        LocalDate oracleStartDate = DateUtil.parseOracleDate(oracleUser.getStartDate());
        if (!equals(mysqlUser.getStartDate(), oracleStartDate)) {
            log.debug("startDate 변경: {} → {} (Oracle: {})",
                    mysqlUser.getStartDate(), oracleStartDate, oracleUser.getStartDate());
            mysqlUser.setStartDate(oracleStartDate);
            updated = true;
        }

        return updated;
    }

    private boolean equals(Object obj1, Object obj2) {
        if (obj1 == null && obj2 == null) return true;
        if (obj1 == null || obj2 == null) return false;
        return obj1.equals(obj2);
    }
}