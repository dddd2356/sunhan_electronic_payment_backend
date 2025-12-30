package sunhan.sunhanbackend.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserListResponseDto {

    /**
     * 현재 페이지에 포함된 사용자 데이터 목록
     * (기존에 사용하시던 UserResponseDto 또는 User 인터페이스의 필드와 일치해야 합니다.)
     */
    private List<UserResponseDto> userDtos;

    /**
     * 전체 검색 조건에 해당하는 총 항목 수 (전체 사용자 수)
     */
    private long totalElements;

    /**
     * 전체 페이지 수
     */
    private int totalPages;

    /**
     * 현재 페이지 번호 (0부터 시작)
     */
    private int number;

    /**
     * 페이지 당 항목 수 (size)
     */
    private int size;
}