package sunhan.sunhanbackend.enums;

import lombok.Getter;

@Getter
public enum ContractType {
    EMPLOYMENT_CONTRACT("근로계약서"),
    LEAVE_APPLICATION("휴가원");
    // 향후 추가될 계약서 타입들
    // RESIGNATION_LETTER("사직서"),
    // BUSINESS_TRIP_REQUEST("출장신청서");

    private final String displayName;

    ContractType(String displayName) {
        this.displayName = displayName;
    }

}