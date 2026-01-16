package sunhan.sunhanbackend.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import sunhan.sunhanbackend.entity.mysql.UserAnnualVacationHistory;
import sunhan.sunhanbackend.entity.mysql.UserEntity;
import sunhan.sunhanbackend.repository.mysql.UserAnnualVacationHistoryRepository;
import sunhan.sunhanbackend.repository.mysql.UserRepository;

import java.time.LocalDate;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class VacationYearService {

    private final UserRepository userRepository;
    private final UserAnnualVacationHistoryRepository vacationHistoryRepository;

    /**
     * 매년 1월 1일 자정에 자동 초기화
     */
    @Scheduled(cron = "0 0 0 1 1 *")
    @Transactional
    public void initializeNewYearVacation() {
        int currentYear = LocalDate.now().getYear();
        int previousYear = currentYear - 1;

        log.info("=== {}년 연차 데이터 자동 초기화 시작 ===", currentYear);

        List<UserEntity> activeUsers = userRepository.findByUseFlag("1").stream()
                .filter(user -> !"000".equals(user.getDeptCode()))
                .toList();

        int successCount = 0;
        int skipCount = 0;

        for (UserEntity user : activeUsers) {
            try {
                boolean exists = vacationHistoryRepository
                        .findByUserIdAndYear(user.getUserId(), currentYear)
                        .isPresent();

                if (exists) {
                    skipCount++;
                    continue;
                }

                UserAnnualVacationHistory lastYear = vacationHistoryRepository
                        .findByUserIdAndYear(user.getUserId(), previousYear)
                        .orElse(null);

                Double carryoverDays = 0.0;
                if (lastYear != null) {
                    Double remaining = lastYear.getRemainingDays();
                    if (remaining != null && remaining > 0) {
                        carryoverDays = Math.min(remaining, 15.0);
                    }
                }

                UserAnnualVacationHistory newYear = UserAnnualVacationHistory.builder()
                        .userId(user.getUserId())
                        .year(currentYear)
                        .carryoverDays(carryoverDays)
                        .regularDays(15.0)
                        .usedCarryoverDays(0.0)
                        .usedRegularDays(0.0)
                        .build();

                vacationHistoryRepository.save(newYear);
                successCount++;

            } catch (Exception e) {
                log.error("사용자 {} 연차 초기화 실패", user.getUserId(), e);
            }
        }

        log.info("=== {}년 연차 데이터 초기화 완료 ===", currentYear);
        log.info("성공: {}명, 건너뜀: {}명", successCount, skipCount);
    }

    /**
     * 수동 초기화 (관리자용)
     */
    @Transactional
    public void initializeYearVacationManually(int year, boolean forceOverwrite) {
        log.info("=== {}년 연차 데이터 수동 초기화 시작 ===", year);

        int previousYear = year - 1;
        List<UserEntity> activeUsers = userRepository.findByUseFlag("1").stream()
                .filter(user -> !"000".equals(user.getDeptCode()))
                .toList();

        int successCount = 0;

        for (UserEntity user : activeUsers) {
            try {
                UserAnnualVacationHistory existing = vacationHistoryRepository
                        .findByUserIdAndYear(user.getUserId(), year)
                        .orElse(null);

                if (existing != null && !forceOverwrite) {
                    continue;
                }

                UserAnnualVacationHistory lastYear = vacationHistoryRepository
                        .findByUserIdAndYear(user.getUserId(), previousYear)
                        .orElse(null);

                Double carryoverDays = 0.0;
                if (lastYear != null) {
                    Double remaining = lastYear.getRemainingDays();
                    if (remaining != null && remaining > 0) {
                        carryoverDays = Math.min(remaining, 15.0);
                    }
                }

                if (existing != null) {
                    existing.setCarryoverDays(carryoverDays);
                    existing.setRegularDays(15.0);
                    existing.setUsedCarryoverDays(0.0);
                    existing.setUsedRegularDays(0.0);
                    vacationHistoryRepository.save(existing);
                } else {
                    UserAnnualVacationHistory newYear = UserAnnualVacationHistory.builder()
                            .userId(user.getUserId())
                            .year(year)
                            .carryoverDays(carryoverDays)
                            .regularDays(15.0)
                            .usedCarryoverDays(0.0)
                            .usedRegularDays(0.0)
                            .build();
                    vacationHistoryRepository.save(newYear);
                }

                successCount++;

            } catch (Exception e) {
                log.error("사용자 {} {}년 연차 초기화 실패", user.getUserId(), year, e);
            }
        }

        log.info("=== {}년 연차 데이터 초기화 완료: {}명 ===", year, successCount);
    }

    /**
     * 특정 사용자의 특정 연도 초기화
     * 💡 FIX: 상위 트랜잭션이 readOnly여도 이 메서드는 새로운 트랜잭션을 생성하여 쓰기 작업을 수행하도록 변경
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public UserAnnualVacationHistory initializeUserYearVacation(String userId, int year) {
        UserAnnualVacationHistory existing = vacationHistoryRepository
                .findByUserIdAndYear(userId, year)
                .orElse(null);

        if (existing != null) {
            return existing;
        }

        UserAnnualVacationHistory lastYear = vacationHistoryRepository
                .findByUserIdAndYear(userId, year - 1)
                .orElse(null);

        Double carryoverDays = 0.0;
        if (lastYear != null) {
            Double remaining = lastYear.getRemainingDays();
            if (remaining != null && remaining > 0) {
                carryoverDays = Math.min(remaining, 15.0);
            }
        }

        UserAnnualVacationHistory newYear = UserAnnualVacationHistory.builder()
                .userId(userId)
                .year(year)
                .carryoverDays(carryoverDays)
                .regularDays(15.0)
                .usedCarryoverDays(0.0)
                .usedRegularDays(0.0)
                .build();

        return vacationHistoryRepository.save(newYear);
    }
}