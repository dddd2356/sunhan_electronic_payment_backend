package sunhan.sunhanbackend.service;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import sunhan.sunhanbackend.entity.mysql.DeptPermissionEntity;
import sunhan.sunhanbackend.entity.mysql.UserEntity;
import sunhan.sunhanbackend.entity.mysql.UserPermissionEntity;
import sunhan.sunhanbackend.enums.PermissionType;
import sunhan.sunhanbackend.enums.Role;
import sunhan.sunhanbackend.repository.mysql.DeptPermissionRepository;
import sunhan.sunhanbackend.repository.mysql.UserPermissionRepository;
import sunhan.sunhanbackend.repository.mysql.UserRepository;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class PermissionService {

    private final UserRepository userRepository;
    private final UserPermissionRepository userPermissionRepository;
    private final DeptPermissionRepository deptPermissionRepository;
    private final ApplicationContext applicationContext;
    // 🆕 요청 범위 내에서 권한 정보를 캐싱하는 맵
    private static final String PERMISSION_CACHE_KEY = "PERMISSION_CACHE";

    /**
     * 요청 범위 내에서 권한을 캐싱하여 중복 조회 방지
     */
    public Set<PermissionType> getAllUserPermissions(String userId) {
        log.info("캐시에서 권한 조회: {}", userId);
        // 현재 요청에서 이미 조회한 권한이 있으면 재사용
        Map<String, Set<PermissionType>> requestCache = getRequestPermissionCache();
        if (requestCache.containsKey(userId)) {
            log.debug("권한 캐시 히트: {}", userId);
            return requestCache.get(userId);
        }

        // 캐시에 없으면 조회 후 캐시에 저장
        // 프록시를 통해 캐시 적용된 메서드 호출
        Set<PermissionType> permissions;
        try {
            PermissionService proxy = applicationContext.getBean(PermissionService.class);
            permissions = proxy.getAllUserPermissionsFromDB(userId);
        } catch (Exception ex) {
            // 예외 시 안전하게 직접 호출 (테스트 등)
            log.warn("프록시를 통한 호출 실패, 직접 호출로 대체합니다: {}", ex.getMessage());
            permissions = getAllUserPermissionsFromDB(userId);
        }
        requestCache.put(userId, permissions);
        log.debug("권한 DB 조회 후 캐시 저장: {}", userId);

        return permissions;
    }

    /**
     * Spring Cache를 이용한 장기 캐싱 (DB에서 실제 조회)
     */
    @Cacheable(value = "userAllPermissionsCache", key = "#userId")
    public Set<PermissionType> getAllUserPermissionsFromDB(String userId) {
        log.info("DB 권한 조회 실행: {}", userId);
        Set<PermissionType> permissions = new HashSet<>();

        Optional<UserEntity> userOpt = userRepository.findByUserId(userId);
        if (!userOpt.isPresent() || userOpt.get().getRole() != Role.ADMIN) {
            return permissions;
        }

        UserEntity user = userOpt.get();

        if (user.getDeptCode() != null) {
            // 🔧 새로운 메서드 사용 (Set 반환이므로 중복 제거됨)
            Set<UserPermissionEntity> userPermissions = userPermissionRepository.getAllUserPermissions(userId);
            Set<DeptPermissionEntity> deptPermissions = deptPermissionRepository.getAllDeptPermissions(user.getDeptCode());

            permissions.addAll(userPermissions.stream()
                    .map(UserPermissionEntity::getPermissionType)
                    .collect(Collectors.toSet()));

            permissions.addAll(deptPermissions.stream()
                    .map(DeptPermissionEntity::getPermissionType)
                    .collect(Collectors.toSet()));
        } else {
            Set<UserPermissionEntity> userPermissions = userPermissionRepository.getAllUserPermissions(userId);
            permissions.addAll(userPermissions.stream()
                    .map(UserPermissionEntity::getPermissionType)
                    .collect(Collectors.toSet()));
        }

        return permissions;
    }

    /**
     * 요청 범위 내 권한 캐시 가져오기
     */
    @SuppressWarnings("unchecked")
    private Map<String, Set<PermissionType>> getRequestPermissionCache() {
        try {
            ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attributes != null) {
                HttpServletRequest request = attributes.getRequest();
                Map<String, Set<PermissionType>> cache =
                        (Map<String, Set<PermissionType>>) request.getAttribute(PERMISSION_CACHE_KEY);

                if (cache == null) {
                    cache = new ConcurrentHashMap<>();
                    request.setAttribute(PERMISSION_CACHE_KEY, cache);
                }
                return cache;
            }
        } catch (Exception e) {
            log.warn("요청 컨텍스트를 가져올 수 없습니다. 임시 캐시를 사용합니다.", e);
        }

        // 요청 컨텍스트가 없는 경우 임시 캐시 사용
        return new ConcurrentHashMap<>();
    }

    /**
     * 사용자가 특정 권한을 가지고 있는지 확인 (개인 권한 OR 부서 권한)
     */
    public boolean hasPermission(String userId, PermissionType permissionType) {
        Set<PermissionType> userPermissions = getAllUserPermissions(userId);
        return userPermissions.contains(permissionType);
    }

    /**
     * 사용자에게 인사 관련 권한이 있는지 확인 (모든 인사 권한 중 하나라도)
     */
    public boolean hasAnyHrPermission(String userId) {
        Set<PermissionType> userPermissions = getAllUserPermissions(userId);
        return userPermissions.contains(PermissionType.HR_LEAVE_APPLICATION) ||
                userPermissions.contains(PermissionType.HR_CONTRACT);
    }

    /**
     * 여러 권한을 한번에 확인 (중복 조회 방지)
     */
    public Map<PermissionType, Boolean> hasPermissions(String userId, Set<PermissionType> permissionTypes) {
        Set<PermissionType> userPermissions = getAllUserPermissions(userId);

        return permissionTypes.stream()
                .collect(Collectors.toMap(
                        permission -> permission,
                        userPermissions::contains
                ));
    }

    /**
     * 관리자 권한 검증 (최적화됨)
     */
    private void validateAdminPermission(String userId) {
        // 요청 범위 내에서 이미 검증했으면 재검증 생략
        Map<String, Set<PermissionType>> requestCache = getRequestPermissionCache();
        String validationKey = "ADMIN_VALIDATED_" + userId;

        if (requestCache.containsKey(validationKey)) {
            return; // 이미 검증됨
        }

        UserEntity user = userRepository.findByUserId(userId)
                .orElseThrow(() -> new RuntimeException("사용자를 찾을 수 없습니다: " + userId));

        if (user.getRole() != Role.ADMIN) {
            throw new RuntimeException("관리자 권한이 필요합니다.");
        }

        // 안전하게 jobLevel 파싱
        int adminLevel = -1;
        try {
            if (user.getJobLevel() != null) {
                adminLevel = Integer.parseInt(user.getJobLevel());
            }
        } catch (NumberFormatException ignored) {}

        // 상위 관리자(jobLevel >= 2)면 허용
        if (adminLevel >= 2) {
            requestCache.put(validationKey, Collections.emptySet()); // 검증 완료 표시
            return;
        }

        // 한 번의 쿼리로 개인 + 부서 권한 확인
        Set<PermissionType> allPermissions = getAllUserPermissions(userId);
        if (!allPermissions.contains(PermissionType.MANAGE_USERS)) {
            throw new RuntimeException("권한 관리는 상위 관리자 또는 MANAGE_USERS 권한이 있는 사용자만 가능합니다.");
        }

        requestCache.put(validationKey, Collections.emptySet()); // 검증 완료 표시
    }

    /**
     * 부서에 권한 부여
     */
    @Transactional
    @CacheEvict(value = "userAllPermissionsCache", allEntries = true)
    public void grantDeptPermission(String adminUserId, String deptCode, PermissionType permissionType) {
        validateAdminPermission(adminUserId);

        if (deptPermissionRepository.existsByDeptCodeAndPermissionType(deptCode, permissionType)) {
            log.info("부서 {}에 이미 {} 권한이 존재합니다", deptCode, permissionType);
            return;
        }

        DeptPermissionEntity permission = new DeptPermissionEntity(deptCode, permissionType);
        deptPermissionRepository.save(permission);

        // 🆕 캐시 무효화
        invalidatePermissionCache();

        log.info("관리자 {}가 부서 {}에 {} 권한 부여", adminUserId, deptCode, permissionType);
    }

    /**
     * 부서에서 권한 제거
     */
    @Transactional
    @CacheEvict(value = "userAllPermissionsCache", allEntries = true)
    public void revokeDeptPermission(String adminUserId, String deptCode, PermissionType permissionType) {
        validateAdminPermission(adminUserId);
        deptPermissionRepository.deleteByDeptCodeAndPermissionType(deptCode, permissionType);

        // 🆕 캐시 무효화
        invalidatePermissionCache();

        log.info("관리자 {}가 부서 {}에서 {} 권한 제거", adminUserId, deptCode, permissionType);
    }

    /**
     * 개인에게 권한 부여
     */
    @Transactional
    @CacheEvict(value = "userAllPermissionsCache", allEntries = true)
    public void grantUserPermission(String adminUserId, String targetUserId, PermissionType permissionType) {
        validateAdminPermission(adminUserId);

        UserEntity targetUser = userRepository.findByUserId(targetUserId)
                .orElseThrow(() -> new RuntimeException("대상 사용자를 찾을 수 없습니다: " + targetUserId));

        if (targetUser.getRole() != Role.ADMIN) {
            throw new RuntimeException("ADMIN 권한이 있는 사용자에게만 인사 권한을 부여할 수 있습니다.");
        }

        if (userPermissionRepository.existsByUserIdAndPermissionType(targetUserId, permissionType)) {
            log.info("사용자 {}에게 이미 {} 권한이 존재합니다", targetUserId, permissionType);
            return;
        }

        UserPermissionEntity permission = new UserPermissionEntity(targetUserId, permissionType);
        userPermissionRepository.save(permission);

        // 🆕 캐시 무효화
        invalidatePermissionCache();

        log.info("관리자 {}가 사용자 {}에게 {} 권한 부여", adminUserId, targetUserId, permissionType);
    }

    /**
     * 개인에서 권한 제거
     */
    @Transactional
    @CacheEvict(value = "userAllPermissionsCache", allEntries = true)
    public void revokeUserPermission(String adminUserId, String targetUserId, PermissionType permissionType) {
        validateAdminPermission(adminUserId);
        userPermissionRepository.deleteByUserIdAndPermissionType(targetUserId, permissionType);

        // 🆕 캐시 무효화
        invalidatePermissionCache();

        log.info("관리자 {}가 사용자 {}에서 {} 권한 제거", adminUserId, targetUserId, permissionType);
    }

    /**
     * 권한 캐시 무효화
     */
    private void invalidatePermissionCache() {
        try {
            // 요청 범위 캐시 무효화
            Map<String, Set<PermissionType>> requestCache = getRequestPermissionCache();
            requestCache.clear();

            // Spring Cache 무효화는 별도 구현 필요
            // @CacheEvict 어노테이션이나 CacheManager 사용
        } catch (Exception e) {
            log.warn("캐시 무효화 실패", e);
        }
    }

    // 기존 메서드들은 그대로 유지...
    public List<DeptPermissionEntity> getDeptPermissions(String deptCode) {
        return deptPermissionRepository.findByDeptCode(deptCode);
    }

    public List<UserPermissionEntity> getUserPermissions(String userId) {
        return userPermissionRepository.findByUserId(userId);
    }

    public List<String> getDeptCodesWithPermission(PermissionType permissionType) {
        return deptPermissionRepository.findDeptCodesByPermissionType(permissionType);
    }

    public List<String> getUserIdsWithPermission(PermissionType permissionType) {
        return userPermissionRepository.findUserIdsByPermissionType(permissionType);
    }

    /**
     * 여러 사용자의 권한을 배치로 조회하는 메서드 (개선됨)
     */
    public Map<String, Set<PermissionType>> getUsersPermissionsBatch(Set<String> userIds) {
        Map<String, Set<PermissionType>> result = new HashMap<>();

        // 이미 캐시된 사용자들은 제외
        Map<String, Set<PermissionType>> requestCache = getRequestPermissionCache();
        Set<String> uncachedUserIds = userIds.stream()
                .filter(userId -> !requestCache.containsKey(userId))
                .collect(Collectors.toSet());

        // 캐시된 권한들 먼저 추가
        userIds.forEach(userId -> {
            if (requestCache.containsKey(userId)) {
                result.put(userId, requestCache.get(userId));
            }
        });

        if (uncachedUserIds.isEmpty()) {
            return result; // 모두 캐시에서 가져옴
        }

        // 캐시되지 않은 사용자들만 DB에서 조회
        Map<String, Set<PermissionType>> dbResult = getUsersPermissionsBatchFromDB(uncachedUserIds);

        // 결과를 캐시에 저장하고 최종 결과에 추가
        dbResult.forEach((userId, permissions) -> {
            requestCache.put(userId, permissions);
            result.put(userId, permissions);
        });

        return result;
    }

    /**
     * DB에서 여러 사용자의 권한을 배치로 조회 (실제 DB 조회)
     */
    private Map<String, Set<PermissionType>> getUsersPermissionsBatchFromDB(Set<String> userIds) {
        Map<String, Set<PermissionType>> result = new HashMap<>();

        // 사용자 정보를 배치로 조회
        List<UserEntity> users = userRepository.findByUserIdIn(new ArrayList<>(userIds));
        Map<String, UserEntity> userMap = users.stream()
                .collect(Collectors.toMap(UserEntity::getUserId, user -> user));

        // ADMIN 역할이 있는 사용자들만 필터링
        Set<String> adminUserIds = users.stream()
                .filter(user -> user.getRole() == Role.ADMIN)
                .map(UserEntity::getUserId)
                .collect(Collectors.toSet());

        if (adminUserIds.isEmpty()) {
            userIds.forEach(userId -> result.put(userId, Collections.emptySet()));
            return result;
        }

        // 개인 권한 배치 조회
        List<UserPermissionEntity> userPermissions = userPermissionRepository.findByUserIdIn(adminUserIds);
        Map<String, Set<PermissionType>> userPermissionMap = userPermissions.stream()
                .collect(Collectors.groupingBy(
                        UserPermissionEntity::getUserId,
                        Collectors.mapping(UserPermissionEntity::getPermissionType, Collectors.toSet())
                ));

        // 부서별 권한 배치 조회
        Set<String> deptCodes = users.stream()
                .filter(user -> user.getDeptCode() != null)
                .map(UserEntity::getDeptCode)
                .collect(Collectors.toSet());

        Map<String, Set<PermissionType>> deptPermissionMap = new HashMap<>();
        if (!deptCodes.isEmpty()) {
            List<DeptPermissionEntity> deptPermissions = deptPermissionRepository.findByDeptCodeIn(deptCodes);
            deptPermissionMap = deptPermissions.stream()
                    .collect(Collectors.groupingBy(
                            DeptPermissionEntity::getDeptCode,
                            Collectors.mapping(DeptPermissionEntity::getPermissionType, Collectors.toSet())
                    ));
        }

        // 결과 조합
        for (String userId : userIds) {
            UserEntity user = userMap.get(userId);
            Set<PermissionType> permissions = new HashSet<>();

            if (user != null && user.getRole() == Role.ADMIN) {
                permissions.addAll(userPermissionMap.getOrDefault(userId, Collections.emptySet()));
                if (user.getDeptCode() != null) {
                    permissions.addAll(deptPermissionMap.getOrDefault(user.getDeptCode(), Collections.emptySet()));
                }
            }

            result.put(userId, permissions);
        }

        return result;
    }
}