package sunhan.sunhanbackend.enums;

public enum HalfDayType {
    ALL_DAY("종일", 1.0),
    MORNING("오전", 0.5),
    AFTERNOON("오후", 0.5);

    private final String displayName;
    private final Double dayValue;

    HalfDayType(String displayName, Double dayValue) {
        this.displayName = displayName;
        this.dayValue = dayValue;
    }

    public String getDisplayName() {
        return displayName;
    }

    public Double getDayValue() {
        return dayValue;
    }
}