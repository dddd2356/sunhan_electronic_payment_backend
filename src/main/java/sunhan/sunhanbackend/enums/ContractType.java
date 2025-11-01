package sunhan.sunhanbackend.enums;

import lombok.Getter;

@Getter
public enum ContractType {
    EMPLOYMENT_CONTRACT("근로계약서"),
    LEAVE_APPLICATION("휴가원");

    private final String displayName;

    ContractType(String displayName) {
        this.displayName = displayName;
    }

}