package sunhan.sunhanbackend.util;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import sunhan.sunhanbackend.dto.response.SyncResult;
import sunhan.sunhanbackend.service.UserSyncService;

import java.util.concurrent.atomic.AtomicBoolean;

@Component
@Slf4j
public class UserSyncScheduler {

    @Autowired
    private UserSyncService userSyncService;

    private final AtomicBoolean isRunning = new AtomicBoolean(false);

    /**
     * 매일 새벽 2시에 변경된 사용자 정보 자동 동기화
     * (useFlag, userName, deptCode, jobType, startDate)
     */
    @Scheduled(cron = "0 0 2 * * ?")
    public void scheduledUserSync() {
        if (isRunning.compareAndSet(false, true)) {
            try {
                log.info("=== 스케줄된 사용자 정보 동기화 시작 ===");

                SyncResult result = userSyncService.syncChangedUsers();

                log.info("=== 스케줄된 사용자 정보 동기화 완료 - 변경대상: {}, 업데이트: {}, 실패: {} ===",
                        result.getTotalCount(), result.getSuccessCount(), result.getErrorCount());

                if (result.getErrorCount() > 0) {
                    log.warn("사용자 정보 동기화 중 {}건의 오류가 발생했습니다.", result.getErrorCount());
                }

            } catch (Exception e) {
                log.error("스케줄된 사용자 정보 동기화 중 오류 발생", e);
            } finally {
                isRunning.set(false);
            }
        } else {
            log.warn("이미 사용자 정보 동기화가 실행 중입니다.");
        }
    }
}