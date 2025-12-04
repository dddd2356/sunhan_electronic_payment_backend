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
import java.util.Base64;

@Slf4j
public class WorkSchedulePdfRenderer {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    public static byte[] render(String jsonData) throws IOException {
        JsonNode data = objectMapper.readTree(jsonData);

        // ✅ 엔트리 개수 계산
        int entryCount = data.path("entries").size();

        String htmlContent = generateWorkScheduleHtml(data, entryCount);

        try (ByteArrayOutputStream os = new ByteArrayOutputStream()) {
            PdfRendererBuilder builder = new PdfRendererBuilder();

            // 폰트 로드
            try (InputStream fontStream = WorkSchedulePdfRenderer.class.getClassLoader()
                    .getResourceAsStream("fonts/malgun.ttf")) {
                if (fontStream != null) {
                    byte[] fontBytes = fontStream.readAllBytes();
                    builder.useFont(() -> new ByteArrayInputStream(fontBytes), "Malgun Gothic");
                    log.info("Malgun Gothic font loaded for PDF rendering.");
                } else {
                    log.warn("Malgun Gothic font file not found.");
                }
            }
            builder.useFastMode();
            builder.withHtmlContent(htmlContent, null);
            builder.toStream(os);
            builder.run();
            return os.toByteArray();
        }
    }

    private static String generateWorkScheduleHtml(JsonNode data, int entryCount) {
        int daysInMonth = data.path("daysInMonth").asInt(31);  // ✅ 일수 가져오기
        String css = loadCss(entryCount, daysInMonth);  // ✅ 일수도 전달
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

        html.append("<div class='schedule-container'>");

        // 헤더
        html.append("<div class='schedule-header'>");
        html.append("<div class='header-logo'>");
        String logoDataUri = loadLogoDataUri();
        if (logoDataUri != null) {
            html.append("<img src='").append(logoDataUri).append("' alt='로고' style='width:40px;height:40px;'/>");
        }
        html.append("<span>선한병원</span></div>");
        html.append("<h1 class='schedule-title'>").append(yearMonth.replace("-", "년 ")).append("월 근무현황표</h1>");
        html.append("<div class='header-info'><span>부서: ").append(deptName).append("</span></div>");
        html.append("</div>");

        // 결재란
        html.append(generateApprovalSection(approvalSteps));

        // 근무표 테이블
        html.append(generateScheduleTable(entries, positions, users, daysInMonth, dutyConfig, yearMonth));

        // 하단 비고
        String remarks = data.path("schedule").path("remarks").asText("");
        html.append("<div class='bottom-remarks'>");
        html.append("<label>비고:</label>");
        html.append("<div class='remarks-content'>");
        if (!remarks.isEmpty()) {
            // 줄바꿈을 <br/>로 변환
            String formatted = escapeHtml(remarks)
                    .replace("\r\n", "\n")
                    .replace("\r", "\n")
                    .replace("\n", "<br/>");
            html.append(formatted);
        } else {
            html.append("&#160;"); // 빈 공간 표시
        }
        html.append("</div>");
        html.append("</div>");
        html.append("</div></body></html>");

        return html.toString();
    }

    private static String generateApprovalSection(JsonNode approvalSteps) {
        if (approvalSteps == null || !approvalSteps.isArray() || approvalSteps.size() == 0) {
            return "";
        }

        StringBuilder html = new StringBuilder();
        html.append("<div class='approval-section'>");
        html.append("<table class='approval-table'><tbody>");

        // 헤더 행 (단계명)
        html.append("<tr><th></th>");
        for (JsonNode step : approvalSteps) {
            html.append("<th>").append(escapeHtml(step.path("stepName").asText(""))).append("</th>");
        }
        html.append("</tr>");

        // 성명 행
        html.append("<tr><th>성명</th>");
        for (JsonNode step : approvalSteps) {
            html.append("<td>").append(escapeHtml(step.path("name").asText(""))).append("</td>");
        }
        html.append("</tr>");

        // 서명 행
        html.append("<tr><th>서명</th>");
        for (JsonNode step : approvalSteps) {
            html.append("<td class='signature-cell'>");
            if (step.path("isSigned").asBoolean(false)) {
                String signatureUrl = step.path("signatureUrl").asText(null);
                if (signatureUrl != null && !signatureUrl.isEmpty()) {
                    html.append("<img src='").append(signatureUrl)
                            .append("' alt='서명' style='max-width:80px;max-height:60px;'/>");
                }
            }
            html.append("</td>");
        }
        html.append("</tr>");

        // 일자 행
        html.append("<tr><th>일자</th>");
        for (JsonNode step : approvalSteps) {
            String signedAt = step.path("signedAt").asText("");
            String displayDate = signedAt.isEmpty() ? "-" :
                    signedAt.substring(0, Math.min(10, signedAt.length()));
            html.append("<td>").append(displayDate).append("</td>");
        }
        html.append("</tr>");

        html.append("</tbody></table></div>");
        return html.toString();
    }

    private static String generateScheduleTable(JsonNode entries, JsonNode positions,
                                                JsonNode users, int daysInMonth,
                                                JsonNode dutyConfig, String yearMonth) {
        StringBuilder html = new StringBuilder();

        html.append("<div class='schedule-table-container'>");
        html.append("<table class='schedule-table'><thead>");

        // ✅ 첫 번째 헤더 행
        html.append("<tr>");
        html.append("<th rowspan='2' style='min-width:20px;'>No</th>");
        html.append("<th rowspan='2' style='min-width:40px;'>직책</th>");
        html.append("<th rowspan='2' style='min-width:40px;'>성명</th>");

        // 일자 헤더 (rowspan=2)
        for (int day = 1; day <= daysInMonth; day++) {
            String dayOfWeek = getDayOfWeek(yearMonth, day);
            html.append("<th rowspan='2' class='work-cell' style='min-width:18px;'>");
            html.append("<div>").append(day).append("</div>");
            html.append("<div style='font-size:5px;'>").append(dayOfWeek).append("</div>");
            html.append("</th>");
        }

        // ✅ 나이트/당직 헤더 (colspan 처리)
        html.append(generateDutyHeaders(dutyConfig));

        // ✅ 휴가 헤더 (colspan=3)
        html.append("<th colspan='3' style='min-width:90px;'>휴가</th>");

        // 비고 (rowspan=2)
        html.append("<th rowspan='2' style='min-width:60px;'>비고</th>");
        html.append("</tr>");

        // ✅ 두 번째 헤더 행 (서브헤더만)
        html.append("<tr>");
        html.append(generateDutySubHeaders(dutyConfig));
        html.append("<th style='min-width:30px;'>총 휴가수</th>");
        html.append("<th style='min-width:30px;'>이달 사용수</th>");
        html.append("<th style='min-width:30px;'>사용 총계</th>");
        html.append("</tr>");

        html.append("</thead><tbody>");

        log.info("📊 총 엔트리 개수: {}", entries.size());

        // 엔트리 데이터
        int idx = 0;
        for (JsonNode entry : entries) {
            idx++;
            html.append("<tr>");
            html.append("<td>").append(idx).append("</td>");

            // 직책
            Long positionId = entry.path("positionId").asLong(-1L);
            String positionName = findPositionName(positions, positionId);
            html.append("<td>").append(escapeHtml(positionName)).append("</td>");

            // 성명
            String userId = entry.path("userId").asText("");
            String userName = users.path(userId).path("userName").asText(userId);
            html.append("<td>").append(escapeHtml(userName)).append("</td>");

            // 근무 데이터
            JsonNode workData = null;
            if (entry.has("workData") && entry.get("workData").isObject()) {
                workData = entry.get("workData");
            } else if (entry.has("workDataJson")) {
                String workDataJsonStr = entry.path("workDataJson").asText("");
                if (!workDataJsonStr.isEmpty()) {
                    try {
                        workData = objectMapper.readTree(workDataJsonStr);
                    } catch (Exception e) {
                        log.error("workDataJson 파싱 실패", e);
                    }
                }
            }

            boolean isTextMode = workData != null &&
                    "longText".equals(workData.path("rowType").asText(""));

            if (isTextMode) {
                String longText = workData.path("longTextValue").asText("");
                String formatted = escapeHtml(longText)
                        .replace("\r\n", "\n")
                        .replace("\r", "\n")
                        .replace("\n", "<br/>");
                html.append("<td class='wse-long-text-cell' colspan='").append(daysInMonth)
                        .append("'>")
                        .append(formatted.isEmpty() ? "&#160;" : formatted)
                        .append("</td>");

            } else {
                for (int day = 1; day <= daysInMonth; day++) {
                    String value = "";
                    if (workData != null && !workData.isNull()) {
                        value = workData.path(String.valueOf(day)).asText("");
                    }
                    html.append("<td class='work-cell'>").append(escapeHtml(value)).append("</td>");
                }
            }

            // 통계
            html.append(generateDutyCells(entry, dutyConfig));

            // 휴가
            html.append("<td><span style='display:inline-block; white-space:nowrap;'>")
                    .append(String.format("%.1f", entry.path("vacationTotal").asDouble(0.0)))
                    .append("</span></td>");
            html.append("<td><span style='display:inline-block; white-space:nowrap;'>")
                    .append(String.format("%.1f", entry.path("vacationUsedThisMonth").asDouble(0.0)))
                    .append("</span></td>");
            html.append("<td><span style='display:inline-block; white-space:nowrap;'>")
                    .append(String.format("%.1f", entry.path("vacationUsedTotal").asDouble(0.0)))
                    .append("</span></td>");
            // 비고
            html.append("<td style='font-size:6px; word-break:break-all;'>")
                    .append(escapeHtml(entry.path("remarks").asText("")))
                    .append("</td>");

            html.append("</tr>");
        }

        html.append("</tbody></table></div>");
        return html.toString();
    }

    private static String generateDutyHeaders(JsonNode dutyConfig) {
        if (dutyConfig == null || dutyConfig.isMissingNode()) {
            return "<th colspan='3' style='min-width:90px;'>나이트</th>" +
                    "<th rowspan='2' style='min-width:30px;'>OFF 개수</th>";
        }

        String dutyMode = dutyConfig.path("dutyMode").asText("NIGHT_SHIFT");
        if ("NIGHT_SHIFT".equals(dutyMode)) {
            String displayName = dutyConfig.path("displayName").asText("나이트");
            return "<th colspan='3' style='min-width:90px;'>" + escapeHtml(displayName) + "</th>" +
                    "<th rowspan='2' style='min-width:30px;'>OFF 개수</th>";
        } else {
            int categoryCount = 0;
            if (dutyConfig.path("useWeekday").asBoolean(false)) categoryCount++;
            if (dutyConfig.path("useFriday").asBoolean(false)) categoryCount++;
            if (dutyConfig.path("useSaturday").asBoolean(false)) categoryCount++;
            if (dutyConfig.path("useHolidaySunday").asBoolean(false)) categoryCount++;

            String displayName = dutyConfig.path("displayName").asText("당직");
            return "<th colspan='" + categoryCount + "' style='min-width:90px;'>" +
                    escapeHtml(displayName) + "</th>";
        }
    }

    private static String generateDutySubHeaders(JsonNode dutyConfig) {
        if (dutyConfig == null || dutyConfig.isMissingNode() ||
                "NIGHT_SHIFT".equals(dutyConfig.path("dutyMode").asText("NIGHT_SHIFT"))) {
            return "<th style='min-width:30px;'>의무 개수</th>" +
                    "<th style='min-width:30px;'>실제 개수</th>" +
                    "<th style='min-width:30px;'>추가 개수</th>";
        }

        StringBuilder html = new StringBuilder();
        if (dutyConfig.path("useWeekday").asBoolean(false)) {
            html.append("<th style='min-width:30px;'>평일</th>");
        }
        if (dutyConfig.path("useFriday").asBoolean(false)) {
            html.append("<th style='min-width:30px;'>금요일</th>");
        }
        if (dutyConfig.path("useSaturday").asBoolean(false)) {
            html.append("<th style='min-width:30px;'>토요일</th>");
        }
        if (dutyConfig.path("useHolidaySunday").asBoolean(false)) {
            html.append("<th style='min-width:30px;'>공휴일 및 일요일</th>");
        }

        return html.toString();
    }

    private static String generateDutyCells(JsonNode entry, JsonNode dutyConfig) {
        if (dutyConfig == null || dutyConfig.isMissingNode() ||
                "NIGHT_SHIFT".equals(dutyConfig.path("dutyMode").asText("NIGHT_SHIFT"))) {
            int required = entry.path("nightDutyRequired").asInt(0);
            int actual = entry.path("nightDutyActual").asInt(0);
            int additional = entry.path("nightDutyAdditional").asInt(0);
            int offCount = entry.path("offCount").asInt(0);

            return "<td><span style='display:inline-block;'>" + required + "</span></td>" +
                    "<td><span style='display:inline-block;'>" + actual + "</span></td>" +
                    "<td><span style='display:inline-block;'>" + (required == actual ? "." : additional) + "</span></td>" +
                    "<td><span style='display:inline-block;'>" + offCount + "</span></td>";
        }

        StringBuilder html = new StringBuilder();
        try {
            JsonNode detailJson = objectMapper.readTree(entry.path("dutyDetailJson").asText("{}"));
            if (dutyConfig.path("useWeekday").asBoolean(false)) {
                html.append("<td>").append(detailJson.path("평일").asInt(0)).append("</td>");
            }
            if (dutyConfig.path("useFriday").asBoolean(false)) {
                html.append("<td>").append(detailJson.path("금요일").asInt(0)).append("</td>");
            }
            if (dutyConfig.path("useSaturday").asBoolean(false)) {
                html.append("<td>").append(detailJson.path("토요일").asInt(0)).append("</td>");
            }
            if (dutyConfig.path("useHolidaySunday").asBoolean(false)) {
                html.append("<td>").append(detailJson.path("공휴일 및 일요일").asInt(0)).append("</td>");
            }
        } catch (Exception e) {
            log.error("dutyDetailJson 파싱 실패", e);
        }

        return html.toString();
    }

    private static String findPositionName(JsonNode positions, Long positionId) {
        if (positionId == null || positionId < 0 || !positions.isArray()) return "-";

        for (JsonNode pos : positions) {
            if (pos.path("id").asLong(-1L) == positionId) {
                return pos.path("positionName").asText("-");
            }
        }
        return "-";
    }

    private static String getDayOfWeek(String yearMonth, int day) {
        try {
            YearMonth ym = YearMonth.parse(yearMonth);
            java.time.LocalDate date = ym.atDay(day);
            String[] days = {"월", "화", "수", "목", "금", "토", "일"};
            return days[date.getDayOfWeek().getValue() - 1];
        } catch (Exception e) {
            return "";
        }
    }

    private static String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    private static String loadLogoDataUri() {
        try (InputStream is = WorkSchedulePdfRenderer.class.getClassLoader()
                .getResourceAsStream("images/newExecution.png")) {
            if (is == null) {
                log.warn("로고 파일을 찾을 수 없습니다.");
                return null;
            }
            byte[] bytes = is.readAllBytes();
            return "data:image/png;base64," + Base64.getEncoder().encodeToString(bytes);
        } catch (IOException e) {
            log.warn("로고 읽기 실패", e);
            return null;
        }
    }

    private static String loadCss(int entryCount, int daysInMonth) {
        // A4 가로: 297mm, 마진 제외: ~290mm

        double baseFontSize;
        double headerFontSize;
        double cellPadding;

        // [✅ 수정 1] 통계 컬럼(나이트3 + OFF1 + 휴가3 = 7개)에 넉넉한 고정 너비 부여
        double statsColumnWidth = 9.0; // 9mm로 고정 (글자가 안 짤리도록)
        int statsColumnCount = 7;
        double totalStatsWidth = statsColumnWidth * statsColumnCount; // 약 63mm

        // 고정 칼럼 너비 (No, 직책, 성명, 비고)
        double fixedColumnsWidth = 5 + 10 + 12 + 40; // 67mm

        // [✅ 수정 2] 날짜 셀들에 사용할 수 있는 남은 너비 계산
        // 전체 폭(285mm) - 고정칼럼들 - 통계칼럼들
        double availableForDateCells = 285 - fixedColumnsWidth - totalStatsWidth; // 약 155mm

        // [✅ 수정 3] 날짜 셀 너비 계산 (일자 개수만큼 나눔)
        double uniformCellWidth = availableForDateCells / daysInMonth;

        // 최소/최대 제한 (너무 작아지지 않게 방어 코드)
        if (uniformCellWidth < 3.5) uniformCellWidth = 3.5;

        // 인원 수에 따른 폰트 크기 조정 (기존 유지)
        if (entryCount <= 5) {
            baseFontSize = 7.5;
            headerFontSize = 13.0;
            cellPadding = 3.0;
        } else if (entryCount <= 10) {
            baseFontSize = 6.5;
            headerFontSize = 11.0;
            cellPadding = 2.5;
        } else if (entryCount <= 15) {
            baseFontSize = 6.0;
            headerFontSize = 10.0;
            cellPadding = 2.0;
        } else if (entryCount <= 20) {
            baseFontSize = 5.5;
            headerFontSize = 9.0;
            cellPadding = 1.5;
        } else {
            baseFontSize = 5.0;
            headerFontSize = 8.5;
            cellPadding = 1.0;
        }

        log.info("📐 PDF 레이아웃 계산: 날짜 셀 너비={:.2f}mm, 통계 셀 너비={:.2f}mm", uniformCellWidth, statsColumnWidth);

        return String.format("""
                    @page {
                        size: A4 landscape;
                        margin: 2mm 1mm;
                    }
                         
                    .schedule-table tbody tr {
                        page-break-inside: avoid;
                    }
                        
                    .schedule-header,
                    .approval-section {
                        page-break-after: avoid;
                    }
                       
                    .bottom-remarks {
                        page-break-before: avoid;
                        margin-top: 2mm;
                    }
                    
                    .schedule-table tbody tr:nth-child(20) {
                        page-break-after: %s;
                    }
                    
                    * {
                        box-sizing: border-box;
                    }
                    body {
                        font-family: 'Malgun Gothic', Arial, sans-serif;
                        font-size: %.1fpt;
                        margin: 0;
                        padding: 0;
                        line-height: 1.1;
                    }
                    .schedule-container {
                        width: 100%%;
                        max-width: 295mm;
                    }
                    .schedule-header {
                        display: table;
                        width: 100%%;
                        margin-bottom: 1mm;
                        padding-bottom: 1mm;
                        border-bottom: 0.5pt solid #000;
                    }
                    .header-logo {
                        display: table-cell;
                        vertical-align: middle;
                        width: 60mm;
                    }
                    .header-logo img {
                        width: 7mm;
                        height: 7mm;
                        vertical-align: middle;
                        margin-right: 2mm;
                    }
                    .header-logo span {
                        font-size: %.1fpt;
                        font-weight: bold;
                        vertical-align: middle;
                    }
                    .schedule-title {
                        display: table-cell;
                        font-size: %.1fpt;
                        font-weight: bold;
                        margin: 0;
                        text-align: center;
                        vertical-align: middle;
                    }
                    .header-info {
                        display: table-cell;
                        font-size: %.1fpt;
                        text-align: right;
                        vertical-align: middle;
                        width: 60mm;
                    }
                    .approval-section {
                        margin: 1mm 0;
                        text-align: right;
                    }
                    .approval-table {
                        border-collapse: collapse;
                        font-size: %.1fpt;
                        margin-bottom: 1mm;
                        display: inline-table;
                    }
                    .approval-table th, .approval-table td {
                        border: 0.5pt solid #000;
                        padding: 0.5mm 1.5mm;
                        text-align: center;
                        min-width: 10mm;
                    }
                    .approval-table th {
                        background-color: #f0f0f0;
                        font-weight: bold;
                    }
                    .signature-cell {
                        height: 10mm;
                    }
                    .signature-cell img {
                        max-width: 18mm;
                        max-height: 9mm;
                    }
                    .schedule-table-container {
                        width: 100%%;
                    }
                    .schedule-table {
                        width: 100%%;
                        border-collapse: collapse;
                        font-size: %.1fpt;
                        table-layout: fixed;
                    }
                    .schedule-table th, .schedule-table td {
                        border: 0.5pt solid #333;
                        padding: %.1fmm;
                        text-align: center;
                        word-wrap: break-word;
                        line-height: 1.0;
                        overflow: hidden;
                        text-overflow: clip;
                    }
                    .schedule-table thead th {
                        background-color: #f0f0f0;
                        font-weight: bold;
                        font-size: %.1fpt;
                        padding: 0.5mm;
                    }
                    /* 고정 컬럼 너비 설정 */
                    .schedule-table th:nth-child(1), .schedule-table td:nth-child(1) { width: 5mm; }
                    .schedule-table th:nth-child(2), .schedule-table td:nth-child(2) { width: 10mm; }
                    .schedule-table th:nth-child(3), .schedule-table td:nth-child(3) { 
                        width: 15mm; 
                        white-space: nowrap; 
                    }
                    
                    /* [✅ 수정 4] 날짜 셀 클래스 (.work-cell) */
                    .work-cell {
                        width: %.2fmm !important;
                        font-size: %.1fpt;
                        font-weight: bold;
                        white-space: nowrap;
                        overflow: hidden;
                        padding: 0.2mm !important;
                    }
                    
                    /* [✅ 수정 5] 통계 컬럼들 (뒤에서 8번째부터 끝에서 2번째까지) */
                    /* 나이트(3) + OFF(1) + 휴가(3) = 7개 컬럼 */
                    .schedule-table td:nth-last-child(-n+8):not(:last-child) {
                        width: %.2fmm !important; /* 여기를 statsColumnWidth로 설정 */
                        font-size: %.1fpt;
                        white-space: nowrap; /* 줄바꿈 방지 */
                        overflow: hidden;
                        text-align: center; /* 중앙 정렬 */
                        vertical-align: middle; /* 세로 중앙 정렬 */
                        word-break: keep-all; /* 단어 분리 방지 */
                    }
                    
                    /* 비고 컬럼 (마지막) */
                    .schedule-table td:last-child {
                        width: 40mm;
                        font-size: %.1fpt;
                        word-break: break-all;
                        white-space: normal;
                    }
                    
                    /* 기타 스타일 */
                    .wse-long-text-cell {
                        text-align: center !important;
                        padding: 2mm !important;
                        font-size: %.1fpt;
                        vertical-align: middle !important;
                        line-height: 1.3;
                        white-space: normal;
                        word-break: break-all;
                    }
                        .bottom-remarks {
                              margin-top: 2mm;
                              padding: 2mm;
                              border: 0.5pt solid #999;
                              page-break-inside: avoid;
                              min-height: 15mm; /* 최소 높이 보장 */
                              background-color: #fff; /* 배경색 명시 */
                          }
                          .bottom-remarks label {
                              font-weight: bold;
                              display: block;
                              margin-bottom: 1mm; /* 간격 증가 */
                              font-size: %.1fpt;
                          }
                          .remarks-content {
                              min-height: 10mm; /* 최소 높이 */
                              white-space: pre-wrap;
                              font-size: %.1fpt;
                              line-height: 1.4; /* 줄간격 증가 */
                              padding: 1mm; /* 내부 여백 */
                          }
                    @media print {
                        body {
                            -webkit-print-color-adjust: exact;
                            print-color-adjust: exact;
                        }
                    }
                        """,
                entryCount > 25 ? "always" : "auto",
                baseFontSize,           // body font-size
                baseFontSize + 1,       // header-logo span
                headerFontSize,         // schedule-title
                baseFontSize,           // header-info
                baseFontSize - 1,       // approval-table
                baseFontSize,           // schedule-table
                cellPadding,            // th, td padding
                baseFontSize - 0.5,     // thead th

                uniformCellWidth,       // [1] 날짜 셀 너비
                baseFontSize - 1,       // 날짜 셀 폰트

                statsColumnWidth,       // [2] 통계 칼럼 너비 (넓게 고정)
                baseFontSize - 1,       // 통계 칼럼 폰트

                baseFontSize - 1,       // 비고 칼럼 폰트
                baseFontSize,           // long-text-cell
                baseFontSize + 1,       // remarks label
                baseFontSize           // remarks-content
        );
    }
}