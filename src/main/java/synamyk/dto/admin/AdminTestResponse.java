package synamyk.dto.admin;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@Schema(description = "Full test info for admin (all languages included)")
public class AdminTestResponse {
    private Long id;
    private String title;
    private String titleKy;
    private String description;
    private String descriptionKy;
    private String iconUrl;
    private BigDecimal price;          // bundle price: unlocks all paid sub-tests
    private LocalDateTime freeFrom;    // free-window start, null = none
    private LocalDateTime freeUntil;   // free-window end, null = none
    private Boolean active;
    private List<AdminSubTestResponse> subTests;

    @Data
    @Builder
    @Schema(description = "Sub-test info for admin")
    public static class AdminSubTestResponse {
        private Long id;
        private String title;
        private String titleKy;
        private String levelName;
        private String levelNameKy;
        private Integer levelOrder;
        private Boolean isPaid;
        private BigDecimal price;          // price to unlock this single sub-test
        private LocalDateTime freeFrom;    // free-window start, null = none
        private LocalDateTime freeUntil;   // free-window end, null = none
        private Integer durationMinutes;
        private Long questionCount;
        private Boolean active;
    }
}