package synamyk.dto;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Builder
public class TestListResponse {
    private Long id;
    private String title;
    private String description;
    private String iconUrl;
    private BigDecimal price;
    private Integer subTestCount;
    private Integer completedSubTestCount; // sub-tests the user has completed at least once
    private Integer progressPercent;       // completedSubTestCount / subTestCount * 100
}
