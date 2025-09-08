package sunhan.sunhanbackend.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sunhan.sunhanbackend.dto.response.SyncResult;
import sunhan.sunhanbackend.entity.mysql.UserEntity;
import sunhan.sunhanbackend.entity.oracle.OracleEntity;
import sunhan.sunhanbackend.repository.mysql.UserRepository;
import sunhan.sunhanbackend.repository.oracle.OracleRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
@Slf4j
@Transactional
public class UseFlagSyncService {

    @Autowired
    private OracleRepository oracleUserRepository;

    @Autowired
    private UserRepository mysqlUserRepository;

    // 전체 useFlag 동기화
    public SyncResult syncAllUseFlags() {
        log.info("전체 useFlag 동기화 시작");

        // 1. Oracle 사용자 전체 조회
        List<OracleEntity> oracleUsers = oracleUserRepository.findAll();
        List<String> oracleUserIds = oracleUsers.stream()
                .map(OracleEntity::getUsrId)
                .toList();

        // 2. MySQL에서 Oracle에 있는 ID만 조회
        List<UserEntity> mysqlUsers = mysqlUserRepository.findByUserIdIn(oracleUserIds);
        Map<String, UserEntity> mysqlUserMap = mysqlUsers.stream()
                .collect(Collectors.toMap(UserEntity::getUserId, u -> u));

        int totalCount = 0;
        int updatedCount = 0;
        int errorCount = 0;
        List<String> errors = new ArrayList<>();

        // 3. Oracle → MySQL 매핑하며 동기화
        for (OracleEntity oracleUser : oracleUsers) {
            String usrId = oracleUser.getUsrId();
            String oracleUseFlag = oracleUser.getUseFlag();
            UserEntity mysqlUser = mysqlUserMap.get(usrId);

            // MySQL에 없는 경우 건너뜀
            if (mysqlUser == null) {
                log.debug("MySQL에 없는 사용자 건너뜀: {}", usrId);
                continue;
            }

            try {
                if (!Objects.equals(mysqlUser.getUseFlag(), oracleUseFlag)) {
                    String oldUseFlag = mysqlUser.getUseFlag();
                    mysqlUser.setUseFlag(oracleUseFlag);
                    mysqlUserRepository.save(mysqlUser);
                    updatedCount++;
                    log.info("useFlag 동기화: {} ({} -> {})", usrId, oldUseFlag, oracleUseFlag);
                }
                totalCount++;
            } catch (Exception e) {
                errorCount++;
                errors.add("User " + usrId + ": " + e.getMessage());
                log.error("useFlag 동기화 실패: {}", usrId, e);
            }
        }

        log.info("전체 useFlag 동기화 완료 - 전체: {}, 업데이트: {}, 실패: {}",
                totalCount, updatedCount, errorCount);

        return new SyncResult(totalCount, updatedCount, errorCount, errors);
    }

    // 변경된 useFlag만 동기화 (Oracle과 MySQL 비교)
    public SyncResult syncChangedUseFlags() {
        log.info("변경된 useFlag 동기화 시작");

        // 1. Oracle 사용자 전체 조회
        List<OracleEntity> oracleUsers = oracleUserRepository.findAll();
        List<String> oracleUserIds = oracleUsers.stream()
                .map(OracleEntity::getUsrId)
                .toList();

        // 2. MySQL에서 Oracle에 있는 ID들만 조회 (N+1 방지)
        List<UserEntity> mysqlUsers = mysqlUserRepository.findByUserIdIn(oracleUserIds);
        Map<String, UserEntity> mysqlUserMap = mysqlUsers.stream()
                .collect(Collectors.toMap(UserEntity::getUserId, u -> u));

        int totalCount = 0;  // 변경된 대상 수
        int updatedCount = 0;
        int errorCount = 0;
        List<String> errors = new ArrayList<>();

        for (OracleEntity oracleUser : oracleUsers) {
            String usrId = oracleUser.getUsrId();
            String oracleUseFlag = oracleUser.getUseFlag();
            UserEntity mysqlUser = mysqlUserMap.get(usrId);

            if (mysqlUser == null) {
                log.debug("MySQL에 없는 사용자 건너뜀: {}", usrId);
                continue;
            }

            String mysqlUseFlag = mysqlUser.getUseFlag();

            // useFlag 다른 경우만 처리
            if (!Objects.equals(oracleUseFlag, mysqlUseFlag)) {
                totalCount++;  // ✅ 이제 변경된 사람만 카운트
                try {
                    mysqlUser.setUseFlag(oracleUseFlag);
                    mysqlUserRepository.save(mysqlUser);
                    updatedCount++;
                    log.info("useFlag 변경 감지 및 동기화: {} ({} -> {})",
                            usrId, mysqlUseFlag, oracleUseFlag);
                } catch (Exception e) {
                    errorCount++;
                    errors.add("User " + usrId + ": " + e.getMessage());
                    log.error("useFlag 동기화 실패: {}", usrId, e);
                }
            }
        }

        log.info("변경된 useFlag 동기화 완료 - 변경대상: {}, 업데이트: {}, 실패: {}",
                totalCount, updatedCount, errorCount);

        return new SyncResult(totalCount, updatedCount, errorCount, errors);
    }

    // 개별 사용자 useFlag 동기화
    public boolean syncSingleUserUseFlag(String userId) {
        OracleEntity oracleUser = oracleUserRepository.findByUsrId(userId)
                .orElseThrow(() -> new RuntimeException("Oracle에서 사용자를 찾을 수 없습니다: " + userId));

        return syncSingleUserUseFlag(userId, oracleUser.getUseFlag());
    }

    private boolean syncSingleUserUseFlag(String userId, String oracleUseFlag) {
        UserEntity mysqlUser = mysqlUserRepository.findByUserId(userId).orElse(null);

        if (mysqlUser == null) {
            log.warn("MySQL에서 사용자를 찾을 수 없습니다: {}", userId);
            return false;
        }

        if (Objects.equals(mysqlUser.getUseFlag(), oracleUseFlag)) {
            return false; // 변경 없음
        }

        String oldUseFlag = mysqlUser.getUseFlag();
        mysqlUser.setUseFlag(oracleUseFlag);
        mysqlUserRepository.save(mysqlUser);

        log.debug("useFlag 업데이트 완료: {} ({} -> {})", userId, oldUseFlag, oracleUseFlag);
        return true; // 변경됨
    }
}
