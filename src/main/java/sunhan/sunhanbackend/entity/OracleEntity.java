package sunhan.sunhanbackend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;


@Getter
@Setter
@NoArgsConstructor //매개변수 없는 생성자 자동으로 만들어줌
@AllArgsConstructor //모든 필드에 대해서 받아오는 생성자 만들어줌
@Entity(name="usr_mst")  // "user" 테이블과 매핑되는 엔티티 클래스
@Table(name="usr_mst")  // 테이블 이름 지정
public class OracleEntity {
    @Id
    @Column(name = "USRID", nullable = false, unique = true)
    private String usrId;

    @Column(name = "USRKORNM")
    private String usrKorNM;

    @Column(name = "DEPFCD")
    private String deptCD;

    @Column(name="JOBTYPE")
    private String jobType;
}
