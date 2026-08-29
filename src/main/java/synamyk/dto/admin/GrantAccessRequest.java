package synamyk.dto.admin;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Schema(description = "Выдать / продлить доступ пользователя к тесту ИЛИ к подтесту. "
        + "Ровно один из testId / subTestId. "
        + "Приоритет срока: expiresAt → durationDays/durationHours → ничего = бессрочно.")
public class GrantAccessRequest {

    @NotNull
    private Long userId;

    @Schema(description = "ID теста — доступ ко всему тесту (bundle). Взаимоисключающе с subTestId.")
    private Long testId;

    @Schema(description = "ID подтеста — доступ к одному подтесту. Взаимоисключающе с testId.")
    private Long subTestId;

    @Schema(description = "Срок в днях от текущего момента", example = "30")
    private Integer durationDays;

    @Schema(description = "Срок в часах от текущего момента (складывается с днями)", example = "12")
    private Integer durationHours;

    @Schema(description = "Точная дата/время окончания доступа. Если задано — durationDays/Hours игнорируются.")
    private LocalDateTime expiresAt;

    @JsonIgnore
    @AssertTrue(message = "Укажите ровно один из testId / subTestId.")
    public boolean isExactlyOneTarget() {
        return (testId == null) != (subTestId == null);
    }
}
