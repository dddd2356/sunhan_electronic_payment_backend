package sunhan.sunhanbackend.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sunhan.sunhanbackend.entity.mysql.Department;
import sunhan.sunhanbackend.entity.mysql.UserEntity;
import sunhan.sunhanbackend.repository.mysql.DepartmentRepository;
import sunhan.sunhanbackend.repository.mysql.UserRepository;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class DepartmentService {

    private final DepartmentRepository departmentRepository;
    private final UserRepository userRepository;

    /**
     * 모든 활성 부서 조회
     */
    @Transactional(readOnly = true)
    public List<Department> getAllActiveDepartments() {
        return departmentRepository.findAllActive();
    }

    /**
     * 신규 부서 생성
     */
    @Transactional
    public Department createDepartment(String deptCode, String deptName) {
        if (departmentRepository.existsById(deptCode)) {
            throw new RuntimeException("이미 존재하는 부서코드입니다: " + deptCode);
        }

        Department dept = Department.builder()
                .deptCode(deptCode)
                .deptName(deptName)
                .useFlag("1")
                .build();

        log.info("✅ 신규 부서 생성: deptCode={}, deptName={}", deptCode, deptName);
        return departmentRepository.save(dept);
    }

    /**
     * 부서 삭제 (논리 삭제)
     */
    @Transactional
    public void deleteDepartment(String deptCode) {
        Department dept = departmentRepository.findById(deptCode)
                .orElseThrow(() -> new RuntimeException("부서를 찾을 수 없습니다: " + deptCode));

        // 해당 부서에 소속된 활성 사용자가 있는지 확인
        long activeUserCount = userRepository.findByDeptCodeAndUseFlag(deptCode, "1").size();
        if (activeUserCount > 0) {
            throw new RuntimeException("해당 부서에 " + activeUserCount + "명의 활성 사용자가 존재합니다. 부서를 삭제할 수 없습니다.");
        }

        dept.setUseFlag("0");
        departmentRepository.save(dept);
        log.info("✅ 부서 비활성화: deptCode={}", deptCode);
    }

    /**
     * 부서명 수정
     */
    @Transactional
    public Department updateDepartmentName(String deptCode, String newName) {
        Department dept = departmentRepository.findById(deptCode)
                .orElseThrow(() -> new RuntimeException("부서를 찾을 수 없습니다: " + deptCode));

        dept.setDeptName(newName);
        log.info("✅ 부서명 수정: deptCode={}, newName={}", deptCode, newName);
        return departmentRepository.save(dept);
    }

    /**
     * 특정 부서의 사용자 목록 조회
     */
    @Transactional(readOnly = true)
    public List<UserEntity> getUsersByDepartment(String deptCode) {
        return userRepository.findByDeptCodeAndUseFlag(deptCode, "1");
    }

    /**
     * 특정 부서의 전체 사용자 수 조회 (활성/비활성 포함)
     */
    @Transactional(readOnly = true)
    public long getUserCountByDepartment(String deptCode) {
        return userRepository.findByDeptCode(deptCode).size();
    }

    /**
     * 부서 상태 토글 (활성/비활성)
     */
    @Transactional
    public Department toggleDepartmentStatus(String deptCode) {
        Department dept = departmentRepository.findById(deptCode)
                .orElseThrow(() -> new RuntimeException("부서를 찾을 수 없습니다: " + deptCode));

        if ("1".equals(dept.getUseFlag())) {
            // 비활성화 전 활성 사용자 확인
            long activeUserCount = userRepository.findByDeptCodeAndUseFlag(deptCode, "1").size();
            if (activeUserCount > 0) {
                throw new RuntimeException("해당 부서에 " + activeUserCount + "명의 활성 사용자가 존재합니다. 부서를 비활성화할 수 없습니다.");
            }
            dept.setUseFlag("0");
            log.info("✅ 부서 비활성화: deptCode={}", deptCode);
        } else {
            dept.setUseFlag("1");
            log.info("✅ 부서 활성화: deptCode={}", deptCode);
        }

        return departmentRepository.save(dept);
    }

    /**
     * 모든 부서 조회 (활성/비활성 포함)
     */
    @Transactional(readOnly = true)
    public List<Department> getAllDepartments() {
        return departmentRepository.findAll();
    }
}