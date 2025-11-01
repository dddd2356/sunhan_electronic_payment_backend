package sunhan.sunhanbackend.entity.mysql;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CacheConcurrencyStrategy;
import sunhan.sunhanbackend.enums.Role;
import org.hibernate.annotations.Cache; // 하이버네이트 어노테이션 임포트

import java.io.Serializable;
import java.util.Objects;

@Getter
@Setter
@NoArgsConstructor //매개변수 없는 생성자 자동으로 만들어줌
@AllArgsConstructor //모든 필드에 대해서 받아오는 생성자 만들어줌
@Entity // "user" 테이블과 매핑되는 엔티티 클래스
@Table(name="usrmst", indexes = {
        @Index(name = "idx_user_dept_job", columnList = "deptcode, joblevel"),
        @Index(name = "idx_user_role", columnList = "role"),
        @Index(name = "idx_user_jobLevel", columnList = "joblevel")
})  // 테이블 이름 지정
@Cache(usage = CacheConcurrencyStrategy.READ_WRITE) // 캐시 설정 추가
//MySQL 테이블
public class UserEntity implements Serializable { // 여기에 Serializable 추가
    private static final long serialVersionUID = 1L;
    @Id
    @Column(name = "id", nullable = false, unique = true)  // "user_id" 컬럼을 기본 키로 설정
    private String userId;  // 사용자 ID
    @Version
    private Long version;
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
    private String address;
    @Column(name = "detail_address")
    private String detailAddress;
    @Column(name = "useflag")
    private String useFlag;
    @Column(name = "signpath")
    private String signpath;
    //BLOB 형태로 이미지 자체 저장
    @Lob
    //@Basic(fetch = FetchType.EAGER)
    @Column(name = "signimage")
    private byte[] signimage;
    @Column(name = "passwd_change_required") // 컬럼명은 실제 DB에 맞게
    private Boolean passwordChangeRequired; // 비밀번호 변경 필요 플래그
    @Enumerated(EnumType.STRING)
    @Column(name = "role")
    private Role role = Role.USER; // 기본값 USER
    // UserEntity.java에 추가할 필드들
    @Column(name = "total_vacation_days")
    private Integer totalVacationDays = 15; // 기본값 15일

    @Column(name = "used_vacation_days")
    private Integer usedVacationDays = 0; // 사용한 휴가일수

    @Column(name = "phone_verified")
    private Boolean phoneVerified = false;

    // 개인정보 수집/이용 동의 필드 추가
    @Column(name = "privacy_consent")
    private Boolean privacyConsent = false; // 기본값은 false로 설정

    // 알림 수신 동의 여부 필드 추가
    @Column(name = "notification_consent")
    private Boolean notificationConsent = false; // 기본값은 false로 설정

    public boolean isAdmin() {
        return this.role == Role.ADMIN;
    }

    public boolean isSuperAdmin() {
        try {
            int jl = Integer.parseInt(jobLevel);
            return jl == 6; // 최고 관리자
        } catch (NumberFormatException e) {
            return false;
        }
    }

    public boolean isUser() {
        return "1".equals(useFlag);
    }

    public String getRoleForSecurity() {
        return this.jobLevel; // 또는 this.getJobLevel()
    }
    // 캐시 효율성을 위한 equals/hashCode 추가
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof UserEntity)) return false;
        UserEntity that = (UserEntity) o;
        return Objects.equals(userId, that.userId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(userId);
    }

    /**
     * 남은 연차 일수 계산
     * @return 총 휴가일수 - 사용한 휴가일수
     */
    public Integer getRemainingAnnualLeave() {
        if (totalVacationDays == null) {
            return 15; // 기본값
        }
        if (usedVacationDays == null) {
            return totalVacationDays;
        }
        return Math.max(0, totalVacationDays - usedVacationDays);
    }

    /**
     * 휴가 사용
     * @param days 사용할 일수
     * @throws IllegalStateException 잔여 일수 부족 시
     */
    public void useVacationDays(double days) {
        if (days <= 0) {
            throw new IllegalArgumentException("사용 일수는 0보다 커야 합니다.");
        }

        if (getRemainingAnnualLeave() < days) {
            throw new IllegalStateException(
                    String.format("잔여 연차가 부족합니다. (필요: %.1f일, 잔여: %d일)",
                            days, getRemainingAnnualLeave())
            );
        }

        if (usedVacationDays == null) {
            usedVacationDays = 0;
        }
        usedVacationDays += (int) Math.ceil(days);
    }

    /**
     * 휴가 복구 (취소 시)
     * @param days 복구할 일수
     */
    public void restoreVacationDays(double days) {
        if (days <= 0) {
            throw new IllegalArgumentException("복구 일수는 0보다 커야 합니다.");
        }

        if (usedVacationDays == null) {
            usedVacationDays = 0;
        }

        usedVacationDays = Math.max(0, usedVacationDays - (int) Math.ceil(days));
    }
}
