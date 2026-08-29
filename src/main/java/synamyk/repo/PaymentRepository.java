package synamyk.repo;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import synamyk.entities.Payment;
import synamyk.entities.TestSession;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PaymentRepository extends JpaRepository<Payment, Long> {
    Optional<Payment> findByPaymentId(UUID paymentId);
    Optional<Payment> findByTransactionId(String transactionId);
    boolean existsByUserIdAndTestIdAndStatus(Long userId, Long testId, Payment.PaymentStatus status);
    List<Payment> findByUserIdOrderByCreatedAtDesc(Long userId);

    /** User's payment history, excluding never-completed PENDING attempts (there are many abandoned ones). */
    @Query(value = "SELECT p FROM Payment p JOIN FETCH p.test t LEFT JOIN FETCH p.subTest st"
            + " WHERE p.user.id = :userId AND p.status <> :excluded"
            + " ORDER BY p.createdAt DESC",
            countQuery = "SELECT COUNT(p) FROM Payment p"
            + " WHERE p.user.id = :userId AND p.status <> :excluded")
    Page<Payment> findMyPayments(@Param("userId") Long userId,
                                @Param("excluded") Payment.PaymentStatus excluded,
                                Pageable pageable);

    @Query(value = "SELECT p FROM Payment p JOIN FETCH p.user u JOIN FETCH p.test t LEFT JOIN FETCH p.subTest st"
            + " WHERE (p.status = COALESCE(:status, p.status))"
            + " AND (p.createdAt >= COALESCE(:dateFrom, p.createdAt))"
            + " AND (p.createdAt <= COALESCE(:dateTo, p.createdAt))"
            + " AND (:search IS NULL OR :search = ''"
            + "   OR CAST(p.id AS string) LIKE %:search%"
            + "   OR p.transactionId LIKE %:search%"
            + "   OR u.phone LIKE %:search%"
            + "   OR LOWER(CONCAT(COALESCE(u.firstName,''),' ',COALESCE(u.lastName,''))) LIKE LOWER(CONCAT('%',:search,'%')))"
            + " ORDER BY p.createdAt DESC",
            countQuery = "SELECT COUNT(p) FROM Payment p JOIN p.user u"
            + " WHERE (p.status = COALESCE(:status, p.status))"
            + " AND (p.createdAt >= COALESCE(:dateFrom, p.createdAt))"
            + " AND (p.createdAt <= COALESCE(:dateTo, p.createdAt))"
            + " AND (:search IS NULL OR :search = ''"
            + "   OR CAST(p.id AS string) LIKE %:search%"
            + "   OR p.transactionId LIKE %:search%"
            + "   OR u.phone LIKE %:search%"
            + "   OR LOWER(CONCAT(COALESCE(u.firstName,''),' ',COALESCE(u.lastName,''))) LIKE LOWER(CONCAT('%',:search,'%')))")
    Page<Payment> findAllByFilters(
            @Param("status") Payment.PaymentStatus status,
            @Param("dateFrom") LocalDateTime dateFrom,
            @Param("dateTo") LocalDateTime dateTo,
            @Param("search") String search,
            Pageable pageable);

    @Query("SELECT COALESCE(SUM(ts.earnedPoints), 0) FROM TestSession ts"
            + " WHERE ts.user.id = :userId AND ts.subTest.test.id = :testId AND ts.status = :status")
    Integer sumEarnedPointsByUserAndTest(
            @Param("userId") Long userId,
            @Param("testId") Long testId,
            @Param("status") TestSession.SessionStatus status);

    @Query("SELECT COALESCE(SUM(p.amount), 0) FROM Payment p WHERE p.status = :status AND p.paidAt >= :from")
    java.math.BigDecimal sumRevenueAfter(@Param("from") LocalDateTime from, @Param("status") Payment.PaymentStatus status);

    @Query("SELECT COUNT(p) FROM Payment p WHERE p.status = :status AND p.paidAt >= :from")
    long countCompletedAfter(@Param("from") LocalDateTime from, @Param("status") Payment.PaymentStatus status);

    // ===== ADMIN REPORTS =====

    /** [status, count, sumAmount] for payments created in [from, to). */
    @Query("SELECT p.status, COUNT(p), COALESCE(SUM(p.amount), 0) FROM Payment p " +
           "WHERE p.createdAt >= :from AND p.createdAt < :to GROUP BY p.status")
    List<Object[]> totalsByStatusBetween(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /** [testId, testTitle, count, revenue] for COMPLETED payments paid in [from, to). */
    @Query("SELECT p.test.id, p.test.title, COUNT(p), COALESCE(SUM(p.amount), 0) FROM Payment p " +
           "WHERE p.status = :status AND p.paidAt >= :from AND p.paidAt < :to " +
           "GROUP BY p.test.id, p.test.title ORDER BY SUM(p.amount) DESC")
    List<Object[]> revenueByTestBetween(@Param("from") LocalDateTime from,
                                        @Param("to") LocalDateTime to,
                                        @Param("status") Payment.PaymentStatus status);

    @Query("SELECT COALESCE(SUM(p.amount), 0) FROM Payment p " +
           "WHERE p.status = :status AND p.paidAt >= :from AND p.paidAt < :to")
    java.math.BigDecimal revenueBetween(@Param("from") LocalDateTime from,
                                        @Param("to") LocalDateTime to,
                                        @Param("status") Payment.PaymentStatus status);

    /** [yyyy-MM, count, revenue] for COMPLETED payments by paid month. */
    @Query(value = "SELECT to_char(date_trunc('month', paid_at), 'YYYY-MM') AS ym, " +
           "COUNT(*) AS cnt, COALESCE(SUM(amount), 0) AS revenue " +
           "FROM payments WHERE status = 'COMPLETED' AND paid_at >= :from AND paid_at < :to " +
           "GROUP BY 1 ORDER BY 1", nativeQuery = true)
    List<Object[]> revenueByMonth(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    @Query("SELECT p FROM Payment p JOIN FETCH p.user u JOIN FETCH p.test t LEFT JOIN FETCH p.subTest st"
            + " WHERE (p.status = COALESCE(:status, p.status))"
            + " AND (p.createdAt >= COALESCE(:dateFrom, p.createdAt))"
            + " AND (p.createdAt <= COALESCE(:dateTo, p.createdAt))"
            + " AND (:search IS NULL OR :search = ''"
            + "   OR CAST(p.id AS string) LIKE %:search%"
            + "   OR p.transactionId LIKE %:search%"
            + "   OR u.phone LIKE %:search%"
            + "   OR LOWER(CONCAT(COALESCE(u.firstName,''),' ',COALESCE(u.lastName,''))) LIKE LOWER(CONCAT('%',:search,'%')))"
            + " ORDER BY p.createdAt DESC")
    List<Payment> findAllByFiltersUnpaged(
            @Param("status") Payment.PaymentStatus status,
            @Param("dateFrom") LocalDateTime dateFrom,
            @Param("dateTo") LocalDateTime dateTo,
            @Param("search") String search);
}
