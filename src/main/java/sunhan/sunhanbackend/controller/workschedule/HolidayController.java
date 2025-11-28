package sunhan.sunhanbackend.controller.workschedule;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/holidays")
@Slf4j
public class HolidayController {

    @Value("${holiday.api.key}")
    private String apiKey;

    @Value("${holiday.api.url}")
    private String apiUrl;

    private final RestTemplate restTemplate = new RestTemplate();

    /**
     * 공휴일 정보 조회 프록시
     */
    @GetMapping
    public ResponseEntity<?> getHolidays(@RequestParam int year) {
        try {
            String url = String.format(
                    "%s?serviceKey=%s&solYear=%d&numOfRows=100&_type=json",
                    apiUrl, apiKey, year
            );

            String response = restTemplate.getForObject(url, String.class);
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("공휴일 조회 실패: year={}", year, e);
            return ResponseEntity.status(500)
                    .body(Map.of("error", "공휴일 조회에 실패했습니다."));
        }
    }
}