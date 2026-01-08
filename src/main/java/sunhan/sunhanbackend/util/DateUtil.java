package sunhan.sunhanbackend.util;

import lombok.extern.slf4j.Slf4j;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

@Slf4j
public class DateUtil {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd");

    /**
     * Oracle의 VARCHAR2(8) 형태 날짜를 LocalDate로 변환
     * @param dateString "20240101" 형태의 문자열
     * @return LocalDate 객체, 변환 실패 시 null
     */
    public static LocalDate parseOracleDate(String dateString) {
        if (dateString == null || dateString.trim().isEmpty()) {
            return null;
        }

        try {
            // 공백 제거 및 8자리 체크
            String trimmed = dateString.trim();
            if (trimmed.length() != 8) {
                log.warn("올바르지 않은 날짜 형식 (8자리 아님): {}", dateString);
                return null;
            }

            return LocalDate.parse(trimmed, FORMATTER);
        } catch (DateTimeParseException e) {
            log.warn("날짜 파싱 실패: {}", dateString, e);
            return null;
        }
    }

    /**
     * LocalDate를 Oracle VARCHAR2(8) 형태로 변환
     * @param date LocalDate 객체
     * @return "20240101" 형태의 문자열, null이면 null 반환
     */
    public static String formatToOracleDate(LocalDate date) {
        if (date == null) {
            return null;
        }
        return date.format(FORMATTER);
    }
}