package sunhan.sunhanbackend.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sunhan.sunhanbackend.entity.mysql.DeptPermissionEntity;
import sunhan.sunhanbackend.entity.mysql.UserEntity;
import sunhan.sunhanbackend.entity.mysql.UserPermissionEntity;
import sunhan.sunhanbackend.enums.PermissionType;
import sunhan.sunhanbackend.enums.Role;
import sunhan.sunhanbackend.repository.mysql.DeptPermissionRepository;
import sunhan.sunhanbackend.repository.mysql.UserPermissionRepository;
import sunhan.sunhanbackend.repository.mysql.UserRepository;

import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class PermissionService {

    private final UserRepository userRepository;
    private final UserPermissionRepository userPermissionRepository;
    private final DeptPermissionRepository deptPermissionRepository;

    /**
     * 사용자가 특정 권한을 가지고 있는지 확인 (개인 권한 OR 부서 권한)
     */
    public boolean hasPermission(String userId, PermissionType permissionType) {
        // 1. 사용자가 존재하는지 확인
        Optional<UserEntity> userOpt = userRepository.findByUserId(userId);
        if (!userOpt.isPresent()) {
            return false;
        }

        UserEntity user = userOpt.get();

        // 2. ADMIN 권한이 없으면 인사 권한 불가
        if (user.getRole() != Role.ADMIN) {
            return false;
        }

        // 3. 개인 권한 확인
        if (userPermissionRepository.existsByUserIdAndPermissionType(userId, permissionType)) {
            log.debug("사용자 {}가 개인 권한 {}를 보유", userId, permissionType);
            return true;
        }

        // 4. 부서 권한 확인
        String deptCode = user.getDeptCode();
        if (deptCode != null && deptPermissionRepository.existsByDeptCodeAndPermissionType(deptCode, permissionType)) {
            log.debug("사용자 {}가 부서 권한 {}를 보유 (부서: {})", userId, permissionType, deptCode);
            return true;
        }

        return false;
    }

    /**
     * 사용자에게 인사 관련 권한이 있는지 확인 (모든 인사 권한 중 하나라도)
     */
    public boolean hasAnyHrPermission(String userId) {
        return hasPermission(userId, PermissionType.HR_LEAVE_APPLICATION) ||
                hasPermission(userId, PermissionType.HR_CONTRACT);
    }

    /**
     * 부서에 권한 부여
     */
    @Transactional
    public void grantDeptPermission(String adminUserId, String deptCode, PermissionType permissionType) {
        // 관리자 권한 확인
        validateAdminPermission(adminUserId);

        // 이미 존재하는지 확인
        if (deptPermissionRepository.existsByDeptCodeAndPermissionType(deptCode, permissionType)) {
            log.info("부서 {}에 이미 {} 권한이 존재합니다", deptCode, permissionType);
            return;
        }

        DeptPermissionEntity permission = new DeptPermissionEntity(deptCode, permissionType);
        deptPermissionRepository.save(permission);

        log.info("관리자 {}가 부서 {}에 {} 권한 부여", adminUserId, deptCode, permissionType);
    }

    /**
     * 부서에서 권한 제거
     */
    @Transactional
    public void revokeDeptPermission(String adminUserId, String deptCode, PermissionType permissionType) {
        // 관리자 권한 확인
        validateAdminPermission(adminUserId);

        deptPermissionRepository.deleteByDeptCodeAndPermissionType(deptCode, permissionType);

        log.info("관리자 {}가 부서 {}에서 {} 권한 제거", adminUserId, deptCode, permissionType);
    }

    /**
     * 개인에게 권한 부여
     */
    @Transactional
    public void grantUserPermission(String adminUserId, String targetUserId, PermissionType permissionType) {
        // 관리자 권한 확인
        validateAdminPermission(adminUserId);

        // 대상 사용자 존재 확인
        UserEntity targetUser = userRepository.findByUserId(targetUserId)
                .orElseThrow(() -> new RuntimeException("대상 사용자를 찾을 수 없습니다: " + targetUserId));

        // 대상 사용자가 ADMIN이어야 함
        if (targetUser.getRole() != Role.ADMIN) {
            throw new RuntimeException("ADMIN 권한이 있는 사용자에게만 인사 권한을 부여할 수 있습니다.");
        }

        // 이미 존재하는지 확인
        if (userPermissionRepository.existsByUserIdAndPermissionType(targetUserId, permissionType)) {
            log.info("사용자 {}에게 이미 {} 권한이 존재합니다", targetUserId, permissionType);
            return;
        }

        UserPermissionEntity permission = new UserPermissionEntity(targetUserId, permissionType);
        userPermissionRepository.save(permission);

        log.info("관리자 {}가 사용자 {}에게 {} 권한 부여", adminUserId, targetUserId, permissionType);
    }

    /**
     * 개인에서 권한 제거
     */
    @Transactional
    public void revokeUserPermission(String adminUserId, String targetUserId, PermissionType permissionType) {
        // 관리자 권한 확인
        validateAdminPermission(adminUserId);

        userPermissionRepository.deleteByUserIdAndPermissionType(targetUserId, permissionType);

        log.info("관리자 {}가 사용자 {}에서 {} 권한 제거", adminUserId, targetUserId, permissionType);
    }

    /**
     * 부서의 모든 권한 조회
     */
    public List<DeptPermissionEntity> getDeptPermissions(String deptCode) {
        return deptPermissionRepository.findByDeptCode(deptCode);
    }

    /**
     * 사용자의 개인 권한 조회
     */
    public List<UserPermissionEntity> getUserPermissions(String userId) {
        return userPermissionRepository.findByUserId(userId);
    }

    /**
     * 특정 권한을 가진 모든 부서 조회
     */
    public List<String> getDeptCodesWithPermission(PermissionType permissionType) {
        return deptPermissionRepository.findDeptCodesByPermissionType(permissionType);
    }

    /**
     * 특정 권한을 가진 모든 사용자 조회
     */
    public List<String> getUserIdsWithPermission(PermissionType permissionType) {
        return userPermissionRepository.findUserIdsByPermissionType(permissionType);
    }

    /**
     * 관리자 권한 검증
     */
// PermissionService 클래스 내
    private void validateAdminPermission(String userId) {
        UserEntity user = userRepository.findByUserId(userId)
                .orElseThrow(() -> new RuntimeException("사용자를 찾을 수 없습니다: " + userId));

        if (user.getRole() != Role.ADMIN) {
            throw new RuntimeException("관리자 권한이 필요합니다.");
        }

        // 안전하게 jobLevel 파싱 (파싱 실패 시 -1)
        int adminLevel = -1;
        try {
            if (user.getJobLevel() != null) {
                adminLevel = Integer.parseInt(user.getJobLevel());
            }
        } catch (NumberFormatException ignored) {}

        // 상위 관리자(jobLevel >= 2)면 허용
        if (adminLevel >= 2) {
            return;
        }

        // 또는 MANAGE_USERS 권한(개인 또는 소속 부서)이 있으면 허용
        boolean hasManageUsersPersonal = userPermissionRepository.existsByUserIdAndPermissionType(userId, PermissionType.MANAGE_USERS);
        boolean hasManageUsersDept = user.getDeptCode() != null
                && deptPermissionRepository.existsByDeptCodeAndPermissionType(user.getDeptCode(), PermissionType.MANAGE_USERS);

        if (hasManageUsersPersonal || hasManageUsersDept) {
            return;
        }

        throw new RuntimeException("권한 관리는 상위 관리자 또는 MANAGE_USERS 권한이 있는 사용자만 가능합니다.");
    }
}