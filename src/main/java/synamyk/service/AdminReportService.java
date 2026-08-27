package synamyk.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import synamyk.dto.admin.ActiveSessionEntry;
import synamyk.dto.admin.OverviewReportResponse;
import synamyk.dto.admin.OverviewReportResponse.MonthPoint;
import synamyk.dto.admin.PaymentReportResponse;
import synamyk.dto.admin.PaymentReportResponse.MonthBucket;
import synamyk.dto.admin.PaymentReportResponse.StatusBucket;
import synamyk.dto.admin.PaymentReportResponse.TestRevenue;
import synamyk.dto.admin.TestReportResponse;
import synamyk.dto.admin.TestReportResponse.Row;
import synamyk.entities.Payment;
import synamyk.entities.SubTest;
import synamyk.entities.TestSession;
import synamyk.entities.User;
import synamyk.repo.PaymentRepository;
import synamyk.repo.TestSessionRepository;
import synamyk.repo.UserRepository;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

@Service
@RequiredArgsConstructor
public class AdminReportService {

    private static final Payment.PaymentStatus COMPLETED = Payment.PaymentStatus.COMPLETED;

    private final TestSessionRepository sessionRepo;
    private final PaymentRepository paymentRepo;
    private final UserRepository userRepo;

    // ===== live: who is taking a test right now =====

    @Transactional(readOnly = true)
    public List<ActiveSessionEntry> activeSessions() {
        LocalDateTime now = LocalDateTime.now();
        Map<Long, long[]> qstats = questionStats();
        List<ActiveSessionEntry> out = new ArrayList<>();
        for (TestSession s : sessionRepo.findActiveSessions(now)) {
            User u = s.getUser();
            SubTest st = s.getSubTest();
            long total = qstats.getOrDefault(st.getId(), new long[]{0, 0})[0];
            long remaining = Math.max(0, Duration.between(now, s.getExpiresAt()).getSeconds());
            out.add(new ActiveSessionEntry(
                    s.getId(), u.getId(), fullName(u), u.getPhone(),
                    st.getTest().getId(), st.getTest().getTitle(), st.getId(), st.getTitle(),
                    s.getStartedAt(), s.getCurrentIndex(), total, remaining));
        }
        return out;
    }

    // ===== per test / sub-test completion report =====

    @Transactional(readOnly = true)
    public TestReportResponse testReport(LocalDateTime from, LocalDateTime to) {
        Map<Long, long[]> qstats = questionStats();
        long totalAttempts = 0;
        long totalCompleted = 0;
        List<Row> rows = new ArrayList<>();

        for (Object[] r : sessionRepo.reportBySubTest(from, to)) {
            Long subId = asLong(r[2]);
            long attempts = asLong(r[4]);
            long completed = asLong(r[5]);
            Double avgPts = r[7] == null ? null : ((Number) r[7]).doubleValue();
            long totalPoints = qstats.getOrDefault(subId, new long[]{0, 0})[1];

            Integer avgPercent = (avgPts == null || totalPoints == 0)
                    ? null : (int) Math.round(avgPts * 100.0 / totalPoints);
            Integer completionRate = attempts == 0 ? 0 : (int) Math.round(completed * 100.0 / attempts);

            totalAttempts += attempts;
            totalCompleted += completed;
            rows.add(new Row(asLong(r[0]), asString(r[1]), subId, asString(r[3]),
                    attempts, completed, asLong(r[6]), avgPercent, completionRate));
        }
        return new TestReportResponse(from, to, totalAttempts, totalCompleted, rows);
    }

    // ===== aggregated payments report =====

    @Transactional(readOnly = true)
    public PaymentReportResponse paymentReport(LocalDateTime from, LocalDateTime to) {
        List<StatusBucket> byStatus = paymentRepo.totalsByStatusBetween(from, to).stream()
                .map(r -> new StatusBucket(asString(r[0]), asLong(r[1]), nz(asBigDecimal(r[2]))))
                .toList();
        long completedCount = byStatus.stream()
                .filter(b -> "COMPLETED".equals(b.status()))
                .mapToLong(StatusBucket::count).findFirst().orElse(0);

        List<TestRevenue> byTest = paymentRepo.revenueByTestBetween(from, to, COMPLETED).stream()
                .map(r -> new TestRevenue(asLong(r[0]), asString(r[1]), asLong(r[2]), nz(asBigDecimal(r[3]))))
                .toList();

        List<MonthBucket> byMonth = paymentRepo.revenueByMonth(from, to).stream()
                .map(r -> new MonthBucket(asString(r[0]), asLong(r[1]), nz(asBigDecimal(r[2]))))
                .toList();

        BigDecimal total = nz(paymentRepo.revenueBetween(from, to, COMPLETED));
        return new PaymentReportResponse(from, to, total, completedCount, byStatus, byTest, byMonth);
    }

    // ===== period overview (dashboard with a date range) =====

    @Transactional(readOnly = true)
    public OverviewReportResponse overview(LocalDateTime from, LocalDateTime to) {
        long registrations = userRepo.countRegisteredBetween(from, to);
        long activeUsers = userRepo.countActiveUsersBetween(from, to);
        long started = sessionRepo.countByStartedAtGreaterThanEqualAndStartedAtLessThan(from, to);
        long completed = sessionRepo.countCompletedBetween(from, to);
        BigDecimal revenue = nz(paymentRepo.revenueBetween(from, to, COMPLETED));

        Map<String, Long> regByMonth = new HashMap<>();
        for (Object[] r : userRepo.registrationsByMonth(from, to)) regByMonth.put(asString(r[0]), asLong(r[1]));

        Map<String, long[]> sesByMonth = new HashMap<>();
        for (Object[] r : sessionRepo.sessionsByMonth(from, to))
            sesByMonth.put(asString(r[0]), new long[]{asLong(r[1]), asLong(r[2])});

        Map<String, BigDecimal> revByMonth = new HashMap<>();
        for (Object[] r : paymentRepo.revenueByMonth(from, to)) revByMonth.put(asString(r[0]), nz(asBigDecimal(r[2])));

        TreeSet<String> months = new TreeSet<>();
        months.addAll(regByMonth.keySet());
        months.addAll(sesByMonth.keySet());
        months.addAll(revByMonth.keySet());

        Map<String, MonthPoint> merged = new LinkedHashMap<>();
        for (String m : months) {
            long[] s = sesByMonth.getOrDefault(m, new long[]{0, 0});
            merged.put(m, new MonthPoint(m,
                    regByMonth.getOrDefault(m, 0L), s[0], s[1],
                    revByMonth.getOrDefault(m, BigDecimal.ZERO)));
        }
        return new OverviewReportResponse(from, to, registrations, activeUsers, started, completed,
                revenue, new ArrayList<>(merged.values()));
    }

    // ===== helpers =====

    private Map<Long, long[]> questionStats() {
        Map<Long, long[]> m = new HashMap<>();
        for (Object[] r : sessionRepo.questionStatsBySubTest()) {
            m.put(asLong(r[0]), new long[]{asLong(r[1]), asLong(r[2])});
        }
        return m;
    }

    private static String fullName(User u) {
        String n = ((u.getFirstName() == null ? "" : u.getFirstName()) + " "
                + (u.getLastName() == null ? "" : u.getLastName())).trim();
        return n.isBlank() ? "—" : n;
    }

    private static long asLong(Object o) {
        return o == null ? 0L : ((Number) o).longValue();
    }

    private static String asString(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static BigDecimal asBigDecimal(Object o) {
        if (o == null) return null;
        if (o instanceof BigDecimal b) return b;
        if (o instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
        return new BigDecimal(o.toString());
    }

    private static BigDecimal nz(BigDecimal b) {
        return b == null ? BigDecimal.ZERO : b;
    }
}
