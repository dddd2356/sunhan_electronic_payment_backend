package sunhan.sunhanbackend.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import sunhan.sunhanbackend.entity.mysql.Department;
import sunhan.sunhanbackend.repository.mysql.DepartmentRepository;

import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/departments")
@RequiredArgsConstructor
@Slf4j
public class DepartmentController {

    private final DepartmentRepository departmentRepository;

    @GetMapping("/names")
    public ResponseEntity<Map<String, String>> getDepartmentNames() {
        Map<String,String> map = departmentRepository.findAllActive()
                .stream()
                .collect(Collectors.toMap(Department::getDeptCode, Department::getDeptName));
        return ResponseEntity.ok(map);
    }
}
