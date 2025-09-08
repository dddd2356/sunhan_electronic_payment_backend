package sunhan.sunhanbackend.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class SyncResult {
    private int totalCount;      // 처리한 총 건수
    private int successCount;    // 성공한 건수 (실제 업데이트된 건수)
    private int errorCount;      // 실패한 건수
    private List<String> errors; // 오류 메시지 목록

    public double getSuccessRate() {
        return totalCount > 0 ? (double) successCount / totalCount * 100 : 0;
    }
}