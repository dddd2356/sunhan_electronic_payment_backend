package sunhan.sunhanbackend.entity.mysql.consent;

import jakarta.persistence.*;
import lombok.Data;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import sunhan.sunhanbackend.enums.consent.ConsentType;

import java.time.LocalDateTime;

/**
 * 동의서 양식 템플릿
 * - 3가지 고정된 동의서 타입별로 최신 버전을 관리
 * - HTML 기반 템플릿으로 PDF 생성에 활용
 * - 추후 버전 관리 및 재동의 요청 시 활용 가능
 */
@Entity
@Table(
        name = "consent_form",
        indexes = {
                @Index(name = "idx_type_active", columnList = "type, is_active"),
                @Index(name = "idx_type_version", columnList = "type, version")
        },
        uniqueConstraints = {
                // 동일 타입 + 버전은 유일해야 함
                @UniqueConstraint(name = "uk_type_version", columnNames = {"type", "version"})
        }
)
@Data
@NoArgsConstructor
public class ConsentForm {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 동의서 타입
     * - PRIVACY_POLICY: 개인정보 수집·이용 동의서
     * - SOFTWARE_USAGE: 소프트웨어 사용 서약서
     * - INTERNAL_REGULATION: 내부 규정 동의서
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private ConsentType type;

    /**
     * 동의서 제목
     */
    @Column(nullable = false, length = 200)
    private String title;

    /**
     * 동의서 본문 (HTML)
     * - Mustache 또는 Thymeleaf 템플릿 형식
     * - 변수: {{userName}}, {{residentNumber}} 등
     */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    /**
     * 버전 번호
     * - 동의서 내용이 변경될 때마다 증가
     * - 예: 1, 2, 3, ...
     */
    @Column(nullable = false)
    private Integer version = 1;

    /**
     * 활성화 여부
     * - true: 현재 사용 중인 버전
     * - false: 과거 버전 (참조용으로만 유지)
     */
    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;

    /**
     * 필수 입력 필드 정의 (JSON)
     * - 예: ["residentNumber", "signatureImage"]
     * - 클라이언트에서 폼 검증에 활용
     */
    @Column(name = "required_fields", columnDefinition = "TEXT")
    private String requiredFields;

    /**
     * 생성 일시
     */
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /**
     * 생성자 (관리자)
     */
    @Column(name = "created_by", length = 50)
    private String createdBy;

    /**
     * 설명 (관리용)
     */
    @Column(length = 500)
    private String description;

    // ==================== Lifecycle Callbacks ====================

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }

    // ==================== Business Logic ====================

    // ✅ 추가: 타입별 필수 필드 자동 설정
    public void setDefaultRequiredFields() {
        switch (this.type) {
            case PRIVACY_POLICY:
                this.requiredFields = "[\"essentialInfoAgree\",\"optionalInfoAgree\",\"uniqueIdAgree\",\"sensitiveInfoAgree\",\"agreementDate\",\"signature\"]";
                break;
            case SOFTWARE_USAGE:
                this.requiredFields = "[\"agreementDate\",\"signature\"]";
                break;
            case MEDICAL_INFO_SECURITY:
                this.requiredFields = "[\"jobType\",\"residentNumber\",\"email\",\"agreementDate\",\"signature\"]";
                break;
        }
    }

    /**
     * 새 버전 생성 시 기존 버전 비활성화
     */
    public static ConsentForm createNewVersion(ConsentForm oldForm, String newContent, String updatedBy) {
        ConsentForm newForm = new ConsentForm();
        newForm.setType(oldForm.getType());
        newForm.setTitle(oldForm.getTitle());
        newForm.setContent(newContent);
        newForm.setVersion(oldForm.getVersion() + 1);
        newForm.setIsActive(true);
        newForm.setRequiredFields(oldForm.getRequiredFields());
        newForm.setCreatedBy(updatedBy);
        newForm.setDescription("버전 " + newForm.getVersion() + " - 내용 업데이트");

        // 기존 버전 비활성화
        oldForm.setIsActive(false);

        return newForm;
    }
}