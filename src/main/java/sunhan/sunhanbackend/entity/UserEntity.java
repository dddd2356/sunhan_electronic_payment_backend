package sunhan.sunhanbackend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.*;

@Getter
@Setter
@NoArgsConstructor //매개변수 없는 생성자 자동으로 만들어줌
@AllArgsConstructor //모든 필드에 대해서 받아오는 생성자 만들어줌
@Entity(name="usrmst")  // "user" 테이블과 매핑되는 엔티티 클래스
@Table(name="usrmst")  // 테이블 이름 지정

//MySQL 테이블
public class UserEntity {
    @Id
    @Column(name = "id", nullable = false, unique = true)  // "user_id" 컬럼을 기본 키로 설정
    private String userId;  // 사용자 ID
    @Column(name = "name")
    private String userName; // 사용자 이름
    private String passwd;  // 비밀번호
    @Column(name="jobtype")
    private String jobType;
    @Column(name = "joblevel")
    private String jobLevel;  // 역할 (사원, 원장 등) - Role 역할
    @Column(name = "deptcode")
    private String deptCode;  // 부서
    private String phone;
    @Column(name = "usrflag")
    private String usrFlag;

    public boolean isAdmin() {
        try {
            int jt = Integer.parseInt(jobLevel);
            return jt == 1 || jt == 2;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    public boolean isUser() {
        return "1".equals(usrFlag);
    }

    public String getRoleForSecurity() {
        if (jobLevel == null) {
            return "ROLE_USER";
        }
        return jobLevel.startsWith("ROLE_") ? jobLevel : "ROLE_" + jobLevel;
    }
}
