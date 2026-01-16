package sunhan.sunhanbackend.enums.consent;

/**
 * 동의서 타입
 * - 3가지 고정된 동의서 종류
 */
public enum ConsentType {
    /**
     * 개인정보 수집·이용 동의서
     * - 필수 입력: 주민번호, 서명
     */
    PRIVACY_POLICY("개인정보 수집·이용 동의서"),

    /**
     * 소프트웨어 사용 서약서
     * - 필수 입력: 장비 일련번호, 서명
     */
    SOFTWARE_USAGE("소프트웨어 사용 서약서"),

    MEDICAL_INFO_SECURITY("의료정보 보호 및 보안(교육)서약서");

    private final String displayName;

    ConsentType(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}