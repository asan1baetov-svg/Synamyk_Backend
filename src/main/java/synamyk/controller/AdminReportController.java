package synamyk.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import synamyk.dto.admin.ActiveSessionEntry;
import synamyk.dto.admin.OverviewReportResponse;
import synamyk.dto.admin.PaymentReportResponse;
import synamyk.dto.admin.TestReportResponse;
import synamyk.service.AdminReportService;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/admin/reports")
@RequiredArgsConstructor
@Tag(name = "Админ — Отчёты",
        description = "Live-мониторинг, отчёты по тестам и оплатам с фильтром по периоду. Требуется роль ADMIN.")
@SecurityRequirement(name = "Bearer")
public class AdminReportController {

    private final AdminReportService reportService;

    @GetMapping("/active-sessions")
    @Operation(summary = "Кто сейчас проходит тест",
            description = "Список активных сессий в реальном времени (IN_PROGRESS, таймер не истёк), от новых к старым.")
    public ResponseEntity<List<ActiveSessionEntry>> activeSessions() {
        return ResponseEntity.ok(reportService.activeSessions());
    }

    @GetMapping("/tests")
    @Operation(summary = "Отчёт по тестам за период",
            description = "По каждому тесту/подтесту: число попыток, завершений, уникальных пользователей, "
                    + "средний балл (%) и доля завершения. Период — см. параметры ниже.")
    public ResponseEntity<TestReportResponse> tests(
            @Parameter(description = "Быстрый период: today | week | month | quarter | year | all")
            @RequestParam(required = false) String period,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        LocalDateTime[] r = range(period, from, to);
        return ResponseEntity.ok(reportService.testReport(r[0], r[1]));
    }

    @GetMapping("/payments")
    @Operation(summary = "Отчёт по оплатам за период",
            description = "Суммарная выручка, разбивка по статусам, по тестам и по месяцам. "
                    + "Выручка считается по COMPLETED-платежам (по дате оплаты).")
    public ResponseEntity<PaymentReportResponse> payments(
            @Parameter(description = "today | week | month | quarter | year | all")
            @RequestParam(required = false) String period,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        LocalDateTime[] r = range(period, from, to);
        return ResponseEntity.ok(reportService.paymentReport(r[0], r[1]));
    }

    @GetMapping("/overview")
    @Operation(summary = "Сводка за период",
            description = "KPI за период (регистрации, активные пользователи, старты/завершения сессий, выручка) "
                    + "+ помесячный таймлайн. Это дашборд с произвольным диапазоном дат.")
    public ResponseEntity<OverviewReportResponse> overview(
            @Parameter(description = "today | week | month | quarter | year | all")
            @RequestParam(required = false) String period,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        LocalDateTime[] r = range(period, from, to);
        return ResponseEntity.ok(reportService.overview(r[0], r[1]));
    }

    /**
     * Resolves the [from, to) window. Explicit from/to win; otherwise {@code period}
     * ending now; default = last 30 days. Upper bound is exclusive.
     */
    private static LocalDateTime[] range(String period, LocalDate from, LocalDate to) {
        LocalDateTime now = LocalDateTime.now();
        if (from != null || to != null) {
            LocalDateTime lo = from != null ? from.atStartOfDay() : now.minusYears(50);
            LocalDateTime hi = to != null ? to.plusDays(1).atStartOfDay() : now;
            return new LocalDateTime[]{lo, hi};
        }
        LocalDateTime lo = switch (period == null ? "" : period.toLowerCase()) {
            case "today" -> now.toLocalDate().atStartOfDay();
            case "week" -> now.minusWeeks(1);
            case "month" -> now.minusMonths(1);
            case "quarter" -> now.minusMonths(3);
            case "year" -> now.minusYears(1);
            case "all" -> LocalDateTime.of(2000, 1, 1, 0, 0);
            default -> now.minusDays(30);
        };
        return new LocalDateTime[]{lo, now.plusSeconds(1)};
    }
}
