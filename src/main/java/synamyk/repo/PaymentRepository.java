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
    @Query(value = "SELECT p FROM Payment p JOIN FETCH p.test t"
            + " WHERE p.user.id = :userId AND p.status <> :excluded"
            + " ORDER BY p.createdAt DESC",
            countQuery = "SELECT COUNT(p) FROM Payment p"
            + " WHERE p.user.id = :userId AND p.status <> :excluded")
    Page<Payment> findMyPayments(@Param("userId") Long userId,
                                @Param("excluded") Payment.PaymentStatus excluded,
                                Pageable pageable);

    @Query(value = "SELECT p FROM Payment p JOIN FETCH p.user u JOIN FETCH p.test t"
            + " WHERE (:status IS NULL OR p.status = :status)"
            + " AND (:dateFrom IS NULL OR p.createdAt >= :dateFrom)"
            + " AND (:dateTo IS NULL OR p.createdAt <= :dateTo)"
            + " AND (:search IS NULL OR :search = ''"
            + "   OR CAST(p.id AS string) LIKE %:search%"
            + "   OR p.transactionId LIKE %:search%"
            + "   OR u.phone LIKE %:search%"
            + "   OR LOWER(CONCAT(COALESCE(u.firstName,''),' ',COALESCE(u.lastName,''))) LIKE LOWER(CONCAT('%',:search,'%')))"
            + " ORDER BY p.createdAt DESC",
            countQuery = "SELECT COUNT(p) FROM Payment p JOIN p.user u"
            + " WHERE (:status IS NULL OR p.status = :status)"
            + " AND (:dateFrom IS NULL OR p.createdAt >= :dateFrom)"
            + " AND (:dateTo IS NULL OR p.createdAt <= :dateTo)"
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

    @Query("SELECT p FROM Payment p JOIN FETCH p.user u JOIN FETCH p.test t"
            + " WHERE (:status IS NULL OR p.status = :status)"
            + " AND (:dateFrom IS NULL OR p.createdAt >= :dateFrom)"
            + " AND (:dateTo IS NULL OR p.createdAt <= :dateTo)"
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
