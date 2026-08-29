package synamyk.dto.admin;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Free-window schedule for a test or a sub-test. Both fields nullable;
 * {@code { "freeFrom": null, "freeUntil": null }} clears the window.
 * Times are zone-less (Asia/Bishkek), like the rest of the API.
 */
@Data
@Schema(description = "Free-window schedule. Content is free for everyone while `now` is inside the window.")
public class ScheduleRequest {

    @Schema(description = "Free from this moment (inclusive). null = open-ended start.", example = "2026-09-01T00:00:00")
    private LocalDateTime freeFrom;

    @Schema(description = "Free until this moment (exclusive). null = open-ended end.", example = "2026-09-08T00:00:00")
    private LocalDateTime freeUntil;
}
