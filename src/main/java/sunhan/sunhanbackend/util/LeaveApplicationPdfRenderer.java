package sunhan.sunhanbackend.util;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

@Slf4j
public class LeaveApplicationPdfRenderer {

    private static final ObjectMapper objectMapper = new ObjectMapper();
    // JobLevel 매핑 메서드 추가 (여러 필드명 시도 및 fallback 처리)
    private static String getJobLevelTitle(JsonNode data, String jobLevelFieldName) {
        log.info("=== getJobLevelTitle 디버깅 ===");
        log.info("요청된 필드명: {}", jobLevelFieldName);

        // 여러 가능한 필드명들을 시도
        String[] possibleFields = {
                jobLevelFieldName,
                "jobLevel",
                "job_level",
                "position_level",
                "level",
                "applicantPosition",  // DTO에서 jobLevel을 applicantPosition에 넣는 경우
                "substitutePosition"   // DTO에서 jobLevel을 substitutePosition에 넣는 경우
        };

        JsonNode jobLevelNode = null;
        String usedField = null;

        for (String field : possibleFields) {
            jobLevelNode = data.path(field);
            if (jobLevelNode != null && !jobLevelNode.isMissingNode() && !jobLevelNode.isNull()) {
                usedField = field;
                log.info("발견된 필드: {} = {}", field, jobLevelNode);
                break;
            }
        }

        if (jobLevelNode == null || jobLevelNode.isMissingNode() || jobLevelNode.isNull()) {
            log.warn("jobLevel 관련 필드를 찾을 수 없음. 시도한 필드: {}", String.join(", ", possibleFields));
            return "사원"; // 기본값
        }

        // 숫자 또는 문자열 처리
        int jobLevel = -1;
        if (jobLevelNode.isNumber()) {
            jobLevel = jobLevelNode.asInt(-1);
        } else if (jobLevelNode.isTextual()) {
            String jobLevelStr = jobLevelNode.asText("");
            try {
                jobLevel = Integer.parseInt(jobLevelStr);
            } catch (NumberFormatException e) {
                log.warn("jobLevel 문자열을 숫자로 변환할 수 없음: {} (필드: {})", jobLevelStr, usedField);
                return "사원"; // 변환 실패 시 기본값
            }
        }

        log.info("파싱된 jobLevel: {} (필드: {})", jobLevel, usedField);

        String result = switch (jobLevel) {
            case 0 -> "사원";
            case 1 -> "부서장";
            case 2 -> "진료지원센터장";
            case 3 -> "원장";
            case 4 -> "행정원장";
            case 5 -> "대표원장";
            default -> {
                log.warn("알 수 없는 jobLevel: {} (필드: {})", jobLevel, usedField);
                yield "사원"; // 최종 기본값
            }
        };

        log.info("최종 결과: {}", result);
        log.info("=== getJobLevelTitle 디버깅 끝 ===");
        return result;
    }

    private static String formatApplicationDate(JsonNode data) {
        String applicationDate = data.path("applicationDate").asText("");
        log.info("=== formatApplicationDate 디버깅 ===");
        log.info("원본 applicationDate: '{}'", applicationDate);

        if (applicationDate.isEmpty()) {
            log.warn("applicationDate가 비어있음");
            return "";
        }

        try {
            // 다양한 날짜 형식 처리
            if (applicationDate.length() >= 10) {
                String dateOnly = applicationDate.substring(0, 10);
                log.info("추출된 dateOnly: '{}'", dateOnly);

                LocalDate date = LocalDate.parse(dateOnly);
                String formatted = date.format(DateTimeFormatter.ofPattern("yyyy년 MM월 dd일"));
                log.info("포맷된 결과: '{}'", formatted);
                return formatted;
            }
            log.warn("applicationDate 길이가 10자 미만: {}", applicationDate.length());
            return "";
        } catch (Exception e) {
            log.error("applicationDate 파싱 실패: {}", applicationDate, e);
            return "";
        }
    }

    public static byte[] render(String jsonData) throws IOException {
        JsonNode data = objectMapper.readTree(jsonData);
        String htmlContent = generateLeaveApplicationHtml(data);

        try (ByteArrayOutputStream os = new ByteArrayOutputStream()) {
            PdfRendererBuilder builder = new PdfRendererBuilder();
            try (InputStream fontStream = LeaveApplicationPdfRenderer.class.getClassLoader().getResourceAsStream("fonts/malgun.ttf")) {
                if (fontStream != null) {
                    byte[] fontBytes = fontStream.readAllBytes();
                    builder.useFont(() -> new ByteArrayInputStream(fontBytes), "Malgun Gothic");
                    log.info("Malgun Gothic font loaded for PDF rendering.");
                } else {
                    log.warn("Malgun Gothic font file (fonts/malgun.ttf) not found. PDF might not render Korean correctly.");
                }
            }

            builder.withHtmlContent(htmlContent, null);
            builder.toStream(os);
            builder.run();
            return os.toByteArray();
        }
    }

    private static String loadCss() {
        return """
        /* A4 페이지 설정 */
        @page { size: A4; margin: 15mm; }
        
        /* 기본 폰트 및 줄 간격 설정 */
        body { font-family: 'Malgun Gothic', Arial, sans-serif; font-size: 10px; line-height: 1.6; }
        
        /* 전체 컨테이너 */
        .leave-application-container { max-width: 180mm; margin: 0 auto; }
        
        /* 상단 공통서식지 헤더 */
        .common-list {
            border: 2px solid #FFFFFF; /* PDF에서는 흰색 대신 검은색 테두리 */
            padding: 5px;
            margin: 10px 0;
            background-color: #333333; /* PDF에서는 진한 회색 대신 연한 회색 */
            color: #FFFFFF; /* PDF에서는 흰색 대신 검은색 텍스트 */
            width: 180px;
            text-align: center;
            font-size: 11px;
            font-weight: bold;
            margin-left: 0;
        }
        
        /* [수정] 헤더 전체를 오른쪽으로 보내기 위한 컨테이너 */
        .header-block-container { text-align: right; }
        
        /* [추가] 헤더 레이아웃용 테이블 */
        .header-layout-table { display: inline-table; width: 100%; border-spacing: 0; vertical-align: bottom; }
        .header-title-cell { vertical-align: bottom; width: 50%; text-align: left; }
        .header-approval-cell { vertical-align: bottom; text-align: right; width: 50%; }

        /* 제목 스타일 */
        .leave-application-title { text-align: left !important;  font-size: 26px; font-weight: bold; margin: 0 0 10px 0; padding-left: 50px; }
        
        /* [수정] 결재란 컨테이너 크기 축소 */
        .table-container { width: 400px; }
        
        /* [수정] 결재란 테이블 전체 크기 및 폰트 축소 */
        .approval-table { width: 80%; border-collapse: collapse; border: 1px solid #000; font-size: 9px; margin-left: auto; }
        
        /* [수정] 결재란 헤더 셀 크기 축소 */
        .approval-header-cell {
            border: 1px solid #000; background-color: #f2f2f2;
            width: 30px; text-align: center; vertical-align: middle;
            font-weight: bold; font-size: 12px; padding: 6px 0;
        }
        
        .position-header-cell, .approval-group-header {
            border: 1px solid #000; background-color: #f2f2f2;
            text-align: center; font-weight: bold;
            font-size: 10px; padding: 3px;
        }
        
        /* [수정] 서명 셀 높이 고정 및 크기 제한 */
        .signature-cell {
            border: 1px solid #000; text-align: center;
            vertical-align: middle; height: 55px;
            width: 60px; /* 너비 고정 */
            min-height: 55px; max-height: 55px;
            min-width: 60px; max-width: 60px;
            overflow: hidden; /* 넘치는 내용 숨김 */
            box-sizing: border-box;
        }
                
        /* [수정] 날짜 셀 높이 축소 */
        .slash-cell {
            border: 1px solid #000; height: 22px;
            text-align: center; font-size: 9px;
            padding: 2px; vertical-align: middle;
        }
        
        /* [수정] 본문 상단 여백 추가하여 아래로 내림 */
        .form-body {
            border: 2px solid #000;
            margin-top: 40px;
        }
        
        .main-table { width: 100%; border-collapse: collapse; margin-bottom: 25px; }
        
        .main-header {
            border-right: 1px solid #000; border-bottom: 1px solid #000;
            background-color: #f2f2f2; text-align: center; vertical-align: middle;
            font-weight: bold; font-size: 11px;
            padding: 12px 6px; /* 세로 패딩 증가 */
            width: 70px;
        }
        
        .sub-header {
            border-left: 1px solid #000; border-bottom: 1px solid #000;
            background-color: #f2f2f2; text-align: center; vertical-align: middle;
            font-weight: bold; font-size: 10px;
            padding: 10px; /* 세로 패딩 증가 */
            width: 80px;
        }
        
        .input-cell {
            border-left: 1px solid #000; border-bottom: 1px solid #000;
            padding: 10px; /* 세로 패딩 증가 */
            vertical-align: middle; font-size: 10px;
        }
        
        .signature-box {
            border-left: 1px solid #000; border-bottom: 1px solid #000;
            text-align: center; vertical-align: middle;
            padding: 10px; width: 80px;
        }
        
        .signature-area-main { min-height: 45px; }
        
        .leave-type-cell { border-left: 1px solid #000; border-bottom: 1px solid #000; padding: 10px; }
        .leave-types { padding: 5px 0; }
        .leave-type-row { padding: 2px 0; }
        
        .checkbox-label {
            font-size: 10px;
            display: inline-block;
            padding-right: 25px;
        }
        .checkbox-mark {
            width: 10px; height: 10px; border: 1px solid #000;
            display: inline-block; text-align: center; line-height: 10px;
            font-size: 10px; margin-right: 4px; vertical-align: middle;
        }
        .checkbox-mark.checked { background-color: #000; color: #fff; }
        
        .period-cell { border: 1px solid #000; padding: 10px; text-align: left; font-size: 10px; }
        .half-day-option { margin-left: 10px; }
        .total-days-cell { border-left: 1px solid #000; border-bottom: 1px solid #000; padding: 8px; text-align: center; font-weight: bold; font-size: 10px; }
        .substitute-cell { border-left: 1px solid #000; border-bottom: 1px solid #000; padding: 10px; font-size: 10px; }
        
        .bottom-text { text-align: center; font-size: 12px; margin: 20px 0; font-weight: bold; }
        
        .signature { text-align: center; margin: 20px 0; }
        .date-section { margin-bottom: 20px; font-size: 12px; }
        .applicant-signature {
            text-align: right; width: 100%; font-size: 12px;
            padding-right: 20px; padding-bottom: 20px;
        }
        .final-approval-mark {
             color: #d00;
             font-weight: 700;
             font-size: 11px;
             display: inline-block;
             padding: 1px 2px;
             line-height: 1.1;
             text-align: center;
             vertical-align: middle;
        }
        .signature-image {
                     max-width: 60px; max-height: 55px; /* 크기 증가 */
                     width: auto; height: auto;
                     object-fit: contain;
         }
        .signature-image-inline { max-width: 60px; height: auto; vertical-align: middle; }
        /* 하단 로고 및 푸터 스타일 */
        .editor-footer {
             text-align: center;
             margin: 20px 0;
             page-break-inside: avoid;
        }
                     
        .logo {
             margin-bottom: 10px;
        }
                     
        .logo img {
             width: 40px;
             height: 40px;
             vertical-align: middle;
             margin-right: 10px;
        }
                     
       .logo span {
             font-size: 30px;
             color: #666;
             vertical-align: middle;
       }
                     
       .common-footer {
             border: 2px solid #FFFFFF;
             padding: 10px;
             background-color: #333333;
             color: #FFFFFF;
             margin-bottom: 30px;
             font-weight: bold;
             font-size: 12px;
       }
        """;
    }

    private static String generateLeaveApplicationHtml(JsonNode data) {
        String css = loadCss();
        StringBuilder html = new StringBuilder();

        String applicantDept = data.path("applicantDept").asText("");
        String applicantName = data.path("applicantName").asText("");
        String applicantPosition = getJobLevelTitle(data, "applicantPosition");
        String applicantContact = data.path("applicantContact").asText("");
        String applicantPhone = data.path("applicantPhone").asText("");

        JsonNode signatures = data.path("signatures");
        String finalApprovalStep = data.path("finalApprovalStep").asText(null);
        boolean isFinalApproved = data.path("isFinalApproved").asBoolean(false);
        String finalApprovalDateStr = data.path("finalApprovalDate").asText(null);
        if (finalApprovalDateStr != null && !finalApprovalDateStr.isBlank()) {
            // 안전하게 날짜 포맷
            try {
                finalApprovalDateStr = LocalDate.parse(finalApprovalDateStr.substring(0, 10)).format(DateTimeFormatter.ofPattern("yyyy.MM.dd"));
            } catch (Exception e) {
                // keep original if parse fails
            }
        }

        // ---- 추가: 렌더 옵션으로 상위 서명 보여줄지 제어 (기본 false)
        boolean allowShowHigherSignatures = data.path("renderOptions").path("showHigherSignaturesWhenAutoApproved").asBoolean(false);
        String logoDataUri = loadLogoDataUri();

        html.append("<!DOCTYPE html><html><head><meta charset='UTF-8'/><title>휴가원</title><style>").append(css).append("</style></head><body>");
        html.append("<div class='leave-application-container'><div class='leave-application-wrapper'>");

        // 상단 공통서식지 헤더 추가
        html.append("<div class='common-list'>선한공통서식지 - 05</div>");

        html.append("<div class='header-block-container'>");
        html.append("<table class='header-layout-table'><tr>");

        // 왼쪽 셀: 제목
        html.append("<td class='header-title-cell'>");
        html.append("<h1 class='leave-application-title'>(<span style='margin: 0 1.5em;'>휴가</span>) 원</h1>");
        html.append("</td>");

        // 오른쪽 셀: 결재란
        html.append("<td class='header-approval-cell'>");
        html.append("<div class='table-container'>");
        html.append("<table class='approval-table'><tbody>");
        html.append("<tr><th class='approval-header-cell' rowspan='4'>결<br/>재</th><th class='position-header-cell' rowspan='2'>인사담당</th><th class='position-header-cell' rowspan='2'>진료지원<br/>센터장</th><th class='approval-group-header' colspan='2'>승인</th></tr>");
        html.append("<tr><th class='position-header-cell'>행정원장</th><th class='position-header-cell'>대표원장</th></tr>");

        html.append("<tr>");
        String[] approverKeys = {"hrStaff", "centerDirector", "adminDirector", "ceoDirector"};
        String[] approverSteps = {"HR_STAFF_APPROVAL", "CENTER_DIRECTOR_APPROVAL", "ADMIN_DIRECTOR_APPROVAL", "CEO_DIRECTOR_APPROVAL"};
        for (int i = 0; i < approverKeys.length; i++) {
            html.append(generateSignatureCell(
                    signatures.path(approverKeys[i]),
                    data.path("is" + toCamelCase(approverKeys[i]) + "Approved").asBoolean(),
                    isFinalApproved,
                    finalApprovalStep,
                    approverSteps[i],
                    allowShowHigherSignatures
            ));
        }
        html.append("</tr>");

        html.append("<tr>");
        for (int i = 0; i < approverKeys.length; i++) {
            html.append(generateDateCell(
                    signatures.path(approverKeys[i]),
                    data.path("is" + toCamelCase(approverKeys[i]) + "Approved").asBoolean(),
                    isFinalApproved,
                    finalApprovalStep,
                    approverSteps[i],
                    finalApprovalDateStr,
                    data,
                    allowShowHigherSignatures
            ));
        }
        html.append("</tr>");
        html.append("</tbody></table></div>");
        html.append("</td>");

        // 헤더 레이아웃 테이블 종료
        html.append("</tr></table>");
        html.append("</div>");
        html.append("<div class='form-body'><table class='main-table'><tbody>");
        html.append("<tr><th class='main-header' rowspan='4'>신<br/>청<br/>자</th><th class='sub-header'>소속</th><td class='input-cell' colspan='3'>").append(applicantDept).append("</td><th class='sub-header'>부서장 확인란</th></tr>");
        html.append("<tr><th class='sub-header'>성명</th><td class='input-cell' colspan='3'>").append(applicantName).append("</td><td class='signature-box' rowspan='3'>")
                .append(generateSignatureAreaMain("departmentHead", data.path("isDeptHeadApproved").asBoolean(false), signatures))
                .append("</td></tr>");
        html.append("<tr><th class='sub-header'>직책</th><td class='input-cell' colspan='3'>").append(applicantPosition).append("</td></tr>");
        html.append("<tr><th class='sub-header'>연락처</th><td class='input-cell' colspan='3'>주소: ").append(applicantContact).append("<br/>전화번호: ").append(applicantPhone).append("</td></tr>");

        html.append("<tr><th class='main-header' rowspan='5'>신<br/>청<br/>내<br/>역</th><th class='sub-header' rowspan='4'>종류</th><td class='leave-type-cell' colspan='4'>")
                .append(generateLeaveTypesHtml(data.path("leaveTypes"))).append("</td></tr>");
        html.append("<tr><th class='sub-header'>경조휴가</th><td class='input-cell' colspan='3'>").append(data.path("leaveContent").path("경조휴가").asText("")).append("</td></tr>");
        html.append("<tr><th class='sub-header'>특별휴가</th><td class='input-cell' colspan='3'>").append(data.path("leaveContent").path("특별휴가").asText("")).append("</td></tr>");
        html.append("<tr><th class='sub-header'>병가</th><td class='input-cell' colspan='3'>").append(data.path("leaveContent").path("병가").asText("")).append("</td></tr>");

        html.append("<tr><th class='sub-header' rowspan='1'>기간</th><td class='period-cell' colspan='3'>")
                .append(generatePeriodsHtml(data.path("flexiblePeriods"))).append("</td><td class='total-days-cell' rowspan='1'>총 기간: ").append(data.path("totalDays").asText("0")).append(" 일</td></tr>");
        String substituteName = data.path("substituteName").asText("— 미지정 —");
        // 수정: substitutePosition에서 jobLevel 문자열을 직책명으로 변환
        String substitutePosition = getJobLevelTitle(data, "substitutePosition");
        html.append("<tr><th class='main-header' colspan='2'>대직자</th><td class='substitute-cell' colspan='4'><div class='substitute-info'>")
                .append("<span>직책: ").append(substitutePosition).append("</span>")
                .append("<span style='margin-left: 40px;'>성명: ").append(substituteName).append("</span>")
                .append("<span style='margin-left: 20px;'>").append(generateSignatureAreaMain("substitute", data.path("isSubstituteApproved").asBoolean(false), signatures)).append("</span>")
                .append("</div></td></tr>");

        html.append("</tbody></table>");

        html.append("<div class='bottom-text'>위와 같이 ( 휴가 ) 원을 제출하오니 허가하여 주시기 바랍니다.</div>");
        //String applicationDate = data.has("applicationDate") ? LocalDate.parse(data.path("applicationDate").asText()).format(DateTimeFormatter.ofPattern("yyyy년 MM월 dd일")) : "";
        String applicationDate = formatApplicationDate(data);
        html.append("<div class='signature'><div class='date-section'>").append(applicationDate).append("</div></div>");
        html.append("<div class='applicant-signature'><span>위 신청인 : ").append(applicantName).append("</span>")
                .append(generateSignatureAreaMain("applicant", data.path("isApplicantSigned").asBoolean(false), signatures))
                .append("</div>");
        html.append("</div>");
        html.append("<div class='editor-footer'>");
        html.append("<div class='logo'>");
        if (logoDataUri != null) {
            html.append("<img src='").append(logoDataUri).append("' alt='logo' style='width:40px;height:40px;vertical-align:middle;margin-right:10px;'/>");
            html.append("<span style='font-size: 25px; color: #000; vertical-align:middle;'>선한병원</span>");
        } else {
            html.append("<span style='font-size: 25px; color: #000;'>선한병원</span>");
        }
        html.append("</div>");
        html.append("<div class='common-footer'>SUNHAN HOSPITAL</div>");
        html.append("</div>");

        html.append("</div></div></body></html>");
        return html.toString();
    }

    private static String loadLogoDataUri() {
        try (InputStream is = LeaveApplicationPdfRenderer.class.getClassLoader().getResourceAsStream("images/newExecution.png")) {
            // 실제 리소스 경로는 프로젝트의 resources 폴더 기준입니다.
            if (is == null) {
                log.warn("로고 파일을 찾을 수 없습니다: images/newExecution.png");
                return null;
            }
            byte[] bytes = is.readAllBytes();
            String encoded = Base64.getEncoder().encodeToString(bytes);
            return "data:image/png;base64," + encoded;
        } catch (IOException e) {
            log.warn("로고 읽기 실패", e);
            return null;
        }
    }

    // 서명 셀 생성 (변경: allowShowHigherSignatures 파라미터 추가)
    private static String generateSignatureCell(JsonNode signatureNode,
                                                boolean isApprovedFlag,
                                                boolean isFinalApproved,
                                                String finalApprovalStep,
                                                String currentStepForCell,
                                                boolean allowShowHigherSignatures) {
        StringBuilder sb = new StringBuilder("<td class='signature-cell'>");
        // HR_FINAL_APPROVAL 단계 특별 처리
        if ("HR_FINAL_APPROVAL".equals(currentStepForCell)) {
            boolean isActualFinalApprovalStep = (isFinalApproved && finalApprovalStep != null && finalApprovalStep.equals(currentStepForCell));
            boolean isHigherThanFinal = (isFinalApproved && finalApprovalStep != null && isHigherStep(finalApprovalStep, currentStepForCell));

            if (isActualFinalApprovalStep) {
                // 이 단계에서 전결 처리한 경우
                log.info("HR_FINAL_APPROVAL에서 전결 처리");
                sb.append("<div class='final-approval-mark'><span>전결처리!</span></div>");
            } else if (isHigherThanFinal) {
                // 상위 단계에서 전결 처리된 경우
                log.info("HR_FINAL_APPROVAL - 상위에서 전결 처리됨");
                sb.append("<div class='final-approval-mark'><span>전결처리!</span></div>");
            } else {
                // 일반적인 경우 - 확인 단계이므로 빈 셀
                log.info("HR_FINAL_APPROVAL - 일반 확인 단계");
                sb.append("/");
            }
            sb.append("</td>");
            return sb.toString();
        }
        boolean isSignedByThisPerson = false;
        String rawImage = null;
        String rawText = null;
        if (signatureNode != null && signatureNode.isArray() && signatureNode.size() > 0 && signatureNode.get(0) != null) {
            isSignedByThisPerson = signatureNode.get(0).path("isSigned").asBoolean(false);
            rawImage = signatureNode.get(0).path("imageUrl").asText(null);
            rawText = signatureNode.get(0).path("text").asText(null);
        }

        String imageUrl = normalizeImageUrl(rawImage);

        // 실제 전결 처리한 단계인지 확인
        boolean isActualFinalApprovalStep = (isFinalApproved && finalApprovalStep != null && finalApprovalStep.equals(currentStepForCell));

        // 전결 단계보다 '상위' 인지 확인 (전결로 자동 승인되어야 하는 단계)
        boolean isHigherThanFinal = (isFinalApproved && finalApprovalStep != null && isHigherStep(finalApprovalStep, currentStepForCell));

        // 우선순위: 1) 만약 현재 셀이 전결보다 상위 단계이면 '전결처리' (단, 옵션으로 저장된 서명을 대신 보여줄 수 있게 함)
        if (isHigherThanFinal) {
            if (allowShowHigherSignatures && isSignedByThisPerson) {
                // 옵션 허용 시: 상위 단계의 저장된 서명(이미지 또는 텍스트)을 대신 표시
                if (imageUrl != null && !imageUrl.isBlank()) {
                    sb.append("<img src='").append(imageUrl).append("' alt='서명' class='signature-image'/>");
                } else {
                    sb.append(rawText != null && !rawText.isBlank() ? rawText : "승인");
                }
            } else {
                // 기본 동작: 전결처리 마크 표시 (저장된 서명 무시)
                sb.append("<div class='final-approval-mark'><span>전결처리!</span></div>");
            }
        }
        // 2) 실제 전결 처리한 단계라면 그 사람의 서명을 우선적으로 표시 (없으면 전결표시)
        else if (isActualFinalApprovalStep) {
            if (isSignedByThisPerson) {
                if (imageUrl != null && !imageUrl.isBlank()) {
                    sb.append("<img src='").append(imageUrl).append("' alt='서명' class='signature-image'/>");
                } else {
                    sb.append(rawText != null && !rawText.isBlank() ? rawText : "전결처리!");
                }
            } else {
                // 드문 경우: finalApproval가 설정됐는데 실제 서명이 없는 경우에도 전결처리 마크로 표시
                sb.append("<div class='final-approval-mark'><span>전결처리!</span></div>");
            }
        }
        // 3) 그 외의 일반적인 단계(전결 대상도 아니고, 실제 승인/서명 있으면 표시)
        else {
            if (isSignedByThisPerson) {
                if (imageUrl != null && !imageUrl.isBlank()) {
                    sb.append("<img src='").append(imageUrl).append("' alt='서명' class='signature-image'/>");
                } else {
                    sb.append(rawText != null && !rawText.isBlank() ? rawText : "승인");
                }
            } else {
                sb.append("/");
            }
        }

        sb.append("</td>");

        return sb.toString();
    }

    // 날짜 셀 생성 (변경: allowShowHigherSignatures 파라미터 추가)
    private static String generateDateCell(JsonNode signatureNode,
                                           boolean isApprovedFlag,
                                           boolean isFinalApproved,
                                           String finalApprovalStep,
                                           String currentStepForCell,
                                           String finalApprovalDate,
                                           JsonNode data,
                                           boolean allowShowHigherSignatures) {
        StringBuilder sb = new StringBuilder("<td class='slash-cell'>");

        log.info("=== generateDateCell 디버깅 ===");
        log.info("currentStepForCell: {}", currentStepForCell);
        log.info("finalApprovalDate(raw): {}", finalApprovalDate);
        log.info("allowShowHigherSignatures: {}", allowShowHigherSignatures);

        boolean isSignedByThisPerson = false;
        String rawSignatureDate = null;
        if (signatureNode != null && signatureNode.isArray() && signatureNode.size() > 0 && signatureNode.get(0) != null) {
            isSignedByThisPerson = signatureNode.get(0).path("isSigned").asBoolean(false);

            // --- 변경: 여러 후보 키 및 숫자(에포크) 처리 시도 ---
            rawSignatureDate = extractSignatureDateFromNode(signatureNode.get(0));
        }

        log.info("raw signatureDate (extracted): {}", rawSignatureDate);

        // 실제 전결 처리한 단계인지 확인
        boolean isActualFinalApprovalStep = (isFinalApproved && finalApprovalStep != null && finalApprovalStep.equals(currentStepForCell));
        // 전결 단계보다 상위인지 확인
        boolean isHigherThanFinal = (isFinalApproved && finalApprovalStep != null && isHigherStep(finalApprovalStep, currentStepForCell));

        log.info("isActualFinalApprovalStep: {}", isActualFinalApprovalStep);
        log.info("isHigherThanFinal: {}", isHigherThanFinal);

        // 1) 실제 서명 날짜가 있으면 (단, 만약 상위단계인데 옵션이 꺼져있으면 rawSignatureDate를 무시하고 finalApprovalDate를 보여줌)
        if (rawSignatureDate != null && !rawSignatureDate.isBlank()) {
            if (isHigherThanFinal && !allowShowHigherSignatures) {
                // 상위단계인데 옵션이 꺼져 있으면 finalApprovalDate를 사용
                String finalDateFormatted = formatDateString(finalApprovalDate);
                if (finalDateFormatted != null) {
                    log.info("상위단계 & 옵션꺼짐 -> 전결 날짜 표시(finalApprovalDate): {}", finalDateFormatted);
                    sb.append(finalDateFormatted);
                } else {
                    log.info("상위단계 & 옵션꺼짐 -> finalApprovalDate 없음 -> / 표시");
                    sb.append("/");
                }
            } else {
                // 옵션 허용이거나 상위단계 아님 -> 실제 서명 날짜 사용
                String signatureDateFormatted = formatDateString(rawSignatureDate);
                if (signatureDateFormatted != null) {
                    log.info("실제 서명 날짜 표시: {}", signatureDateFormatted);
                    sb.append(signatureDateFormatted);
                } else {
                    // format 실패 시, raw 그대로(짧게) 표시하거나 '/'로 대체 — 여기선 '/'로 처리
                    log.info("rawSignatureDate 존재하나 포맷 실패 -> / 표시 (raw={})", rawSignatureDate);
                    sb.append("/");
                }
            }
        } else {
            // rawSignatureDate 없음: 전결 관련 로직에 따라 finalApprovalDate 표시 여부 결정
            String finalDateFormatted = formatDateString(finalApprovalDate);
            if ((isActualFinalApprovalStep || isHigherThanFinal) && finalDateFormatted != null) {
                log.info("전결 날짜 표시(finalApprovalDate): {}", finalDateFormatted);
                sb.append(finalDateFormatted);
            } else {
                // 만약 서명은 되어있는데 날짜가 완전히 없으면, "승인" 대신 '/' 인 경우가 많음.
                // 필요하면 여기에서 '승인(날짜없음)' 같은 텍스트로 변경 가능.
                log.info("날짜 없음 - / 표시 (isSignedByThisPerson={})", isSignedByThisPerson);
                sb.append("/");
            }
        }

        sb.append("</td>");
        log.info("=== generateDateCell 디버깅 끝 ===");
        return sb.toString();
    }

    // --- 새 헬퍼: 시그니처 노드에서 가능한 날짜 후보들을 찾아 반환(문자열 ISO/날짜 표현) ---
    private static String extractSignatureDateFromNode(JsonNode sigNode) {
        if (sigNode == null || sigNode.isMissingNode()) return null;

        // 우선 문자열형 날짜 후보들
        String[] dateKeys = new String[] {
                "signatureDate", "signedAt", "signatureTimestamp", "signatureDateTime", "createdAt", "date", "signedDate"
        };

        for (String key : dateKeys) {
            JsonNode v = sigNode.path(key);
            if (!v.isMissingNode() && !v.isNull()) {
                if (v.isTextual()) {
                    String txt = v.asText(null);
                    if (txt != null && !txt.isBlank()) return txt.trim();
                } else if (v.isNumber()) {
                    // 숫자로 들어온 경우 (에포크 초 또는 밀리초)
                    long num = v.asLong();
                    String maybe = tryParseEpochToIso(num);
                    if (maybe != null) return maybe;
                }
            }
        }

        // 경우에 따라 metadata 구조 안에 있을 수 있음
        JsonNode meta = sigNode.path("meta");
        if (meta != null && meta.isObject()) {
            for (String key : dateKeys) {
                JsonNode v = meta.path(key);
                if (!v.isMissingNode() && !v.isNull()) {
                    if (v.isTextual()) {
                        String txt = v.asText(null);
                        if (txt != null && !txt.isBlank()) return txt.trim();
                    } else if (v.isNumber()) {
                        long num = v.asLong();
                        String maybe = tryParseEpochToIso(num);
                        if (maybe != null) return maybe;
                    }
                }
            }
        }

        return null;
    }

    // --- 새 헬퍼: 에포크(초/밀리초) 숫자를 ISO-ish(yyyy-MM-dd...) 문자열로 변환 시도 ---
    private static String tryParseEpochToIso(long epoch) {
        try {
            // heuristic: 1e12 보다 크면 밀리초(예: 1630000000000)
            Instant inst;
            if (epoch > 1_000_000_000_000L) {
                inst = Instant.ofEpochMilli(epoch);
            } else if (epoch > 1_000_000_000L) {
                // 초 단위 (약 2001-09-09 이후)
                inst = Instant.ofEpochSecond(epoch);
            } else {
                // 너무 작으면 무시
                return null;
            }
            LocalDate ld = LocalDateTime.ofInstant(inst, ZoneId.systemDefault()).toLocalDate();
            return ld.format(DateTimeFormatter.ofPattern("yyyy-MM-dd")); // 포맷은 formatDateString에서 다시 처리
        } catch (Exception e) {
            log.info("tryParseEpochToIso 실패: {}", e.toString());
            return null;
        }
    }
    // 이미지 URL을 data URI로 정규화 (필요하면 prefix 추가)
    private static String normalizeImageUrl(String imageUrl) {
        if (imageUrl == null) return null;
        imageUrl = imageUrl.trim();
        if (imageUrl.isEmpty()) return null;

        // 이미 data:로 시작하면 그대로 반환
        if (imageUrl.startsWith("data:")) return imageUrl;

        // 만약 URL(예: http...)로 보이면 그대로 반환
        if (imageUrl.startsWith("http://") || imageUrl.startsWith("https://")) return imageUrl;

        // 간단한 base64 문자 집합 검사: base64만 있는 경우 data URI로 변환
        // (정교하게 검사하려면 길이·패딩 검사 추가 가능)
        if (imageUrl.matches("^[A-Za-z0-9+/=\\r\\n]+$")) {
            // 공백/개행 제거
            String cleaned = imageUrl.replaceAll("\\s+", "");
            return "data:image/png;base64," + cleaned;
        }

        // 그 외(낯선 포맷)에는 원본 반환
        return imageUrl;
    }

    // 날짜 문자열을 yyyy.MM.dd 포맷으로 안전하게 변환. 실패하면 null 반환
    private static String formatDateString(String dateStr) {
        if (dateStr == null || dateStr.isBlank()) return null;
        dateStr = dateStr.trim();

        // 1) 이미 yyyy.MM.dd 형식일 경우 바로 통과
        try {
            DateTimeFormatter dotFmt = DateTimeFormatter.ofPattern("yyyy.MM.dd");
            LocalDate test = LocalDate.parse(dateStr, dotFmt);
            return test.format(dotFmt);
        } catch (Exception ignored) {}

        // 2) ISO instant / offset / local datetime 등 여러 포맷 시도
        try {
            // OffsetDateTime (e.g. 2025-08-08T10:38:24+09:00)
            OffsetDateTime odt = OffsetDateTime.parse(dateStr);
            LocalDate ld = odt.toLocalDate();
            return ld.format(DateTimeFormatter.ofPattern("yyyy.MM.dd"));
        } catch (DateTimeParseException ignored) {}

        try {
            // Instant (e.g. 2025-08-08T01:23:45Z)
            Instant inst = Instant.parse(dateStr);
            LocalDate ld = LocalDateTime.ofInstant(inst, ZoneId.systemDefault()).toLocalDate();
            return ld.format(DateTimeFormatter.ofPattern("yyyy.MM.dd"));
        } catch (DateTimeParseException ignored) {}

        try {
            // LocalDateTime (no offset)
            LocalDateTime ldt = LocalDateTime.parse(dateStr);
            return ldt.toLocalDate().format(DateTimeFormatter.ofPattern("yyyy.MM.dd"));
        } catch (DateTimeParseException ignored) {}

        // 3) yyyy-MM-dd 같은 단순 포맷 시도
        List<DateTimeFormatter> fallbacks = List.of(
                DateTimeFormatter.ISO_LOCAL_DATE, // yyyy-MM-dd
                DateTimeFormatter.ofPattern("yyyyMMdd")
        );
        for (DateTimeFormatter fmt : fallbacks) {
            try {
                LocalDate d = LocalDate.parse(dateStr, fmt);
                return d.format(DateTimeFormatter.ofPattern("yyyy.MM.dd"));
            } catch (DateTimeParseException ignored) {}
        }

        // 4) substring으로 간단히 yyyy-MM-dd 형태가 포함되어 있으면 사용 시도
        if (dateStr.length() >= 10) {
            String sub = dateStr.substring(0, 10);
            try {
                LocalDate d = LocalDate.parse(sub, DateTimeFormatter.ISO_LOCAL_DATE);
                return d.format(DateTimeFormatter.ofPattern("yyyy.MM.dd"));
            } catch (Exception ignored) {}
        }

        return null;
    }

    // 전결 단계보다 높은 단계인지 확인 (finalApprovalStep index 보다 currentStep index 가 큰가)
    private static boolean isHigherStep(String finalApprovalStep, String currentStep) {
        List<String> stepOrder = List.of(
                "HR_STAFF_APPROVAL",
                "CENTER_DIRECTOR_APPROVAL",
                "HR_FINAL_APPROVAL",
                "ADMIN_DIRECTOR_APPROVAL",
                "CEO_DIRECTOR_APPROVAL"
        );

        int finalIndex = stepOrder.indexOf(finalApprovalStep);
        int currentIndex = stepOrder.indexOf(currentStep);

        log.info("isHigherStep 확인 - finalApprovalStep: {} (index: {}), currentStep: {} (index: {})",
                finalApprovalStep, finalIndex, currentStep, currentIndex);

        if (finalIndex < 0 || currentIndex < 0) {
            // 알 수 없는 스텝 이름이면 false로 처리 (혹은 비즈니스에 따라 예외 처리)
            log.info("isHigherStep: 알 수 없는 스텝 이름 - finalIndex={}, currentIndex={}", finalIndex, currentIndex);
            return false;
        }

        boolean result = currentIndex > finalIndex;
        log.info("결과: {}", result);
        return result;
    }


    private static String generateSignatureAreaMain(String role, boolean isApproved, JsonNode signatures) {
        log.info("=== generateSignatureAreaMain 디버깅 ===");
        log.info("role: {}, isApproved: {}", role, isApproved);

        JsonNode signatureNode = signatures.path(role);
        log.info("signatureNode for role '{}': {}", role, signatureNode);

        boolean isSigned = false;
        String imageUrl = null;
        String signerName = null;

        if (signatureNode.isArray() && signatureNode.size() > 0) {
            JsonNode firstSignature = signatureNode.get(0);
            isSigned = firstSignature.path("isSigned").asBoolean(false);
            imageUrl = firstSignature.path("imageUrl").asText(null);
            signerName = firstSignature.path("text").asText(null);

            log.info("Array case - isSigned: {}, imageUrl present: {}, signerName: {}",
                    isSigned, (imageUrl != null && !imageUrl.isEmpty()), signerName);
        }

        // 이미지 URL 정규화
        imageUrl = normalizeImageUrl(imageUrl);

        StringBuilder result = new StringBuilder();

        if ((isApproved || isSigned)) {
            if (imageUrl != null && !imageUrl.isEmpty()) {
                log.info("서명 이미지 표시: {}", role);
                result.append("<img src='").append(imageUrl).append("' alt='").append(role).append(" 서명' class='signature-image-inline'/>");
            } else if (signerName != null && !signerName.isEmpty()) {
                log.info("서명자 이름 표시: {} ({})", role, signerName);
                result.append("<span>").append(signerName).append("</span>");
            } else {
                log.info("기본 (인) 표시: {}", role);
                result.append("<span>(인)</span>");
            }
        } else {
            log.info("서명 없음: {}", role);
            // 빈 공간 유지
            result.append("<span></span>");
        }

        log.info("최종 결과: {}", result.toString());
        log.info("=== generateSignatureAreaMain 디버깅 끝 ===");

        return result.toString();
    }

    private static String generateLeaveTypesHtml(JsonNode leaveTypesNode) {
        if (!leaveTypesNode.isArray()) return "";
        StringBuilder sb = new StringBuilder("<div class='leave-types'>");
        List<String> selectedTypes = new ArrayList<>();
        leaveTypesNode.forEach(node -> selectedTypes.add(node.asText()));

        String[] allTypes = {"연차휴가", "경조휴가", "특별휴가", "생리휴가", "보민휴가", "유산사산휴가", "병가", "기타"};
        int typesPerRow = 3;

        for (int i = 0; i < allTypes.length; i += typesPerRow) {
            sb.append("<div class='leave-type-row'>");
            for (int j = i; j < i + typesPerRow && j < allTypes.length; j++) {
                String type = allTypes[j];
                boolean isChecked = selectedTypes.contains(type);

                sb.append("<label class='checkbox-label'>")
                        .append("<span class='checkbox-mark").append(isChecked ? " checked" : "").append("'>")
                        .append(isChecked ? "V" : "").append("</span>")
                        .append(type).append("</label>");
            }
            sb.append("</div>");
        }
        sb.append("</div>");
        return sb.toString();
    }

    private static String generatePeriodsHtml(JsonNode periodsNode) {
        StringBuilder sb = new StringBuilder();
        if (periodsNode.isArray()) {
            periodsNode.forEach(period -> sb.append(formatPeriod(period)));
        } else if (periodsNode.isObject()) {
            sb.append(formatPeriod(periodsNode));
        }
        return sb.toString();
    }

    private static String formatPeriod(JsonNode period) {
        if (period == null || period.isEmpty()) return "";
        String startDate = period.path("startDate").asText("");
        String endDate = period.path("endDate").asText("");
        if (startDate.isEmpty() || endDate.isEmpty()) return "";

        String halfDay = period.path("halfDayOption").asText("all_day");
        String halfDayIndicator = "";
        switch (halfDay) {
            case "morning":
                halfDayIndicator = " <span class='half-day-option'>" +
                        "<span class='radio-mark'></span>종일 " +
                        "<span class='radio-mark checked'>●</span>오전 " +
                        "<span class='radio-mark'></span>오후</span>";
                break;
            case "afternoon":
                halfDayIndicator = " <span class='half-day-option'>" +
                        "<span class='radio-mark'></span>종일 " +
                        "<span class='radio-mark'></span>오전 " +
                        "<span class='radio-mark checked'>●</span>오후</span>";
                break;
            default:
                halfDayIndicator = " <span class='half-day-option'>" +
                        "<span class='radio-mark checked'>●</span>종일 " +
                        "<span class='radio-mark'></span>오전 " +
                        "<span class='radio-mark'></span>오후</span>";
                break;
        }
        return "<div>" + startDate + " ~ " + endDate + halfDayIndicator + "</div>";
    }

    private static String toCamelCase(String s) {
        String[] parts = s.split("(?=[A-Z])");
        parts[0] = parts[0].substring(0, 1).toUpperCase() + parts[0].substring(1);
        return String.join("", parts);
    }
}