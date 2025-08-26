package sunhan.sunhanbackend.util;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import lombok.extern.slf4j.Slf4j;
import sunhan.sunhanbackend.dto.request.ContractFormData;
import sunhan.sunhanbackend.entity.mysql.EmploymentContract;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Slf4j
public class HtmlPdfRenderer {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);


    // style.css 파일의 내용을 여기에 붙여넣습니다.
    private static final String CSS_CONTENT = """
    @page {
        size: A4;
        margin: 20mm;
    }
    
    body {
        font-family: 'Malgun Gothic', Arial, sans-serif;
        font-size: 12px;
        line-height: 1.4;
        margin: 0;
        padding: 0;
    }
    
    .contract-container {
        width: 100%;
        margin: 0;
        padding: 0;
        background-color: white;
    }
    
    .contract-header {
        text-align: center;
        margin-bottom: 20px;
    }
    
    .contract-header h1 {
        font-size: 20px;
        font-weight: bold;
        margin: 0 0 15px 0;
        letter-spacing: 4px;
        color: #333;
    }
    
    .parties-table table {
        width: 100%;
        border-collapse: collapse;
        border: 2px solid #333;
        margin-bottom: 20px;
        font-size: 11px;
    }
    
    .parties-table th,
    .parties-table td {
        border: 1px solid #333;
        padding: 6px;
        text-align: center;
      
    }
    
    .parties-table .section-header {
        background-color: #f2f2f2;
        font-weight: bold;
    }
    
    .parties-table .content-header {
        background-color: #f2f2f2;
        font-weight: bold;
    }
    
    .parties-table .party-header {
        background-color: #f2f2f2;
        font-weight: bold;
        writing-mode: vertical-rl;
        text-orientation: mixed;
    }
    
    .parties-table .field-header {
        background-color: #f2f2f2;
        font-weight: bold;
        width: 15%;
    }
    
    .parties-table .input-cell {
        text-align: center;
        padding: 8px 4px;
    }
    
    .parties-table .section-body {
        font-size: 10px;
        color: #555;
        text-align: left;
        padding: 6px;
    }
    
    .wide-cell {
        width: 35%;
    }
    
    .contract-content {
        margin-top: 15px;
    }
    
    .clause {
        margin-bottom: 15px;
        page-break-inside: avoid;
    }
    
    .clause h3 {
        font-size: 14px;
        font-weight: bold;
        margin: 10px 0 8px 0;
        border-bottom: 1px solid #eee;
        padding-bottom: 3px;
    }
    
    .clause p {
        font-size: 12px;
        line-height: 1.5;
        margin: 8px 0;
        text-align: justify;
    }
    
    .input-group {
        display: block;
        margin: 8px 0;
        line-height: 1.6;
    }
    
    .input-group p {
        display: inline;
        margin: 0;
    }
    
    .wide-input {
        display: inline-block;
        min-width: 150px;
        border: none;
        border-bottom: 1px solid #333;
        padding: 2px 5px;
        font-size: 12px;
        text-align: center;
    }
    
    .ch-input {
        display: inline-block;
        width: 25px;
        border: none;
        border-bottom: 1px solid #333;
        padding: 2px;
        font-size: 12px;
        text-align: center;
        font-weight: bold;
    }
    
    .consent-container {
        width: 100%;
        margin: 0;
        padding: 0;
        background: transparent;
        border: none;
    }
    
    .consent-row {
        background-color: #e5e5e5;
        border: 2px solid #000;
        padding: 10px 20px;
        box-sizing: border-box;
        margin: 0;
        width: 100%;
        min-height: 40px;
        overflow: hidden; /* clearfix 효과 */
    }

    .checkbox-section {
        float: left;
            width: 60%; /* 적절한 비율로 조정 */
            margin: 0;
            padding: 0;
    }
    
     .checkbox-item {
         display: inline-block;
            margin-left: 60px;
            margin-right: 20px;
            white-space: nowrap;
            vertical-align: middle;
     }
    
     .signature-section {
          float: right;
            width: 35%; /* 적절한 비율로 조정 */
            text-align: right;
            white-space: nowrap;
            margin: 0;
            padding: 0;
     }
                     
     .signature-label {
        font-size: 12px;
            color: #333;
            white-space: nowrap;
            line-height: 16px;
            margin: 0;
            display: inline;
     }
    
     .signature-input {
        width: 70px;
        height: 20px;
        border: none;
        background-color: transparent;
        padding: 0 4px;
        font-size: 12px;
        text-align: center;
        line-height: 1.2;
        margin: 0 5px;
        display: inline-block;
     }
    
     .signature-suffix-container {
        display: inline-block;
        margin: 0;
        vertical-align: middle;
        min-width: 60px;
        text-align: center;
        white-space: nowrap;
     }
                  
                  
     .signature-text {
        font-size: 11px;
        color: #666;
     }
    
     .checkbox-symbol {
        font-size: 14px;
        font-weight: bold;
        display: inline-block;
        margin-right: 3px;
        vertical-align: middle;
     }
        
     .checkbox-item span {
        display: inline-block;
        vertical-align: middle;
     }
        
     /* 서명 이미지 크기 조정 */
     .signature-image {
        vertical-align: middle !important;
        max-width: 70px !important;
        height: 30px !important;
     }
     
     .consent-row:after {
        content: "";
        display: table;
        clear: both;
     }
             
     .signature-input {
         width: 70px !important;
         font-size: 12px !important;
         line-height: 1.2 !important;
         padding: 0 4px !important;
         display: inline-block !important; /* 추가: 인라인 블록으로 강제 */
         vertical-align: baseline !important;
     }
             

     .page-break {
        page-break-before: always;
        margin: 0;
        padding: 0;
     }
    
     .date-info {
        align-self: center; /* Ensures center alignment when parent is flex-column */
        margin: 20px 0;
        font-size: 12px;
     }
    
     .signature-area {
        margin-top: 30px;
        text-align: center;
     }
    
     .signature-form {
        margin-top: 20px;
        display: flex; /* Make it a flex container */
        flex-direction: column; /* Stack children vertically */
        align-items: flex-end; /* Align all children (date, signatures) to the right initially */
     }

    /* 페이지별 특별 스타일 */
    .page1 { min-height: 260mm; }
    .page2 { min-height: 260mm; }
    .page3 { min-height: 260mm; }
    .page4 { min-height: 260mm; }
""";

    public static byte[] render(String formDataJson, EmploymentContract employmentContract) throws IOException {
        ContractFormData formData = OBJECT_MAPPER.readValue(formDataJson, ContractFormData.class);

        String htmlContent = generateHtml(formData, employmentContract);

        try (ByteArrayOutputStream os = new ByteArrayOutputStream()) {
            PdfRendererBuilder builder = new PdfRendererBuilder();

            try {
                // 폰트 파일을 바이트 배열로 읽어서 저장
                InputStream fontStream = HtmlPdfRenderer.class.getClassLoader().getResourceAsStream("fonts/malgun.ttf");
                if (fontStream != null) {
                    byte[] fontBytes = fontStream.readAllBytes();
                    fontStream.close();

                    builder.useFont(() -> new ByteArrayInputStream(fontBytes), "Malgun Gothic");
                    log.info("Malgun Gothic font loaded for PDF rendering.");
                } else {
                    log.warn("Malgun Gothic font file (fonts/malgun.ttf) not found. PDF might not render Korean correctly.");
                }
            } catch (Exception e) {
                log.error("Error loading font: " + e.getMessage(), e);
            }

            builder.withHtmlContent(htmlContent, null);
            builder.toStream(os);
            builder.run();
            return os.toByteArray();
        }
    }

    private static String getAgreementSymbol(Map<String, String> agreements, String agreementKey) {
        if (agreements != null && agreements.containsKey(agreementKey)) {
            String agreement = agreements.get(agreementKey);
            // "agree", "true", "동의" 등의 값에 따라 체크박스 표시
            return ("agree".equals(agreement) || "true".equals(agreement) || "동의".equals(agreement)) ? "☑" : "☐";
        }
        return "☐"; // 기본값: 체크되지 않음
    }

    private static String generateHtml(ContractFormData formData, EmploymentContract employmentContract) {

        String checkedSymbol = "[V]";
        String uncheckedSymbol = "[ ]";

        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html>");
        html.append("<html lang=\"ko\">");
        html.append("<head>");
        html.append("<meta charset=\"UTF-8\"/>");
        html.append("<title>근로계약서</title>");
        html.append("<style>").append(CSS_CONTENT).append("</style>");
        html.append("</head>");
        html.append("<body>");
        html.append("<div class=\"contract-container\">");

        // --- Page 1 Content ---
        html.append("<div class=\"contract-header\">");
        html.append("<h1>근로계약서【연봉제】</h1>");
        html.append("</div>");
        html.append("<div style=\"text-align: left;\">");
        html.append("선한병원(이하 '사용자'라 한다)와(과) ");
        html.append("<span style=\"text-align:center; display:inline-block; min-width:100px;\">").append(Objects.toString(formData.employeeName, "")).append("</span>");
        html.append(" (이하 '근로자'라 한다)는(은) 다음과 같이 근로 계약을 체결하고 상호 성실히 준수할 것을 확약한다.");
        html.append("</div>");
        html.append("<br/>");
        html.append("<div class=\"parties-table\">");
        html.append("<table>");
        html.append("<thead>");
        html.append("<tr>");
        html.append("<th class=\"section-header\">구분</th>");
        html.append("<th class=\"content-header\" colspan=\"5\">내용</th>");
        html.append("</tr>");
        html.append("</thead>");
        html.append("<tbody>");
        html.append("<tr>");
        html.append("<th rowspan=\"5\" class=\"party-header\">당사자</th>");
        html.append("</tr>");
        html.append("<tr>");
        html.append("<th rowspan=\"2\" class=\"party-header\">사용자</th>");
        html.append("<th class=\"field-header\">사업체명</th>");
        html.append("<td class=\"input-cell\">선한병원</td>");
        html.append("<th class=\"field-header\">대표자</th>");
        html.append("<td class=\"input-cell\">최철훈외 6명</td>");
        html.append("</tr>");
        html.append("<tr>");
        html.append("<th class=\"field-header\">소재지</th>");
        html.append("<td class=\"input-cell\">광주광역시 서구 무진대로 975(광천동)</td>");
        html.append("<th class=\"field-header\">전화</th>");
        html.append("<td class=\"input-cell\">062-466-1000</td>");
        html.append("</tr>");
        html.append("<tr>");
        html.append("<th rowspan=\"2\" class=\"party-header\">근로자</th>");
        html.append("<th class=\"field-header\">성명</th>");
        html.append("<td class=\"input-cell\">").append(Objects.toString(formData.employeeName, "")).append("</td>");
        html.append("<th class=\"field-header\">주민번호</th>");
        html.append("<td class=\"input-cell\">").append(Objects.toString(formData.employeeSSN, "")).append("</td>");
        html.append("</tr>");
        html.append("<tr>");
        html.append("<th class=\"field-header\">주소</th>");
        html.append("<td class=\"input-cell wide-cell\">").append(Objects.toString(formData.employeeAddress, "")).append("</td>");
        html.append("<th class=\"field-header\">전화</th>");
        html.append("<td class=\"input-cell\">").append(Objects.toString(formData.employeePhone, "")).append("</td>");
        html.append("</tr>");
        html.append("</tbody>");
        html.append("</table>");
        html.append("</div>");

        html.append("<div class=\"contract-content\">");
        html.append("<div class=\"clause\">");
        html.append("<h3>제 1 조 【취업 장소 및 취업직종】</h3>");
        html.append("<p class=\"input-group\">");
        html.append("① 취업장소 : 사업장 소재지 및 회사가 지정한 소재지 &#160;&#160;&#160;&#160;");
        html.append("② 취업직종 :&#160;");
        html.append("<span class=\"wide-input\" style=\"margin : 0; border-bottom:1px solid #333; display:inline-block;\">").append(Objects.toString(formData.employmentOccupation, "")).append("</span>");
        html.append("</p>");
        html.append("<p>");
        html.append("③ '사용자'는 업무상 필요에 의해서 '근로자'의 근무장소 및 부서 또는 담당업무를 변경할 수 있으며 근로자는 이에 성실히 따라야 한다");
        html.append("</p>");
        html.append("</div>");

        html.append("<div class=\"clause\">");
        html.append("<h3>제 2 조 【근로계약기간】</h3>");
        html.append("<div class=\"input-group\">");
        html.append("<p>①최초입사일 :</p>");
        html.append("<span class=\"wide-input\" style=\"border-bottom:1px solid #333; display:inline-block;\">").append(Objects.toString(formData.startDate, "")).append("</span>");
        html.append("</div>");
        html.append("<div class=\"input-group\">");
        html.append("<p>②근로계약기간 : </p>");
        html.append("<span class=\"wide-input\" style=\"border-bottom:1px solid #333; display:inline-block;\">").append(Objects.toString(formData.contractDate, "")).append("</span>");
        html.append("</div>");
        html.append("<p>");
        html.append("③ 본 계약의 유효기간은 제 ②항을 원칙으로 하며 매년 연봉 등 근로 조건에 대한 재계약을 체결하고 재계약 체결 시에는 \"사용자\"는 ");
        html.append("\"근로자\"에게 30일 전에 재계약 체결에 대한 기일 통보를 한다. 또한 매년 재계약 체결시 \"사용자\"가 제시한 기일 내에 \"근로자\"가 ");
        html.append("재계약에 응하지 않을 때에는 근로계약의 해지의사로 간주하여 근로계약은 자동으로 종료된다.");
        html.append("</p>");
        html.append("<p>");
        html.append("④ 계약기간 중 '근로자'가 계약을 해지하고자 할 때에는 30일 전에 사직서를 제출하여 업무인수인계가 원활히 이루어지도록 하여야 ");
        html.append("하며, 만약 사직서가 수리되기 전에 출근 명령 등에 불응하였을 때에는 그 기간에 대하여 결근 처리한다.");
        html.append("</p>");
        html.append("<h3>제 3 조 【근로시간 및 휴게시간】</h3>");
        html.append("<div class=\"input-group\">");
        html.append("<p>① 근로시간 : </p>");
        html.append("<span class=\"wide-input\" style=\"border-bottom:1px solid #333; display:inline-block;\">").append(Objects.toString(formData.workTime, "")).append("</span>");
        html.append("</div>");
        html.append("<div class=\"input-group\">");
        html.append("<p>② 휴게시간 : </p>");
        html.append("<span class=\"wide-input\" style=\"border-bottom:1px solid #333; display:inline-block;\">").append(Objects.toString(formData.breakTime, "")).append("</span>");
        html.append("</div>");
        html.append("<p>③ 제 ①항 및 ②항은 \"사용자\"의 병원운영상 필요와 계절의 변화에 의해 변경할 수 있으며 \"근로자\"는 근로형태에 따라 1주일에 ");
        html.append("12시간 한도로 근로를 연장할 수 있으며, 근로자는 발생할 수 있는 연장, 야간 및 휴일근로를 시행하는 것에 동의한다.</p>");

        html.append("<div class=\"consent-container\">");
        html.append("<div class=\"consent-row\" style=\"display: flex; align-items: center; justify-content: space-between; padding: 5px 15px; min-height: 25px;\">");

// 1. 체크박스 섹션
        html.append("<div class=\"checkbox-section\" style=\"display: flex; margin-top: 8px; align-items: center;\">");

        String page1Agreement = getAgreementSymbol(formData.getAgreements(), "page1");
        if (page1Agreement == null || page1Agreement.isEmpty()) {
            page1Agreement = uncheckedSymbol;
        }

// 동의 체크박스
        html.append("<div class=\"checkbox-item\">");
        String page1CurrentSymbol = "☑".equals(page1Agreement) ? checkedSymbol : uncheckedSymbol;
        html.append("<span class=\"checkbox-symbol\">" + page1CurrentSymbol + "</span>");
        html.append("<span>동의</span>");
        html.append("</div>");

// 동의하지 않음 체크박스
        html.append("<div class=\"checkbox-item\">");
        String page1OppositeSymbol = "☑".equals(page1Agreement) ? uncheckedSymbol : checkedSymbol;
        html.append("<span class=\"checkbox-symbol\">" + page1OppositeSymbol + "</span>");
        html.append("<span>동의하지 않음</span>");
        html.append("</div>");

        html.append("</div>"); // checkbox-section 끝

// 2. 서명 섹션
        html.append("<div class=\"signature-section\" style=\"display: flex; align-items: center;\">");
        html.append("<span class=\"signature-label\" style=\"margin-right: 10px;\">동의자 :</span>");
        html.append("<span class=\"signature-wrapper\" style=\"display: flex; align-items: center;\">");

        if (formData.signatures != null && formData.signatures.containsKey("page1")) {
            List<ContractFormData.SignatureEntry> page1Signatures = formData.signatures.get("page1");
            if (page1Signatures != null && !page1Signatures.isEmpty()) {
                ContractFormData.SignatureEntry sig = page1Signatures.get(0);
                html.append("<span class=\"signature-input\" style=\" text-align: center; margin-right: 10px;\">").append(Objects.toString(formData.employeeName, "")).append("</span>");

                if (sig.isSigned && sig.imageUrl != null && !sig.imageUrl.isEmpty()) {
                    html.append("<img src=\"").append(sig.imageUrl).append("\" alt=\"서명\" class=\"signature-image\" style=\"height: 30px; vertical-align: middle;\"/>");
                } else {
                    html.append("<span class=\"signature-text\" style=\"vertical-align: middle;\">(서명/인)</span>");
                }
            }
        } else {
            html.append("<span class=\"signature-input\" style=\"border-bottom: 1px solid #333; text-align: center; margin-right: 10px;\">").append(Objects.toString(formData.employeeName, "")).append("</span>");
            html.append("<span class=\"signature-text\" style=\"vertical-align: middle;\">(서명/인)</span>");
        }

        html.append("</span>");
        html.append("</div>");
        html.append("</div>"); // consent-row 끝
        html.append("</div>"); // consent-container 끝
        html.append("</div>"); // clause

        html.append("<div class=\"clause\">");
        html.append("<h3>제 4 조 【연봉계약】</h3>");
        html.append("<div class=\"input-group\">");
        html.append("<p>① 연봉계약 기간 : </p>");
        html.append("<span class=\"wide-input\" style=\"border-bottom:1px solid #333; display:inline-block;\">").append(Objects.toString(formData.salaryContractDate, "")).append("</span>");
        html.append("</div>");
        html.append("<div>");
        html.append("<p>② 연봉계약의 종료일까지 재계약이 체결되지 않을 경우 재계약 체결일까지 동일한 조건으로 재계약이 체결된 것으로 한다.</p>");
        html.append("</div>");
        html.append("</div>"); // clause
        html.append("</div>"); // contract-content

        // --- Page 2 Content ---
        html.append("<div class=\"page-break\"></div>"); // Page break
        html.append("<div class=\"contract-content\">");
        html.append("<div class=\"clause\">");
        html.append("<h3>제 5 조 【임금 및 구성항목】</h3>");
        html.append("<p>① 연봉은 아래의 각 수당을 포함하고, 12개월 균등 분할하여 매월 지급한다.</p>");
        html.append("<div class=\"parties-table\">"); // Reusing parties-table class for general tables
        html.append("<table>");
        html.append("<thead>");
        html.append("<tr>");
        html.append("<th class=\"section-header\" colspan=\"3\">항목</th>");
        html.append("<th class=\"content-header\" colspan=\"1\">금액</th>");
        html.append("<th class=\"content-header\" colspan=\"3\">산정근거</th>");
        html.append("</tr>");
        html.append("</thead>");
        html.append("<tbody>");
        html.append("<tr>");
        html.append("<th style=\"border-top: 3px double #333;\" colspan=\"3\" class=\"party-header\">연봉총액</th>");
        html.append("<td style=\"border-top: 3px double #333;\" colspan=\"1\" class=\"input-cell\">");
        html.append(Objects.toString(formData.totalAnnualSalary, ""));
        html.append("</td>");
        html.append("<td style=\"border-top: 3px double #333;\" colspan=\"3\" class=\"section-body\">월급여총액 x 12개월</td>");
        html.append("</tr>");
        html.append("<tr>");
        html.append("<th rowspan=\"12\" class=\"party-header\">연봉</th>");
        html.append("</tr>");
        html.append("<tr>");
        html.append("<th style=\"font-weight: bolder;\" rowspan=\"8\" class=\"party-header\">표준<br/>연봉총액</th>");
        html.append("</tr>");
        html.append("<tr>");
        html.append("<th class=\"party-header\">기본급</th>");
        html.append("<td class=\"input-cell\">").append(Objects.toString(formData.basicSalary, "")).append("</td>");
        html.append("<td colspan=\"1\" rowspan=\"7\" class=\"section-body\">209시간</td>");
        html.append("<td colspan=\"2\" rowspan=\"7\" class=\"section-body\">소정근로시간 x 통상시급 x 1.0</td>");
        html.append("</tr>");
        html.append("<tr>");
        html.append("<th class=\"party-header\">직책수당</th>");
        html.append("<td class=\"input-cell\">").append(Objects.toString(formData.positionAllowance, "")).append("</td>");
        html.append("</tr>");
        html.append("<tr>");
        html.append("<th class=\"party-header\">면허/자격수당</th>");
        html.append("<td class=\"input-cell\">").append(Objects.toString(formData.licenseAllowance, "")).append("</td>");
        html.append("</tr>");
        html.append("<tr>");
        html.append("<th class=\"party-header\">위험수당</th>");
        html.append("<td class=\"input-cell\">").append(Objects.toString(formData.hazardPay, "")).append("</td>");
        html.append("</tr>");
        html.append("<tr>");
        html.append("<th class=\"party-header\">처우개선비</th>");
        html.append("<td class=\"input-cell\">").append(Objects.toString(formData.treatmentImprovementExpenses, "")).append("</td>");
        html.append("</tr>");
        html.append("<tr>");
        html.append("<th class=\"party-header\">특별수당</th>");
        html.append("<td class=\"input-cell\">").append(Objects.toString(formData.specialAllowance, "")).append("</td>");
        html.append("</tr>");
        html.append("<tr>");
        html.append("<th class=\"party-header\">조정수당</th>");
        html.append("<td class=\"input-cell\">").append(Objects.toString(formData.adjustmentAllowance, "")).append("</td>");
        html.append("</tr>");
        html.append("<tr>");
        html.append("<th style=\"font-weight: bolder;\" rowspan=\"3\" class=\"party-header\">변동<br/>연봉총액</th>");
        html.append("</tr>");
        html.append("<tr>");
        html.append("<th class=\"party-header\">연장/야간수당(고정)</th>");
        html.append("<td class=\"input-cell\">").append(Objects.toString(formData.overtimePay, "")).append("</td>");
//        html.append("<td colspan=\"3\" class=\"section-body\">월 소정근로시간 209시간을 초과한 연장근로, 야간근로 가산</td>");
        html.append("<td colspan=\"3\" class=\"section-body\">").append(Objects.toString(formData.overtimeDescription, "")).append("</td>");
        html.append("</tr>");
        html.append("<tr>");
        html.append("<th class=\"party-header\">N/당직수당</th>");
        html.append("<td class=\"input-cell\">").append(Objects.toString(formData.nDutyAllowance, "")).append("</td>");
//        html.append("<td colspan=\"3\" class=\"section-body\">의무나이트 이행 수당(의무 나이트 미수행 시 차감)</td>");
        html.append("<td colspan=\"3\" class=\"section-body\">").append(Objects.toString(formData.dutyDescription, "")).append("</td>");
        html.append("</tr>");
        html.append("<tr>");
        html.append("<th colspan=\"3\" class=\"party-header\">통상시급</th>");
        html.append("<td class=\"input-cell\">").append(Objects.toString(formData.regularHourlyWage, "")).append("</td>");
        html.append("<td colspan=\"3\" class=\"section-body\">통상시급은 표준연봉총액을 기준으로 한다.</td>");
        html.append("</tr>");
        html.append("</tbody>");
        html.append("</table>");
        html.append("</div>"); // parties-table
        html.append("<p>② 추가적인 연장, 야간 및 휴일근로수당은 근로기준법이 정하는 바에 따라 가산하여 지급한다.</p>");
        html.append("<p>③ 임금은 매월 1일부터 말일까지를 산정기간으로 하고, <u>익월 15일</u>에 지급한다.</p>");
        html.append("<p>④ 매월 임금 정산 시에는 소득세와 사회보험료 등을 원천징수한 후 지급한다.</p>");
        html.append("<p>⑤ 근로자의 의무나이트(당직) 개수를 지정하여 지정 개수만큼의 수당을 연봉에 포함한다.</p>");
        html.append("<p class=\"input-group\" style=\"display: block; line-height: 1.6;\">⑥ 근로자의 의무나이트(당직)개수는 ");
        html.append("<u><span class=\"ch-input\" style=\"margin: 0; font-weight: bolder; display: inline-block; text-align: center; min-width: 30px;\">").append(Objects.toString(formData.dutyNight, "")).append("</span></u>");
        html.append("로 지정하고,의무나이트(당직)개수를 기준으로 매월 부족한 개수에 대해서는 ①항의 연봉에서 삭감하고 초과한 개수에 대해서는 추가 지급한다.");
        html.append("</p>");
        html.append("<p>⑦ 제 ①항의 임금에 관한 내용은 다른 직원들에게 비밀을 유지하며, 이를 위반할 경우 중징계 대상이 될수 있다.</p>");
        html.append("<p>⑧ 3개월 미만 제직 후 퇴사할 경우 유니폼 구입비용(업체 거래명에서 금액) 100%와 채용 시 지출했던 특수검진비 100%를 퇴직 월급여에서 공제 후 지급한다.</p>");
        html.append("<p>⑨ 이외의 사항은 급여규정에 따른다.</p>");
        html.append("</div>"); // clause

        html.append("<div class=\"clause\">");
        html.append("<h3>제 6 조 【임금의 차감】</h3>");
        html.append("<p>");
        html.append("① 제 3조(근로시간 및 휴게)에서 정한 근로시간에 '사용자'의 근무지시에도 불구하고 지각, 조퇴 및 결근한 경우에는 「근로 기준법」이 정하는 바에 따라 ");
        html.append("지급될 수 있고, 결근 1일에 대해서는 근로자 통상시급에 시간을 비례해서 공제하며, 제5조(임금 및 구성항목) 제 ①항에서 정한 급여를 차감하여 지급한다.");
        html.append("</p>");
        html.append("</div>"); // clause

        // Page 2 Signature
        html.append("<div class=\"signature-section\" style=\"display: flex; align-items: center; justify-content: flex-end; margin-top: 20px;\">");
        html.append("<span class=\"signature-label\" style=\"margin-right: 10px;\">확인 :</span>");

        if (formData.signatures != null && formData.signatures.containsKey("page2")) {
            List<ContractFormData.SignatureEntry> page2Signatures = formData.signatures.get("page2");
            if (page2Signatures != null && !page2Signatures.isEmpty()) {
                ContractFormData.SignatureEntry sig = page2Signatures.get(0);
                // 이름과 사인이미지를 감싸는 새로운 <span> 추가
                html.append("<span class=\"signature-container\" style=\"display: flex; align-items: center; min-width:120px; text-align: center;\">");
                html.append("<span class=\"signature-name\" style=\"margin-right: 5px;\">").append(Objects.toString(formData.employeeName, "")).append("</span>");

                if (sig.isSigned && sig.imageUrl != null && !sig.imageUrl.isEmpty()) {
                    html.append("<img src=\"").append(sig.imageUrl).append("\" alt=\"서명\" class=\"signature-image\" style=\"height: 30px;\"/>");
                } else {
                    html.append("<span class=\"signature-text\">(서명/인)</span>");
                }
                html.append("</span>"); // signature-container 닫기
            }
        } else {
            // 사인이 없는 경우에도 동일한 구조로 변경
            html.append("<span class=\"signature-container\" style=\"display: flex; align-items: center; border-bottom:1px solid #333; min-width:120px; text-align: center;\">");
            html.append("<span class=\"signature-name\" style=\"margin-right: 5px;\">").append(Objects.toString(formData.employeeName, "")).append("</span>");
            html.append("<span class=\"signature-text\">(서명/인)</span>");
            html.append("</span>"); // signature-container 닫기
        }
        html.append("</div>");
        html.append("</div>"); // contract-content


        // --- Page 3 Content ---
        html.append("<div class=\"page-break\"></div>"); // Page break
        html.append("<div class=\"contract-content\">");
        html.append("<div class=\"clause\">");
        html.append("<p>");
        html.append("②'근로자'가 월 중 신규채용, 중도퇴사, 휴직, 복직 등의 사유로 그 월의 근무일수가 1개월에 미달할 경우에는 임금 및 구성항목별 임금액을 최종 근로일까지의 ");
        html.append("일수에 비례하여 해당 월의 총 일수로 일할 계산한 후 지급하며, 주휴수당은 만근시에만 지급한다.");
        html.append("</p>");
        html.append("<h3>제 7 조 【휴일 및 휴가】</h3>");
        html.append("<p>");
        html.append("① 휴일 : 주휴일(주1회), 근로자의 날, 「근로 기준법」에서 정한 날, 기타 취업규칙에서 정한 날. 다만, 주휴일은 회사업무의 특성상 부서별 또는 ");
        html.append("근로자별로 다른 날을 지정할 수 있다.");
        html.append("</p>");
        html.append("<p>");
        html.append("-주휴일은 1주 동안의 소정근로일을 개근한 자에게 유급으로 하며, 개근하지 않은 근로자는 무급으로 한다.");
        html.append("</p>");
        html.append("<p>");
        html.append("-유급 휴일이 중복될 경우에는 하나의 유급 휴일만 인정한다.");
        html.append("</p>");
        html.append("<p>");
        html.append("② '사용자'는 '근로자'에게 「근로 기준법」에서 정하는 바에 따라 연차유급휴가 및 생리휴가(무급)를 부여한다.");
        html.append("</p>");
        html.append("<p>");
        html.append("③ 연차 유급휴가는 회계연도 기준(매년 01월 01일부터 12월 31일)으로 산정하여 부여한다.");
        html.append("</p>");
        html.append("</div>"); // clause
        html.append("<div class=\"clause\">");
        html.append("<h3>제 8 조 【퇴직급여】</h3>");
        html.append("<p>");
        html.append("① 퇴직급여는 근로기준법 및 근로자퇴직급여보장법이 정하는 바에 따른다.");
        html.append("</p>");
        html.append("</div>"); // clause

        html.append("<div class=\"clause\">");
        html.append("<h3>제 9 조 【정년】</h3>");
        html.append("<p>");
        html.append("① 정년은 만 60세에 도달한 날로 한다.");
        html.append("</p>");
        html.append("</div>"); // clause

        html.append("<div class=\"clause\">");
        html.append("<h3>제 10 조 【안전관리】</h3>");
        html.append("<p>");
        html.append("① \"근로자\"는 \"사용자\"가 정한 안전관리에 관한 제규칙과 관리자의 지시 사항을 준수하고 재해 발생시에는 산업재해 보상보험법에 의한다.");
        html.append("</p>");
        html.append("</div>"); // clause
        // 제 11 조 【근로계약해지】
        html.append("<div class=\"clause\">");
        html.append("  <h3>제 11 조 【근로계약해지】</h3>");
        html.append("  <p>① \"근로자\"가 취업규칙 또는 다음 각 호에 해당하는 경우에 대해서는 \"사용자\"는 \"근로자\"를 징계위원회에 회부하여 징계위원회 결정에 따라 처리한다.</p>");
        html.append("  <p>1. \"근로자\"가 직원을 선동하여 업무를 방해하고 불법으로 유인물을 배포할 때.</p>");
        html.append("  <p>2. \"근로자\"가 무단결근을 계속하여 연속 3일, 월간 5일 또는 년 20일 이상 무단결근한 경우.</p>");
        html.append("  <p>3. \"근로자\"가 근무성적 또는 능력이 현저히 불량하여 업무 수행이 불가능하다고 인정될 때.</p>");
        html.append("  <p>4. \"사용자\"의 허가 없이 을 문서, 비품, 자산 등을 외부로 반출하거나 대여했을 때.</p>");
        html.append("  <p>5. 기타 이에 준하는 행위를 하였다고 판단될 때.</p>");
        html.append("  <p>② \"근로자\"가 30일 전 사직서를 제출하고 후임자에게 인수인계를 완료한 경우.</p>");
        html.append("  <p>③ 제 2조 제②항에서 정한 근로계약기간이 만료된 때.</p>");
        html.append("  <p>④ 제 9조에서 규정한 정년에 도달한 때.</p>");
        html.append("  <p>⑤ 채용조건에 갖춰진 각종 문서의 위조, 변조 또는 허위사실이 발견되었을 때.</p>");
        html.append("  <p>⑥ 퇴직하는 달의 월급은 익월 15일에 지급하고, 퇴직금은 퇴직일로부터 1개월 이내에 지급한다.</p>");
        html.append("</div>");

        html.append("<div class=\"clause\">");
        html.append("  <h3>제 12 조 【손해배상】</h3>");
        html.append("  <p>다음 각 호의 1에 해당하는 경우에는 \"근로자\"는 \"사용자\"에게 손해를 배상하여야 한다.</p>");
        html.append("  <p>① \"근로자\"가 고의 또는 과실로 \"사용자\"에게 손해를 끼친 경우.</p>");
        html.append("</div>");

        // Page 3 Signature
        html.append("<div class=\"signature-section\" style=\"display: flex; align-items: center; justify-content: flex-end; margin-top: 20px;\">");
        html.append("<span class=\"signature-label\" style=\"margin-right: 10px;\">확인 :</span>");

        if (formData.signatures != null && formData.signatures.containsKey("page3")) {
            List<ContractFormData.SignatureEntry> page3Signatures = formData.signatures.get("page3");
            if (page3Signatures != null && !page3Signatures.isEmpty()) {
                ContractFormData.SignatureEntry sig = page3Signatures.get(0);
                // 이름과 서명을 감싸는 컨테이너
                html.append("<span class=\"signature-container\" style=\"display: flex; align-items: center; min-width:120px; text-align: center;\">");
                html.append("<span class=\"signature-name\" style=\"margin-right: 5px;\">").append(Objects.toString(formData.employeeName, "")).append("</span>");

                if (sig.isSigned && sig.imageUrl != null && !sig.imageUrl.isEmpty()) {
                    html.append("<img src=\"").append(sig.imageUrl).append("\" alt=\"서명\" class=\"signature-image\" style=\"height: 30px;\"/>");
                } else {
                    html.append("<span class=\"signature-text\">(서명/인)</span>");
                }
                html.append("</span>"); // signature-container 닫기
            }
        } else {
            // 서명이 없는 경우에도 동일한 Flexbox 구조 사용
            html.append("<span class=\"signature-container\" style=\"display: flex; align-items: center; border-bottom:1px solid #333; min-width:120px; text-align: center;\">");
            html.append("<span class=\"signature-name\" style=\"margin-right: 5px;\">").append(Objects.toString(formData.employeeName, "")).append("</span>");
            html.append("<span class=\"signature-text\">(서명/인)</span>");
            html.append("</span>"); // signature-container 닫기
        }
        html.append("</div>");
        html.append("</div>"); // contract-content


        // --- Page 4 Content ---
        // JSX의 <></> React.Fragment는 HTML 태그가 아니므로 직접 contract-content로 시작합니다.
        html.append("<div class=\"contract-content\">");
        html.append("                <div class=\"clause\">");
        html.append("                    <p>");
        html.append("                        ② '근로자'가 재직 중 또는 퇴직 후라도 병원, 관련 회사 및 업무상 관계자에 대한 기밀 정보를 누설한 경우");
        html.append("                    </p>");
        html.append("                    <p>");
        html.append("                        ③ '근로자'가 병원에 근무 중 얻은 비밀 정보나 지식을 이용하여 병원 및 관련 회사에 손해를 끼친 경우");
        html.append("                    </p>");
        html.append("                    <p>");
        html.append("                        ④ '사용자'의 사직서 수리 전에 퇴사함으로 써 병원에 손해를 끼친 경우");
        html.append("                    </p>");
        html.append("                </div>"); // clause 닫기

        // --- 제 13 조 【개인정보의 수집이용에 대한 동의】 ---
        html.append("<div class=\"clause\">");
        html.append("<h3>제 13 조 【개인정보의 수집이용에 대한 동의】</h3>");
        html.append("<div class=\"parties-table\">");
        html.append("<table>");
        html.append("<thead>");
        html.append("<tr>");
        html.append("<th class=\"section-header\">정보의 수집, 이용목적</th>");
        html.append("<td class=\"section-body\">당사의 인적자원관리, 노동법률자문사제출, 세무사무대행사제출</td>");
        html.append("</tr>");
        // 개인정보의 항목 with rowspan
        html.append("<tr>");
        html.append("<th rowspan=\"4\" class=\"section-header\">개인정보의 항목</th>");
        html.append("<td class=\"section-body\">1. 성명, 주민번호, 가족사항</td>");
        html.append("</tr>");
        html.append("<tr>");
        html.append("<td class=\"section-body\">2. 주소, 이메일, 휴대전화 번호 등 연락처</td>");
        html.append("</tr>");
        html.append("<tr>");
        html.append("<td class=\"section-body\">3. 학력, 근무경력</td>");
        html.append("</tr>");
        html.append("<tr>");
        html.append("<td class=\"section-body\">4. 기타 근로와 관련된 개인정보</td>");
        html.append("</tr>");
        html.append("<tr>");
        html.append("<th class=\"section-header\">보유 및 이용기간</th>");
        html.append("<td class=\"section-body\">근로관계가 유지되는 기간</td>");
        html.append("</tr>");
        html.append("</thead>");
        html.append("<tbody>");
        html.append("<tr>");
        html.append("<td colspan=\"2\" class=\"section-body\">");
        html.append("<p style=\"font-size: 10px;\">'사용자'는 개인정보를 다른 목적으로 이용하거나 노동법률자문사, 세무대행 외 제 3자에게 제공하지 않습니다.</p>");
        html.append("<p style=\"font-size: 10px;\">위 내용을 충분히 숙지하고 개인정보의 수집 및 이용에 대하여 동의합니다.</p>");

// 체크박스 섹션
        html.append("<div class=\"checkbox-section\" style=\"display: flex; margin-top: 8px; align-items: center;\">");

// Page 4 동의 상태 가져오기
        String page4Agreement = getAgreementSymbol(formData.getAgreements(), "page4");
        if (page4Agreement == null || page4Agreement.isEmpty()) {
            page4Agreement = uncheckedSymbol;
        }

// 동의 체크박스
        html.append("<div class=\"checkbox-item\">");
        String page4CurrentSymbol = "☑".equals(page4Agreement) ? checkedSymbol : uncheckedSymbol;
        html.append("<span class=\"checkbox-symbol\">" + page4CurrentSymbol + "</span>");
        html.append("<span>동의</span>");
        html.append("</div>");

// 동의하지 않음 체크박스
        html.append("<div class=\"checkbox-item\">");
        String page4OppositeSymbol = "☑".equals(page4Agreement) ? uncheckedSymbol : checkedSymbol;
        html.append("<span class=\"checkbox-symbol\">" + page4OppositeSymbol + "</span>");
        html.append("<span>동의하지 않음</span>");
        html.append("</div>");

        html.append("</div>"); // checkbox-section 끝

// 서명 섹션
        html.append("<div class=\"signature-section\">");
        List<ContractFormData.SignatureEntry> page4ConsentSignatures = formData.getSignatures().get("page4_consent");
        if (page4ConsentSignatures != null && !page4ConsentSignatures.isEmpty()) {
            ContractFormData.SignatureEntry sig = page4ConsentSignatures.get(0);
            html.append("<span class=\"signature-input\">").append(Objects.toString(formData.employeeName, "")).append("</span>");
            html.append("<span class=\"signature-suffix-container\">");
            if (sig.isSigned() && sig.getImageUrl() != null && !sig.getImageUrl().isEmpty()) {
                html.append("<img src=\"").append(sig.getImageUrl()).append("\" alt=\"서명\" class=\"signature-image\"/>");
            } else {
                html.append("<span class=\"signature-text\">(서명/인)</span>");
            }
            html.append("</span>");
        } else {
            html.append("<span class=\"signature-input\">").append(Objects.toString(formData.employeeName, "")).append("</span>");
            html.append("<span class=\"signature-suffix-container\">");
            html.append("<span class=\"signature-text\">(서명/인)</span>");
            html.append("</span>");
        }
        html.append("</div>"); // signature-section 끝

        html.append("</td>");
        html.append("</tr>");
        html.append("</tbody>");
        html.append("</table>");
        html.append("</div>"); // close parties-table
        html.append("</div>"); // close clause

        html.append("                <div class=\"clause\">");
        html.append("                    <h3>제 14 조 【준용 및 해석】</h3>");
        html.append("                    <p>");
        html.append("                        ① 본 계약서에 명시되지 않은 사항은 취업규칙 및 관계법령에서 정한 바에 따른다.");
        html.append("                    </p>");
        html.append("                </div>"); // clause 닫기

        html.append("                <div class=\"clause\">");
        html.append("                    <h3>제 15 조 【근로계약서 교부】</h3>");
        html.append("                    <p>");
        html.append("                        ① 근로자로 채용된 자는 본 근로계약서에 서명 또는 날인하여 근로계약을 체결하고, 근로자에게 근로계약서 사본 1부를 교부한다.");
        html.append("                    </p>");
        html.append("                </div>"); // clause 닫기

        html.append("                <div class=\"clause\">");
        html.append("                    <p>");
        html.append("                        ※ 아래의 음영부분을 자필로 기재합니다.");
        html.append("                    </p>");
        html.append("                    <div class=\"input-group\">");
        html.append("                        <span>근로계약서를 </span>");
// input 필드를 static span으로 변환 및 조건부 스타일 적용
        String receiptConfirmation1Style = "text-align: center; background-color: " + (Objects.equals(formData.receiptConfirmation1, "교부") ? "#e8f5e8" : "#ffe8e8") + ";";
        html.append("                        <span class=\"" + (Objects.equals(formData.receiptConfirmation1, "교부") ? "receipt-correct" : "receipt-incorrect") + "\" style=\"" + receiptConfirmation1Style + "\">").append(Objects.toString(formData.receiptConfirmation1, "")).append("</span>");

        html.append("                        <span>받았음을 </span>");
// input 필드를 static span으로 변환 및 조건부 스타일 적용
        String receiptConfirmation2Style = "text-align: center; background-color: " + (Objects.equals(formData.receiptConfirmation2, "확인") ? "#e8f5e8" : "#ffe8e8") + ";";
        html.append("                        <span class=\"" + (Objects.equals(formData.receiptConfirmation2, "확인") ? "receipt-correct" : "receipt-incorrect") + "\" style=\"" + receiptConfirmation2Style + "\">").append(Objects.toString(formData.receiptConfirmation2, "")).append("</span>");
        html.append("                        <span>합니다.<br/></span>");

        html.append("                        <div style=\"text-align: right; width: 100%;\">");
        html.append("                            <span style=\"display: inline-block; white-space: nowrap;\">근로자 :</span>");
// page4_receipt 서명 부분 처리
        if (formData.signatures != null && formData.signatures.containsKey("page4_receipt")) {
            List<ContractFormData.SignatureEntry> page4ReceiptSignatures = formData.signatures.get("page4_receipt");
            if (page4ReceiptSignatures != null) {
                for (int idx = 0; idx < page4ReceiptSignatures.size(); idx++) {
                    ContractFormData.SignatureEntry sig = page4ReceiptSignatures.get(idx);
                    // 이름과 사인을 감싸는 래퍼 추가
                    html.append("                            <span style=\"display: inline-flex; align-items: center; white-space: nowrap;\">");
                    // min-width 제거
                    html.append("                            <span style=\"text-align: center; margin-right: 10px;\">").append(Objects.toString(formData.employeeName, "")).append("</span>");
                    if (sig.isSigned && sig.imageUrl != null && !sig.imageUrl.isEmpty()) {
                        html.append("                            <img src=\"").append(sig.imageUrl).append("\" alt=\"서명\" style=\"height: 30px; display: inline-block; vertical-align: middle;\"/>");
                    } else {
                        html.append("                            <span style=\"vertical-align: middle;\">(서명/인)</span>");
                    }
                    html.append("</span>"); // 래퍼 닫기
                }
            }
        } else {
            // 서명이 없는 경우에도 동일한 구조로 변경
            html.append("                            <span style=\"display: inline-flex; align-items: center; white-space: nowrap;\">");
            html.append("                            <span style=\"border-bottom: 1px solid #333; text-align: center; margin-right: 10px;\">").append(Objects.toString(formData.employeeName, "")).append("</span>");
            html.append("                            <span style=\"vertical-align: middle;\">(서명/인)</span>");
            html.append("</span>"); // 래퍼 닫기
        }
        html.append("                        </div>");
        html.append("                    </div>"); // input-group 닫기
        html.append("                </div>"); // clause 닫기


        html.append("                <div class=\"clause\" style=\"text-align: center;\">");
        html.append("                    <div class=\"date-info\" style=\"text-align: center; margin-bottom: 20px;\">");
        html.append("                        <span>작성일자: </span>");
        // 작성일자 input을 static span으로 변환
        String createdDateStr;
        if (employmentContract != null && employmentContract.getCreatedAt() != null) {
            // employmentContract의 createdAt 사용
            LocalDate date = employmentContract.getCreatedAt().toLocalDate();
            createdDateStr = date.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        } else if (formData.writtenDate != null && !Objects.toString(formData.writtenDate, "").isEmpty()) {
            // fallback: formData.writtenDate 사용
            LocalDate date = null;
            String writtenDateVal = Objects.toString(formData.writtenDate, "");
            try {
                // "yyyy-MM-dd" 형식으로 파싱 시도
                date = LocalDate.parse(writtenDateVal, DateTimeFormatter.ofPattern("yyyy-MM-dd"));
            } catch (Exception e) {
                try {
                    // "yyyy년 MM월 dd일" 형식으로 파싱 시도
                    date = LocalDate.parse(writtenDateVal, DateTimeFormatter.ofPattern("yyyy년 MM월 dd일"));
                } catch (Exception e2) {
                    // 파싱 실패 시 현재 날짜 사용
                    date = LocalDate.now();
                }
            }
            createdDateStr = date.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        } else {
            // 둘 다 없는 경우 현재 날짜 사용
            createdDateStr = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        }
        html.append("                        <span class=\"input\" style=\"border:none; margin-left:10px; font-size: 12px;\">").append(createdDateStr).append("</span>");
        html.append("                    </div>");

        // 2. 회사 서명 (오른쪽 정렬된 독립 div)
        html.append("    <div style=\"text-align: right; margin-bottom: 15px; line-height: 1.4;\">");
        html.append("        <span style=\"font-weight: bold;\">회사 : 선한병원 대표원장 최철훈외 6명</span>");
// 이름과 서명 이미지를 감싸는 새로운 Flexbox 컨테이너
        html.append("        <span style=\"display: inline-flex; align-items: center; justify-content: flex-end;\">");
        if (formData.getCeoSignatureUrl() != null && !formData.getCeoSignatureUrl().isEmpty()) {
            // 이미지 높이를 30px로 키우고, 너비는 자동으로 비율에 맞게 조절
            html.append("        <img src=\"").append(formData.getCeoSignatureUrl()).append("\" alt=\"대표 서명\" style=\"height: 30px; margin-left: 10px; transform: translateY(10px);\"/>");
        } else {
            html.append("        <span style=\"margin-left: 15px; font-weight: normal;\">(서명 또는 인)</span>");
        }
        html.append("        </span>"); // Flexbox 컨테이너 닫기
        html.append("    </div>");

        html.append("                        <div style=\"text-align: right; width: 100%;\">");
        html.append("                            <span style=\"display: inline-block; white-space: nowrap;\">근로자 :</span>");

        if (formData.signatures != null && formData.signatures.containsKey("page4_final")) {
            List<ContractFormData.SignatureEntry> page4FinalSignatures = formData.signatures.get("page4_final");
            if (page4FinalSignatures != null) {
                for (int idx = 0; idx < page4FinalSignatures.size(); idx++) {
                    ContractFormData.SignatureEntry sig = page4FinalSignatures.get(idx);
                    // 이름과 서명을 감싸는 래퍼 추가
                    html.append("<span class=\"signature-wrapper\" style=\"display: inline-flex; align-items: center;\">");
                    // min-width 제거 및 border-bottom 적용
                    html.append("<span class=\"signature-input\" style=\" text-align: center; min-width: 120px; margin-right: 10px; white-space: nowrap;\">").append(Objects.toString(formData.employeeName, "")).append("</span>");

                    // 서명 이미지 또는 텍스트
                    if (sig.isSigned && sig.imageUrl != null && !sig.imageUrl.isEmpty()) {
                        html.append("<img src=\"").append(sig.imageUrl).append("\" alt=\"서명\" class=\"signature-image\" style=\"height: 30px;\"/>");
                    } else {
                        html.append("<span class=\"signature-text\">(서명/인)</span>");
                    }
                    html.append("</span>"); // 래퍼 닫기
                }
            }
        } else {
            // 서명이 없는 경우에도 동일한 구조로 변경
            html.append("<span class=\"signature-wrapper\" style=\"display: inline-flex; align-items: center;\">");
            html.append("<span class=\"signature-input\" style=\"border-bottom: 1px solid #333; text-align: center; min-width: 120px; margin-right: 10px; white-space: nowrap;\">").append(Objects.toString(formData.employeeName, "")).append("</span>");
            html.append("<span class=\"signature-text\">(서명/인)</span>");
            html.append("</span>"); // 래퍼 닫기
        }
        html.append("                        </div>");
        html.append("</div>"); // clause 닫기
        html.append("</div>"); // contract-content 닫기
        html.append("</div>"); // contract-container 닫기
        html.append("</body>");
        html.append("</html>");
        return html.toString();
    }
}