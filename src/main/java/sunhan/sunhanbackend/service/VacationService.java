package sunhan.sunhanbackend.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sunhan.sunhanbackend.dto.response.VacationHistoryResponseDto;
import sunhan.sunhanbackend.dto.response.VacationStatusResponseDto;
import sunhan.sunhanbackend.entity.mysql.LeaveApplication;
import sunhan.sunhanbackend.entity.mysql.UserEntity;
import sunhan.sunhanbackend.enums.LeaveApplicationStatus;
import sunhan.sunhanbackend.repository.mysql.LeaveApplicationRepository;
import sunhan.sunhanbackend.repository.mysql.UserRepository;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class VacationService {

    private final UserRepository userRepository;
    private final LeaveApplicationRepository leaveApplicationRepository;
    private final UserService userService;

    /**
     * 사용자의 휴가 현황 조회
     */
    @Transactional(readOnly = true)
    public VacationStatusResponseDto getVacationStatus(String userId) {
        UserEntity user = userService.getUserInfo(userId);
        if (user == null) {
            throw new RuntimeException("사용자를 찾을 수 없습니다: " + userId);
        }

        // 승인된 휴가 신청의 총 일수 계산
        Double usedDays = leaveApplicationRepository
                .findByApplicantIdAndStatus(userId, LeaveApplicationStatus.APPROVED)
                .stream()
                .mapToDouble(leave -> leave.getTotalDays() != null ? leave.getTotalDays() : 0.0)
                .sum();

        Integer totalDays = user.getTotalVacationDays() != null ? user.getTotalVacationDays() : 15;
        Integer usedVacationDays = usedDays.intValue();
        Integer remainingDays = totalDays - usedVacationDays;

        return VacationStatusResponseDto.builder()
                .userId(userId)
                .userName(user.getUserName())
                .totalVacationDays(totalDays)
                .usedVacationDays(usedVacationDays)
                .remainingVacationDays(remainingDays)
                .build();
    }

    /**
     * 사용자의 휴가 사용 내역 조회
     */
    @Transactional(readOnly = true)
    public List<VacationHistoryResponseDto> getVacationHistory(String userId) {
        // 🚀 Use the optimized JOIN FETCH query to get applications and applicants at once.
        List<LeaveApplication> approvedApplications = leaveApplicationRepository
                .findByApplicantIdAndStatusWithApplicant(userId, LeaveApplicationStatus.APPROVED);

        return approvedApplications.stream()
                .map(this::convertToHistoryDto) // No extra queries will be triggered here.
                .collect(Collectors.toList());
    }
    /**
     * 총 휴가일수 설정 (관리자만)
     */
    @Transactional
    public void setTotalVacationDays(String adminUserId, String targetUserId, Integer totalDays) {
        // 권한 검증
        if (!userService.canManageUser(adminUserId, targetUserId)) {
            throw new RuntimeException("해당 사용자의 휴가일수를 설정할 권한이 없습니다.");
        }

        UserEntity targetUser = userRepository.findByUserId(targetUserId)
                .orElseThrow(() -> new RuntimeException("대상 사용자를 찾을 수 없습니다: " + targetUserId));

        targetUser.setTotalVacationDays(totalDays);

        // 이 시점에 동시성 충돌 발생 가능
        try {
            userRepository.save(targetUser);
        } catch (ObjectOptimisticLockingFailureException e) {
            // 명확한 메시지를 전달하기 위해 예외를 다시 던짐
            throw new RuntimeException("다른 사용자가 해당 정보를 수정했습니다. 다시 시도해주세요.", e);
        }

        log.info("관리자 {}가 사용자 {}의 총 휴가일수를 {}일로 설정",
                adminUserId, targetUserId, totalDays);
    }

    /**
     * 사용자의 휴가 정보 조회 권한 확인
     */
    public boolean canViewUserVacation(String currentUserId, String targetUserId) {
        // 본인의 휴가 정보는 항상 조회 가능
        if (currentUserId.equals(targetUserId)) {
            return true;
        }

        // 관리 권한이 있는 경우 조회 가능
        return userService.canManageUser(currentUserId, targetUserId);
    }

    /**
     * LeaveApplication을 VacationHistoryResponseDto로 변환
     */
    private VacationHistoryResponseDto convertToHistoryDto(LeaveApplication application) {
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd");

        return VacationHistoryResponseDto.builder()
                .id(application.getId())
                .startDate(application.getStartDate().format(formatter))
                .endDate(application.getEndDate().format(formatter))
                .days(application.getTotalDays() != null ? application.getTotalDays().intValue() : 0)
                .reason(getLeaveTypeKorean(application.getLeaveType().toString()))
                .status(application.getStatus().toString())
                .createdDate(application.getCreatedAt().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")))
                .build();
    }

    /**
     * 휴가 종류를 한국어로 변환
     */
    private String getLeaveTypeKorean(String leaveType) {
        switch (leaveType) {
            case "ANNUAL_LEAVE": return "연차휴가";
            case "SICK_LEAVE": return "병가";
            case "FAMILY_CARE_LEAVE": return "가족돌봄휴가";
            case "MATERNITY_LEAVE": return "출산휴가";
            case "PATERNITY_LEAVE": return "배우자출산휴가";
            case "SPECIAL_LEAVE": return "특별휴가";
            case "BEREAVEMENT_LEAVE": return "경조휴가";
            default: return leaveType;
        }
    }
}