package synamyk.dto.admin;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Data
@Schema(description = "Full pricing rewrite for a test and its sub-tests. "
        + "Any sub-test of this test NOT present in `subTests` is set to free (isPaid=false, price=0).")
public class UpdateTestPricingRequest {

    @NotNull
    @DecimalMin(value = "0", inclusive = true)
    @Schema(description = "Bundle price for the whole test. 0 is allowed.", example = "1000.00")
    private BigDecimal price;

    @NotNull
    @Valid
    @Schema(description = "Per-sub-test pricing. Sub-tests omitted here become free.")
    private List<SubTestPricing> subTests = new ArrayList<>();

    @Data
    @Schema(description = "Pricing for a single sub-test")
    public static class SubTestPricing {
        @NotNull
        private Long subTestId;

        @NotNull
        @Schema(description = "Whether the sub-test requires payment", example = "true")
        private Boolean isPaid;

        @NotNull
        @DecimalMin(value = "0", inclusive = true)
        @Schema(description = "Price to unlock this sub-test. Must be > 0 when isPaid = true.", example = "300.00")
        private BigDecimal price;
    }
}
