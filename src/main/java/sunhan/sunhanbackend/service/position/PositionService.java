package sunhan.sunhanbackend.service.position;

import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sunhan.sunhanbackend.entity.mysql.UserEntity;
import sunhan.sunhanbackend.entity.mysql.position.Position;
import sunhan.sunhanbackend.repository.mysql.UserRepository;
import sunhan.sunhanbackend.repository.mysql.position.PositionRepository;

import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class PositionService {

    private final PositionRepository positionRepository;
    private final UserRepository userRepository;

    /**
     * 직책 생성
     */
    @Transactional
    public Position createPosition(String deptCode, String positionName, Integer displayOrder, String creatorId) {
        // 권한 검증: 해당 부서의 관리자만 생성 가능
        validateDeptAdmin(creatorId, deptCode);

        // 중복 체크
        positionRepository.findByDeptCodeAndPositionNameAndIsActiveTrue(deptCode, positionName)
                .ifPresent(p -> {
                    throw new IllegalStateException("이미 존재하는 직책입니다: " + positionName);
                });

        // displayOrder가 없으면 자동으로 마지막으로 설정
        if (displayOrder == null) {
            Integer maxOrder = positionRepository.findMaxDisplayOrderByDeptCode(deptCode);
            displayOrder = maxOrder + 1;
        }

        Position position = new Position(deptCode, positionName, displayOrder, creatorId);
        Position saved = positionRepository.save(position);

        log.info("직책 생성 완료: deptCode={}, positionName={}, createdBy={}",
                deptCode, positionName, creatorId);

        return saved;
    }

    /**
     * 부서별 직책 목록 조회
     */
    @Transactional(readOnly = true)
    public List<Position> getPositionsByDept(String deptCode) {
        return positionRepository.findByDeptCodeAndIsActiveTrueOrderByDisplayOrderAsc(deptCode);
    }

    /**
     * 직책 수정
     */
    @Transactional
    public Position updatePosition(Long positionId, String positionName, Integer displayOrder, String userId) {
        Position position = positionRepository.findById(positionId)
                .orElseThrow(() -> new EntityNotFoundException("직책을 찾을 수 없습니다: " + positionId));

        // 권한 검증
        validateDeptAdmin(userId, position.getDeptCode());

        if (positionName != null && !positionName.trim().isEmpty()) {
            position.setPositionName(positionName.trim());
        }

        if (displayOrder != null) {
            position.setDisplayOrder(displayOrder);
        }

        Position saved = positionRepository.save(position);
        log.info("직책 수정 완료: id={}, name={}", positionId, positionName);

        return saved;
    }

    /**
     * 직책 삭제 (비활성화)
     */
    @Transactional
    public void deletePosition(Long positionId, String userId) {
        Position position = positionRepository.findById(positionId)
                .orElseThrow(() -> new EntityNotFoundException("직책을 찾을 수 없습니다: " + positionId));

        // 권한 검증
        validateDeptAdmin(userId, position.getDeptCode());

        position.setIsActive(false);
        positionRepository.save(position);

        log.info("직책 비활성화 완료: id={}, name={}", positionId, position.getPositionName());
    }

    /**
     * 직책 순서 변경
     */
    @Transactional
    public void reorderPositions(String deptCode, List<Long> positionIds, String userId) {
        validateDeptAdmin(userId, deptCode);

        for (int i = 0; i < positionIds.size(); i++) {
            Long positionId = positionIds.get(i);
            Position position = positionRepository.findById(positionId)
                    .orElseThrow(() -> new EntityNotFoundException("직책을 찾을 수 없습니다: " + positionId));

            if (!position.getDeptCode().equals(deptCode)) {
                throw new IllegalArgumentException("다른 부서의 직책은 순서를 변경할 수 없습니다.");
            }

            position.setDisplayOrder(i);
            positionRepository.save(position);
        }

        log.info("직책 순서 변경 완료: deptCode={}, count={}", deptCode, positionIds.size());
    }

    /**
     * 부서 관리자 권한 검증
     */
    private void validateDeptAdmin(String userId, String deptCode) {
        UserEntity user = userRepository.findByUserId(userId)
                .orElseThrow(() -> new EntityNotFoundException("사용자를 찾을 수 없습니다: " + userId));

        // 부서장(jobLevel=1) 이상이면서 같은 부서이거나, 상위 관리자(jobLevel>=2)인 경우
        int jobLevel = Integer.parseInt(user.getJobLevel());

        if (jobLevel == 1 && !user.getDeptCode().equals(deptCode)) {
            throw new SecurityException("해당 부서의 직책을 관리할 권한이 없습니다.");
        }

        if (jobLevel == 0) {
            throw new SecurityException("직책을 관리할 권한이 없습니다.");
        }
    }
}