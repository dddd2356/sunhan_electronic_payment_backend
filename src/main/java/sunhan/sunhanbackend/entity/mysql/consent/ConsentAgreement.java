package sunhan.sunhanbackend.entity.mysql.consent;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.*;
import lombok.Data;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import sunhan.sunhanbackend.enums.consent.ConsentStatus;
import sunhan.sunhanbackend.enums.consent.ConsentType;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.Map;

/**
 * 동의서 발급/작성 인스턴스
 * - 한 명의 targetUser에게 발급된 특정 동의서를 나타냄
 * - 동일 타입의 동의서는 완료 후 재발급 불가 (중복 방지)
 */
@Entity
@Table(
        name = "consent_agreement",
        indexes = {
                @Index(name = "idx_target_status", columnList = "target_user_id, status"),
                @Index(name = "idx_creator", columnList = "creator_id"),
                @Index(name = "idx_type_status", columnList = "consent_form_id, status")
        },
        uniqueConstraints = {
                // ✅ 한 사용자당 동일 타입 동의서는 완료 상태가 1개만 존재 가능
                @UniqueConstraint(
                        name = "uk_target_type_completed",
                        columnNames = {"target_user_id", "consent_form_id", "status"}
                )
        }
)
@Data
@NoArgsConstructor
@JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
public class ConsentAgreement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 동의서 템플릿 (양식)
     * - 다대일 관계: 여러 동의서가 하나의 템플릿을 참조
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "consent_form_id", nullable = false)
    private ConsentForm consentForm;

    /**
     * 작성 대상자 (동의서를 받을 사람)
     */
    @Column(name = "target_user_id", nullable = false, length = 50)
    private String targetUserId;

    /**
     * 발급자 (동의서를 발송한 사람)
     * - 생성 권한(CONSENT_CREATE)을 가진 사용자
     */
    @Column(name = "creator_id", nullable = false, length = 50)
    private String creatorId;

    /**
     * 동의서 상태
     * - ISSUED: 발송됨 (작성 대기 중)
     * - COMPLETED: 작성 완료
     * - CANCELLED: 취소됨 (추후 확장용)
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ConsentStatus status = ConsentStatus.ISSUED;

    /**
     * 작성 완료된 양식 데이터 (JSON)
     * - 체크박스, 텍스트 입력, 서명 등의 정보
     * - 완료 시점에만 저장됨
     */
    @Column(name = "form_data_json", columnDefinition = "TEXT")
    private String formDataJson;

    /**
     * 동의서별 추가 데이터 (JSON)
     * - 개인정보 동의서: 주민번호 등
     * - 소프트웨어 서약서: 장비 번호 등
     * - 확장성을 위해 JSON으로 관리
     */
    @Column(name = "extra_data_json", columnDefinition = "TEXT")
    private String extraDataJson;

    /**
     * PDF 저장 경로
     * - 작성 완료 시 비동기로 생성됨
     */
    @Column(name = "pdf_url", length = 500)
    private String pdfUrl;

    /**
     * 작성 완료 일시
     */
    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    /**
     * 발급 일시 (생성 시각)
     */
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /**
     * 마지막 수정 일시
     */
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    /**
     * 버전 관리 (낙관적 락)
     */
    @Version
    private Long version;

    // ==================== Helper Methods ====================

    /**
     * extraDataJson을 Map으로 변환하여 반환
     */
    @Transient
    private static final ObjectMapper objectMapper = new ObjectMapper();

    public Map<String, String> getExtraData() {
        if (extraDataJson == null || extraDataJson.isBlank()) {
            return Collections.emptyMap();
        }
        try {
            return objectMapper.readValue(extraDataJson, new TypeReference<Map<String, String>>() {});
        } catch (Exception e) {
            return Collections.emptyMap();
        }
    }


    /**
     * Map을 JSON으로 변환하여 설정
     */
    public void setExtraData(Map<String, String> data) {
        try {
            this.extraDataJson = objectMapper.writeValueAsString(data);
        } catch (Exception e) {
            this.extraDataJson = "{}";
        }
    }

    /**
     * 작성 대상자 이름 (조회용)
     * - UserEntity 조인 없이 사용할 수 있도록 별도 필드 추가 가능
     */
    @Column(name = "target_user_name", length = 100)
    private String targetUserName;

    /**
     * 동의서 타입 (편의용)
     * - ConsentForm을 통해서도 접근 가능하지만, 자주 사용되므로 중복 저장
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "type", length = 50)
    private ConsentType type;

    @Column(name = "dept_name", length = 100)
    private String deptName;

    @Column(name = "phone", length = 20)
    private String phone;

    // ==================== Lifecycle Callbacks ====================

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();

        // ConsentForm에서 type 복사
        if (this.consentForm != null && this.type == null) {
            this.type = this.consentForm.getType();
        }
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    // ==================== Business Logic ====================

    /**
     * 작성 완료 처리
     */
    public void complete(String formDataJson) {
        if (this.status == ConsentStatus.COMPLETED) {
            throw new IllegalStateException("이미 완료된 동의서입니다.");
        }
        this.formDataJson = formDataJson;
        this.status = ConsentStatus.COMPLETED;
        this.completedAt = LocalDateTime.now();
    }

    /**
     * 조회 권한 확인
     * @param userId 조회 시도하는 사용자
     * @param hasManagePermission 관리 권한 보유 여부
     * @return 조회 가능 여부
     */
    public boolean canBeViewedBy(String userId, boolean hasManagePermission) {
        // 관리 권한 보유자는 모두 조회 가능
        if (hasManagePermission) {
            return true;
        }

        // 작성 대상자는 본인 동의서만 조회 가능
        if (this.targetUserId.equals(userId)) {
            return true;
        }
        if (this.status == ConsentStatus.COMPLETED && this.creatorId.equals(userId)) {
            return true;
        }

        // 발급 상태(ISSUED)에서도 발급자는 조회 가능
        if (this.status == ConsentStatus.ISSUED && this.creatorId.equals(userId)) {
            return true;
        }

        return false;
    }

    /**
     * 수정 권한 확인
     */
    public boolean canBeEditedBy(String userId) {
        // 발급 상태(ISSUED)일 때만 작성 가능
        if (this.status != ConsentStatus.ISSUED) {
            return false;
        }

        // 작성 대상자만 작성 가능
        return this.targetUserId.equals(userId);
    }
}