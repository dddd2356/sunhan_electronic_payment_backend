package sunhan.sunhanbackend.entity.mysql;

import jakarta.persistence.*;
import lombok.*;

import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "department") // 실제 DB에 생성할 부서 테이블 이름 (예: deptmst 등)
public class Department {
    @Id
    @Column(name = "deptcode", length = 20)
    private String deptCode; // 부서 코드 (PK)

    @Column(name = "deptname", length = 50)
    private String deptName; // 부서 이름

    @Column(name = "useflag", length = 1)
    private String useFlag; // 사용 여부 (1:사용, 0:미사용)

    @Transient
    private String parentDeptCode;

    @Transient
    private List<Department> children;
}