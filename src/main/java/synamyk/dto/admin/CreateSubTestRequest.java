package synamyk.dto.admin;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Schema(description = "Request to create or update a sub-test")
public class CreateSubTestRequest {

    @NotBlank
    @Schema(description = "Sub-test title in Russian", example = "Бесплатный уровень")
    private String title;

    @Schema(description = "Sub-test title in Kyrgyz")
    private String titleKy;

    @NotBlank
    @Schema(description = "Level display name in Russian", example = "1-уровень")
    private String levelName;

    @Schema(description = "Level display name in Kyrgyz")
    private String levelNameKy;

    @NotNull
    @Schema(description = "Level order for sorting (lower = first)", example = "1")
    private Integer levelOrder;

    @Schema(description = "Whether this sub-test requires payment", example = "false")
    private Boolean isPaid = false;

    @NotNull
    @DecimalMin(value = "0", inclusive = true)
    @Schema(description = "Price to unlock this single sub-test. Must be > 0 when isPaid = true.", example = "300.00")
    private BigDecimal price = BigDecimal.ZERO;

    @NotNull
    @Schema(description = "Duration of the sub-test in minutes", example = "30")
    private Integer durationMinutes;
}