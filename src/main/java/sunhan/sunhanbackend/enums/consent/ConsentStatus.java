package sunhan.sunhanbackend.enums.consent;

/**
 * 동의서 상태
 */
public enum ConsentStatus {
    /**
     * 발송됨 (작성 대기 중)
     * - 대상자가 아직 작성하지 않은 상태
     */
    ISSUED("발송됨"),

    /**
     * 작성 완료
     * - 대상자가 모든 항목을 작성하고 제출한 상태
     */
    COMPLETED("작성 완료"),

    /**
     * 취소됨 (추후 확장용)
     * - 발급자가 발송을 취소한 경우
     */
    CANCELLED("취소됨");

    private final String displayName;

    ConsentStatus(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}