package sunhan.sunhanbackend.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class DepartmentDto {
    private String deptCode;
    private String deptName;
    private String parentDeptCode;
}
