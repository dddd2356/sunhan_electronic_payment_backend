package sunhan.sunhanbackend.util;

import com.openhtmltopdf.extend.FSSupplier;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

@Slf4j
public class ConsentPdfRenderer {

    private static byte[] cachedFontBytes = null;

    /**
     * HTML 문자열을 PDF 바이트 배열로 변환
     */
    public static byte[] render(String htmlContent) throws IOException {
        // 1. 기본 HTML 구조 래핑 (CSS 포함)
        String fullHtml = wrapWithStyle(htmlContent);

        try (ByteArrayOutputStream os = new ByteArrayOutputStream()) {
            PdfRendererBuilder builder = new PdfRendererBuilder();

            // 2. 한글 폰트 설정
            if (cachedFontBytes == null) {
                synchronized (ConsentPdfRenderer.class) {
                    if (cachedFontBytes == null) {
                        try (InputStream is = ConsentPdfRenderer.class.getClassLoader()
                                .getResourceAsStream("fonts/malgun.ttf")) {
                            if (is != null) {
                                cachedFontBytes = is.readAllBytes();
                            } else {
                                log.warn("malgun.ttf 폰트를 찾을 수 없습니다. 기본 폰트를 사용합니다.");
                            }
                        }
                    }
                }
            }

            if (cachedFontBytes != null) {
                builder.useFont(() -> new ByteArrayInputStream(cachedFontBytes), "Malgun Gothic");
            }

            builder.withHtmlContent(fullHtml, null);
            builder.toStream(os);
            builder.run();

            return os.toByteArray();
        } catch (Exception e) {
            log.error("PDF 렌더링 중 오류 발생", e);
            throw new IOException("PDF 생성 실패", e);
        }
    }

    /**
     * HTML에 기본 스타일 추가
     */
    private static String wrapWithStyle(String body) {
        return """
<!DOCTYPE html>
<html>
<head>
    <meta charset="UTF-8"/>
    <style>
        @page {
            size: A4;
            margin: 8mm 10mm;
        }
        body {
            font-family: 'Malgun Gothic', sans-serif;
            padding: 0;
            margin: 0;
            line-height: 1.5;
            font-size: 9.5pt;
        }
        h1 {
            font-size: 18pt;
            margin: 0 0 12px 0;
            text-align: center;
            font-weight: bold;
        }
        table {
            width: 100%;
            border-collapse: collapse;
            margin: 10px 0;
            font-size: 9pt;
        }
        th, td {
            border: 1px solid #333;
            padding: 6px;
            text-align: left;
        }
        
        /* PDF 생성 시 안내 메시지 숨김 */
        .print-hide {
            display: none !important;
        }
        
        .print-only {
            display: block !important;
        }
        
        div {
            margin-bottom: 2px;
        }
        p {
            margin: 2px 0;
        }
    </style>
</head>
<body>
    """ + body + """
</body>
</html>
""";
    }
}