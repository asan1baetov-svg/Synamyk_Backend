package synamyk.dto.admin;

import java.time.LocalDateTime;
import java.util.List;

/** Per test / sub-test activity over a period (by session start time). */
public record TestReportResponse(
        LocalDateTime from,
        LocalDateTime to,
        long totalAttempts,
        long totalCompleted,
        List<Row> rows
) {
    public record Row(
            Long testId,
            String testTitle,
            Long subTestId,
            String subTestTitle,
            long attempts,
            long completed,
            long distinctUsers,
            Integer avgPercent,      // avg score of completed attempts, 0-100; null if none completed
            Integer completionRate   // completed / attempts * 100
    ) {}
}
