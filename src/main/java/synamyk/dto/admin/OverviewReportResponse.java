package synamyk.dto.admin;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** Period KPIs + monthly timeline — the dashboard with an arbitrary date range. */
public record OverviewReportResponse(
        LocalDateTime from,
        LocalDateTime to,
        long registrations,
        long activeUsers,
        long sessionsStarted,
        long sessionsCompleted,
        BigDecimal revenue,
        List<MonthPoint> byMonth
) {
    public record MonthPoint(
            String month,
            long registrations,
            long sessionsStarted,
            long sessionsCompleted,
            BigDecimal revenue
    ) {}
}
