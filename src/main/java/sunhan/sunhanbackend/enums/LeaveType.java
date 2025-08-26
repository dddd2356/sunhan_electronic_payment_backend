package sunhan.sunhanbackend.enums;

public enum LeaveType {
    ANNUAL_LEAVE("연차휴가"),
    FAMILY_EVENT_LEAVE("경조휴가"),
    SPECIAL_LEAVE("특별휴가"),
    MENSTRUAL_LEAVE("생리휴가"),
    MATERNITY_LEAVE("보민휴가"),
    MISCARRIAGE_LEAVE("유산사산휴가"),
    SICK_LEAVE("병가"),
    OTHER("기타");

    private final String displayName;

    LeaveType(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }

    @Override
    public String toString() {
        return displayName; // 한글 표시
    }
}
