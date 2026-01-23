package sunhan.sunhanbackend.enums;

import lombok.Getter;

@Getter
public enum Role {
    USER("0"),
    ADMIN("1");

    private final String value;

    Role(String value) {
        this.value = value;
    }

    // ⭐ 추가: 문자열 값으로 Role 찾기
    public static Role fromValue(String value) {
        for (Role role : Role.values()) {
            if (role.value.equals(value)) {
                return role;
            }
        }
        throw new IllegalArgumentException("Unknown role value: " + value);
    }
}