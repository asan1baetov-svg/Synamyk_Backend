package synamyk.dto.admin;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Schema(description = "Выдать / продлить доступ пользователя к тесту. "
        + "Приоритет срока: expiresAt → durationDays/durationHours → ничего = бессрочно.")
public class GrantAccessRequest {

    @NotNull
    private Long userId;

    @NotNull
    private Long testId;

    @Schema(description = "Срок в днях от текущего момента", example = "30")
    private Integer durationDays;

    @Schema(description = "Срок в часах от текущего момента (складывается с днями)", example = "12")
    private Integer durationHours;

    @Schema(description = "Точная дата/время окончания доступа. Если задано — durationDays/Hours игнорируются.")
    private LocalDateTime expiresAt;
}
