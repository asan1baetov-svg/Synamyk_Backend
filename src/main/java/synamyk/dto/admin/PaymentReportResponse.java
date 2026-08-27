package synamyk.dto.admin;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** Aggregated payments report over a period. */
public record PaymentReportResponse(
        LocalDateTime from,
        LocalDateTime to,
        BigDecimal totalRevenue,     // COMPLETED, by paidAt in range
        long completedCount,
        List<StatusBucket> byStatus, // all payments by createdAt in range
        List<TestRevenue> byTest,    // COMPLETED revenue by test
        List<MonthBucket> byMonth    // COMPLETED revenue by paid month
) {
    public record StatusBucket(String status, long count, BigDecimal amount) {}
    public record TestRevenue(Long testId, String testTitle, long count, BigDecimal revenue) {}
    public record MonthBucket(String month, long count, BigDecimal revenue) {}
}
