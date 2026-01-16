package sunhan.sunhanbackend.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.YearMonth;
import java.util.HashSet;
import java.util.Set;

@Slf4j
public class WorkSchedulePdfRenderer {

    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static byte[] cachedFontBytes = null;

    public static byte[] render(String jsonData) throws IOException {
        JsonNode data = objectMapper.readTree(jsonData);
        int entryCount = data.path("entries").size();
        String htmlContent = generateWorkScheduleHtml(data, entryCount);

        try (ByteArrayOutputStream os = new ByteArrayOutputStream()) {
            PdfRendererBuilder builder = new PdfRendererBuilder();

            // ✅ 동기화 블록 추가로 멀티스레드 환경 보호
            if (cachedFontBytes == null) {
                synchronized (WorkSchedulePdfRenderer.class) {
                    if (cachedFontBytes == null) {
                        try (InputStream fontStream = WorkSchedulePdfRenderer.class.getClassLoader()
                                .getResourceAsStream("fonts/malgun.ttf")) {
                            if (fontStream != null) {
                                cachedFontBytes = fontStream.readAllBytes();
                                log.info(">>>> [SYSTEM] Malgun Gothic 폰트 캐싱 완료");
                            }
                        }
                    }
                }
            }

            if (cachedFontBytes != null) {
                builder.useFont(() -> new ByteArrayInputStream(cachedFontBytes), "Malgun Gothic");
            }

            builder.useFastMode();
            // ✅ 수정 4: 이미지 로드 시간 단축을 위해 리소스 로더 확인
            // 만약 이미지 URL이 외부망(http)이라면 여기서 시간이 많이 걸립니다.
            builder.withHtmlContent(htmlContent, null);
            builder.toStream(os);
            builder.run();
            return os.toByteArray();
        }
    }

    private static String generateWorkScheduleHtml(JsonNode data, int entryCount) {
        int daysInMonth = data.path("daysInMonth").asInt(31);
        String css = loadExcelStyleCss(entryCount, daysInMonth);
        StringBuilder html = new StringBuilder();

        String yearMonth = data.path("yearMonth").asText("");
        String deptCode = data.path("schedule").path("deptCode").asText("");
        String deptName = data.has("deptName") ? data.path("deptName").asText() : deptCode;

        JsonNode entries = data.path("entries");
        JsonNode positions = data.path("positions");
        JsonNode users = data.path("users");
        JsonNode dutyConfig = data.path("dutyConfig");
        JsonNode approvalSteps = data.path("approvalSteps");

        html.append("<!DOCTYPE html><html><head><meta charset='UTF-8'/>");
        html.append("<title>근무현황표</title><style>").append(css).append("</style></head><body>");

        // 최상위 컨테이너
        html.append("<div class='sheet-container'>");

        // 1. 타이틀 및 정보 섹션 (엑셀 상단처럼)
        html.append("<table class='header-table'><tbody>");
        html.append("<tr>");
        html.append("<td class='title-cell' colspan='2'>").append(yearMonth.replace("-", "년 ")).append("월 근무현황표</td>");
        html.append("</tr>");
        html.append("<tr>");
        html.append("<td class='info-left'>부서명 : ").append(deptName).append("</td>");
        html.append("<td class='info-right'>출력일 : ").append(java.time.LocalDate.now().toString()).append("</td>");
        html.append("</tr>");
        html.append("</tbody></table>");

        // 2. 결재란 (우측 정렬된 깔끔한 박스)
        if (approvalSteps.isArray() && approvalSteps.size() > 0) {
            html.append(generateApprovalTable(approvalSteps));
        }

// ✅ JSON에서 공휴일 데이터 가져오기
        Set<String> holidays = new HashSet<>();
        JsonNode holidaysNode = data.path("holidays");
        if (holidaysNode.isArray()) {
            for (JsonNode holiday : holidaysNode) {
                holidays.add(holiday.asText());
            }
        }

// generateScheduleTable 호출 시 전달
        html.append(generateScheduleTable(entries, positions, users, daysInMonth, dutyConfig, yearMonth, holidays));

        // 4. 하단 비고
        String remarks = data.path("schedule").path("remarks").asText("");
        html.append("<div class='remarks-box'>");
        html.append("<span class='remarks-label'>※ 비고</span>");
        html.append("<div class='remarks-content'>");
        if (!remarks.isEmpty()) {
            String formatted = escapeHtml(remarks).replace("\n", "<br/>");
            html.append(formatted);
        } else {
            html.append("-");
        }
        html.append("</div></div>");

        html.append("</div></body></html>");
        return html.toString();
    }

    private static String generateApprovalTable(JsonNode steps) {
        if (steps == null || !steps.isArray() || steps.size() == 0) return "";

        StringBuilder sb = new StringBuilder();
        sb.append("<div class='wse-approval-section'>");
        sb.append("<table class='wse-approval-table'>");
        sb.append("<tbody>");

        // 1. 헤더 (담당, 과장, 부장 등)
        sb.append("<tr><th></th>"); // 첫 칸 비움
        for (JsonNode step : steps) {
            sb.append("<th>").append(escapeHtml(step.path("stepName").asText())).append("</th>");
        }
        sb.append("</tr>");

        // 2. 성명 행
        sb.append("<tr><th>성명</th>");
        for (JsonNode step : steps) {
            sb.append("<td>").append(escapeHtml(step.path("name").asText("-"))).append("</td>");
        }
        sb.append("</tr>");

        // 3. 서명 행
        sb.append("<tr><th class='wse-signature-cell'>서명</th>");
        for (JsonNode step : steps) {
            boolean isFinalApproved = step.path("isFinalApproved").asBoolean(false);
            String signatureUrl = step.path("signatureUrl").asText("");

            sb.append("<td class='wse-signature-cell'>");
            if (isFinalApproved && signatureUrl.isEmpty()) {
                sb.append("<span style='color: red; font-weight: bold; font-size: 7pt;'>전결</span>");
            } else if (!signatureUrl.isEmpty()) {
                sb.append("<img src='").append(signatureUrl).append("' />");
            } else {
                sb.append("<span style='color: #ccc;'>-</span>");
            }
            sb.append("</td>");
        }
        sb.append("</tr>");

        // 4. 일자 행
        sb.append("<tr><th>일자</th>");
        for (JsonNode step : steps) {
            String signedAt = step.path("signedAt").asText("");
            String displayDate = "-";
            if (!signedAt.isEmpty() && signedAt.length() >= 10) {
                displayDate = signedAt.substring(2, 10).replace("-", "."); // "24.01.12" 형식으로 압축
            }
            sb.append("<td class='wse-date-cell'>").append(displayDate).append("</td>");
        }
        sb.append("</tr>");

        sb.append("</tbody></table></div>");
        return sb.toString();
    }

    private static String generateScheduleTable(JsonNode entries, JsonNode positions,
                                                JsonNode users, int daysInMonth,
                                                JsonNode dutyConfig, String yearMonth,
                                                Set<String> holidays) {
        StringBuilder html = new StringBuilder();
        html.append("<table class='grid-table'><thead>");

        // 첫 번째 헤더 행
        html.append("<tr>");
        html.append("<th rowspan='2' class='col-no'>No</th>");
        html.append("<th rowspan='2' class='col-pos'>직책</th>");
        html.append("<th rowspan='2' class='col-name'>성명</th>");

        // 날짜 헤더
        for (int day = 1; day <= daysInMonth; day++) {
            String dayOfWeek = getDayOfWeek(yearMonth, day);
            // ✅ 수정: 공휴일 정보를 getDayClass에 전달
            String dayClass = getDayClass(yearMonth, day, dayOfWeek, holidays);
            html.append("<th rowspan='2' class='col-day ").append(dayClass).append("'>");
            html.append(day).append("<br/><span class='dow'>").append(dayOfWeek).append("</span>");
            html.append("</th>");
        }

        // 근무 통계 및 휴가 헤더
        html.append(generateDutyHeaders(dutyConfig));
        html.append("<th colspan='3' class='col-stats-group'>휴가</th>");
        html.append("<th rowspan='2' class='col-remark'>비고</th>");
        html.append("</tr>");

        // 두 번째 헤더 행 (서브 헤더)
        html.append("<tr>");
        html.append(generateDutySubHeaders(dutyConfig));
        html.append("<th class='col-stat-sub'>총 휴가 수</th>"); // 총 휴가를 잔여로 표현하기도 함, 데이터에 맞게 수정
        html.append("<th class='col-stat-sub'>이달 사용 수</th>");
        html.append("<th class='col-stat-sub'>사용 총계</th>");
        html.append("</tr></thead><tbody>");

        int idx = 0;
        for (JsonNode entry : entries) {
            idx++;
            html.append("<tr>");
            html.append("<td class='center'>").append(idx).append("</td>");

            Long positionId = entry.path("positionId").asLong(-1L);
            html.append("<td class='center'>").append(escapeHtml(findPositionName(positions, positionId))).append("</td>");

            String userId = entry.path("userId").asText("");
            String userName = users.path(userId).path("userName").asText(userId);
            html.append("<td class='center'>").append(escapeHtml(userName)).append("</td>");

            // 근무 데이터
            JsonNode workData = getWorkData(entry);
            boolean isTextMode = workData != null && "longText".equals(workData.path("rowType").asText(""));

            if (isTextMode) {
                String longText = workData.path("longTextValue").asText("");
                html.append("<td colspan='").append(daysInMonth).append("' class='long-text'>")
                        .append(escapeHtml(longText)).append("</td>");
            } else {
                // ✅ 데이터 셀 (공휴일 정보 전달)
                for (int day = 1; day <= daysInMonth; day++) {
                    String value = "";
                    if (workData != null) value = workData.path(String.valueOf(day)).asText("");

                    String dayOfWeek = getDayOfWeek(yearMonth, day);
                    // ✅ 수정: 공휴일 정보를 getDayClass에 전달
                    String cellClass = getDayClass(yearMonth, day, dayOfWeek, holidays);

                    html.append("<td class='center ").append(cellClass).append("'>")
                            .append(escapeHtml(value)).append("</td>");
                }
            }

            // 통계
            html.append(generateDutyCells(entry, dutyConfig));

            // 휴가
            html.append("<td class='center bg-light'>").append(formatDouble(entry.path("vacationTotal").asDouble())).append("</td>");
            html.append("<td class='center bg-light'>").append(formatDouble(entry.path("vacationUsedThisMonth").asDouble())).append("</td>");
            html.append("<td class='center bg-light'>").append(formatDouble(entry.path("vacationUsedTotal").asDouble())).append("</td>");

            // 비고
            html.append("<td class='remark-cell'>").append(escapeHtml(entry.path("remarks").asText(""))).append("</td>");
            html.append("</tr>");
        }
        html.append("</tbody></table>");
        return html.toString();
    }

    // CSS 생성 (엑셀 스타일의 핵심)
    private static String loadExcelStyleCss(int entryCount, int daysInMonth) {
        // A4 가로 폭에 맞춤 (약 297mm)
        // 폰트 크기 및 셀 너비 자동 조정 로직
        double baseFontSize = (entryCount > 20) ? 6.5 : 8.0;
        double cellPadding = 2.0;

        return String.format("""
            @page { size: A4 landscape; margin: 10mm; }
            body { font-family: 'Malgun Gothic', sans-serif; font-size: %.1fpt; color: #000; margin: 0; padding: 0; }
            table { border-collapse: collapse; width: 100%%; table-layout: fixed; }
            
            /* 헤더 테이블 */
            .header-table { margin-bottom: 10px; border: none; }
            .header-table td { border: none; padding: 2px; }
            .title-cell { font-size: 18pt; font-weight: bold; text-align: center; padding: 10px 0; border-bottom: 2px solid #000 !important; }
            .info-left { text-align: left; font-size: 10pt; }
            .info-right { text-align: right; font-size: 9pt; }
            
            /* 결재란 */
            .approval-wrapper { text-align: right; margin-bottom: 10px; }
            .approval-table { width: auto; display: inline-table; border: 1px solid #000; margin-left: auto; }
            .approval-table th, .approval-table td { border: 1px solid #000; text-align: center; vertical-align: middle; padding: 2px; }
            .approval-table th { background-color: #f2f2f2; font-weight: normal; font-size: 8pt; height: 18px; min-width: 50px; }
            .approval-header-col { width: 20px; background-color: #f2f2f2; }
            .signature-cell { height: 40px; }
            .signature-cell img { max-height: 35px; max-width: 45px; }
            .stamp-text { color: #cc0000; font-weight: bold; font-size: 9pt; border: 1px solid #cc0000; padding: 2px; border-radius: 2px; }
            .approver-name { height: 15px; font-size: 7pt; }
            
            /* 메인 데이터 그리드 (엑셀 느낌) */
            .grid-table { border: 1px solid #000; }
            .grid-table th, .grid-table td {
                border: 0.5pt solid #000; /* 엑셀의 얇은 검은 선 */
                padding: %.1fpx;
                overflow: hidden;
                white-space: nowrap;
                height: 16px;
            }
            .grid-table thead th {
                background-color: #e6e6e6; /* 엑셀 헤더 배경색 */
                text-align: center;
                font-weight: bold;
                vertical-align: middle;
            }
            
            /* 컬럼 스타일 */
            .col-no { width: 25px; }
            .col-pos { width: 50px; }
            .col-name { width: 50px; }
            .col-day { width: auto; font-size: 7pt; line-height: 1.1; }
            .col-stats-group { width: 60px; }
            .col-stat-sub {
                width: 22px;
                font-size: 6.5pt;
                letter-spacing: -0.5px;
            }
            .col-remark { width: 80px; }
            
            /* 셀 내용 스타일 */
            .center { text-align: center; }
            .bg-light { background-color: #f9f9f9; }
            .weekend { background-color: #fff0f0; color: #d00; } /* 일요일/공휴일 붉은색 처리 */
            .saturday { background-color: #f0f8ff; color: #00d; }
            .holiday { background-color: #fff0f0; color: #d00; font-weight: bold; }
            .holiday-priority { background-color: #ffebee; color: #d00; font-weight: bold; }
            .saturday-duty { background-color: #e6f7ff; }
            .holiday-sunday-duty { background-color: #fff0f0; }
            .dow { font-size: 6pt; font-weight: normal; }
            .remark-cell { text-align: left; font-size: 6pt; white-space: normal; line-height: 1.1; }
            .long-text { text-align: center; background-color: #fffde7; }
            
            /* 하단 비고 박스 */
            .remarks-box { margin-top: 10px; border: 1px solid #000; padding: 5px; font-size: 8pt; }
            .remarks-label { font-weight: bold; display: block; margin-bottom: 3px; text-decoration: underline; }
            .remarks-content { white-space: pre-wrap; line-height: 1.4; }
            
                /* 결재 섹션: 전체 너비를 쓰되 내용은 오른쪽으로 정렬 */
                        .wse-approval-section {
                            width: 100%%;
                            margin-bottom: 10px;
                            display: block;
                        }
                       
                        /* 결재 테이블: 필요한 만큼만 너비를 차지하고 오른쪽으로 배치 */
                        .wse-approval-table {
                            border-collapse: collapse;
                            margin-left: auto; /* 핵심: 오른쪽 정렬 */
                            margin-right: 0;
                            width: auto;       /* 너비 자동 (내부 셀 너비 합계) */
                            table-layout: fixed; /* 셀 너비 고정 */
                            border: 0.5pt solid #000;
                        }
                       
                        /* 각 셀의 너비를 65px로 고정 (성명/서명/일자 공통) */
                        .wse-approval-table th, .wse-approval-table td {
                            border: 0.5pt solid #000;
                            width: 65px;
                            min-width: 65px;
                            max-width: 65px;
                            text-align: center;
                            vertical-align: middle;
                            font-size: 7.5pt;
                            padding: 2px;
                            overflow: hidden;
                        }
                       
                        /* 첫 번째 "성명", "서명", "일자" 타이틀 칸만 너비를 약간 줄임 */
                        .wse-approval-table th:first-child,
                        .wse-approval-table td:first-child {
                            width: 35px;
                            min-width: 35px;
                            background-color: #f8f9fa;
                        }

                        .wse-approval-table th {
                            background-color: #f8f9fa;
                            height: 18px;
                            font-weight: bold;
                        }
                       
                        /* 서명란 높이 조절 (너무 크지 않게 45px 정도) */
                        .wse-signature-cell {
                            height: 45px !important;
                        }
                       
                        .wse-signature-cell img {
                            max-width: 55px;
                            max-height: 40px;
                            display: block;
                            margin: 0 auto;
                        }
                       
                        .wse-date-cell {
                            height: 15px;
                            font-size: 6.5pt !important;
                            letter-spacing: -0.5px;
                        }
        """, baseFontSize, cellPadding);
    }

    // 유틸리티 메서드들
    private static JsonNode getWorkData(JsonNode entry) {
        if (entry.has("workData") && entry.get("workData").isObject()) return entry.get("workData");
        if (entry.has("workDataJson")) {
            try { return objectMapper.readTree(entry.path("workDataJson").asText("{}")); }
            catch (Exception e) { return null; }
        }
        return null;
    }

    private static String getDayClass(String yearMonth, int day, String dayOfWeek, Set<String> holidays) {
        String[] parts = yearMonth.split("-");
        int month = Integer.parseInt(parts[1]);
        String monthDay = month + "-" + day;

        boolean isHoliday = holidays.contains(monthDay);
        boolean isSaturday = "토".equals(dayOfWeek);
        boolean isSunday = "일".equals(dayOfWeek);

        // ✅ 토요일 + 공휴일 = 공휴일 우선 (빨강)
        if (isSaturday && isHoliday) {
            return "holiday-priority";
        }

        // ✅ 공휴일 또는 일요일 (빨강)
        if (isHoliday || isSunday) {
            return "holiday";
        }

        // ✅ 토요일 (파랑)
        if (isSaturday) {
            return "saturday";
        }

        return "";
    }

    private static String formatDouble(double val) {
        if (val == (long) val) {
            return String.format("%d", (long) val);
        }
        return String.format("%.1f", val);
    }

    private static String generateDutyHeaders(JsonNode dutyConfig) {
        String mode = dutyConfig.path("dutyMode").asText("D_SHIFT");

        if ("NIGHT_SHIFT".equals(mode)) {
            String displayName = dutyConfig.path("displayName").asText("나이트");
            return "<th colspan='3'>" + escapeHtml(displayName) + "</th><th rowspan='2' class='col-stat-sub'>OFF</th>";
        } else {
            // 당직(D_SHIFT) 모드에서 사용 중인 설정 개수만큼 colspan 지정
            int colSpan = 0;
            if (dutyConfig.path("useWeekday").asBoolean()) colSpan++;
            if (dutyConfig.path("useFriday").asBoolean()) colSpan++;
            if (dutyConfig.path("useSaturday").asBoolean()) colSpan++;
            if (dutyConfig.path("useHolidaySunday").asBoolean()) colSpan++;

            // 만약 설정이 하나도 없다면 최소 1칸 확보
            if (colSpan == 0) colSpan = 1;

            return "<th colspan='" + colSpan + "'>" + escapeHtml(dutyConfig.path("displayName").asText("당직")) + "</th>";
        }
    }
    private static String generateDutySubHeaders(JsonNode dutyConfig) {
        boolean isNightMode = dutyConfig == null || "NIGHT_SHIFT".equals(dutyConfig.path("dutyMode").asText("NIGHT_SHIFT"));
        if (isNightMode) {
            return "<th class='col-stat-sub'>의무</th><th class='col-stat-sub'>실제</th><th class='col-stat-sub'>추가</th>";
        }
        StringBuilder sb = new StringBuilder();
        if (dutyConfig.path("useWeekday").asBoolean()) sb.append("<th class='col-stat-sub'>평일</th>");
        if (dutyConfig.path("useFriday").asBoolean()) sb.append("<th class='col-stat-sub'>금</th>");
        if (dutyConfig.path("useSaturday").asBoolean()) sb.append("<th class='col-stat-sub'>토</th>");
        if (dutyConfig.path("useHolidaySunday").asBoolean()) sb.append("<th class='col-stat-sub'>휴일</th>");
        return sb.toString();
    }

    private static String generateDutyCells(JsonNode entry, JsonNode dutyConfig) {
        String mode = dutyConfig.path("dutyMode").asText("D_SHIFT");

        if ("NIGHT_SHIFT".equals(mode)) {
            int req = entry.path("nightDutyRequired").asInt(0);
            int act = entry.path("nightDutyActual").asInt(0);
            int add = entry.path("nightDutyAdditional").asInt(0);
            int off = entry.path("offCount").asInt(0);

            return String.format("<td class='center'>%d</td><td class='center'>%d</td><td class='center'>%d</td><td class='center'>%d</td>",
                    req, act, add, off);
        } else {
            StringBuilder sb = new StringBuilder();
            try {
                JsonNode detail = objectMapper.readTree(entry.path("dutyDetailJson").asText("{}"));
                if (dutyConfig.path("useWeekday").asBoolean()) sb.append("<td class='center'>").append(detail.path("평일").asInt(0)).append("</td>");
                if (dutyConfig.path("useFriday").asBoolean()) sb.append("<td class='center'>").append(detail.path("금요일").asInt(0)).append("</td>");
                if (dutyConfig.path("useSaturday").asBoolean()) sb.append("<td class='center'>").append(detail.path("토요일").asInt(0)).append("</td>");
                if (dutyConfig.path("useHolidaySunday").asBoolean()) sb.append("<td class='center'>").append(detail.path("공휴일 및 일요일").asInt(0)).append("</td>");

                // 만약 아무 설정도 없다면 빈 칸 방지를 위해 0 하나 출력
                if (sb.length() == 0) sb.append("<td class='center'>0</td>");

            } catch (Exception e) {
                // 에러 시 설정된 개수만큼 0으로 채움
                int cnt = 0;
                if (dutyConfig.path("useWeekday").asBoolean()) cnt++;
                if (dutyConfig.path("useFriday").asBoolean()) cnt++;
                if (dutyConfig.path("useSaturday").asBoolean()) cnt++;
                if (dutyConfig.path("useHolidaySunday").asBoolean()) cnt++;
                if (cnt == 0) cnt = 1;
                for(int i=0; i<cnt; i++) sb.append("<td class='center'>0</td>");
            }
            return sb.toString();
        }
    }

    private static String findPositionName(JsonNode positions, Long positionId) {
        if (positions.isArray()) {
            for (JsonNode p : positions) {
                if (p.path("id").asLong() == positionId) return p.path("positionName").asText();
            }
        }
        return "";
    }

    private static String getDayOfWeek(String yearMonth, int day) {
        try {
            return "월화수목금토일".split("")[YearMonth.parse(yearMonth).atDay(day).getDayOfWeek().getValue() - 1];
        } catch (Exception e) { return ""; }
    }

    private static String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}