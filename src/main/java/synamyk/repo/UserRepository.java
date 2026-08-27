package synamyk.repo;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import synamyk.entities.User;
import synamyk.enums.Role;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByPhone(String phone);
    boolean existsByPhone(String phone);

    @Query(value = "SELECT u FROM User u"
            + " WHERE (:search IS NULL OR :search = ''"
            + "   OR u.phone LIKE %:search%"
            + "   OR LOWER(COALESCE(u.email,'')) LIKE LOWER(CONCAT('%',:search,'%'))"
            + "   OR LOWER(CONCAT(COALESCE(u.firstName,''),' ',COALESCE(u.lastName,''))) LIKE LOWER(CONCAT('%',:search,'%')))"
            + " AND (:active IS NULL OR u.active = :active)"
            + " AND (:role IS NULL OR u.role = :role)"
            + " AND (:dateFrom IS NULL OR u.createdAt >= :dateFrom)"
            + " AND (:dateTo IS NULL OR u.createdAt <= :dateTo)"
            + " ORDER BY u.createdAt DESC",
            countQuery = "SELECT COUNT(u) FROM User u"
            + " WHERE (:search IS NULL OR :search = ''"
            + "   OR u.phone LIKE %:search%"
            + "   OR LOWER(COALESCE(u.email,'')) LIKE LOWER(CONCAT('%',:search,'%'))"
            + "   OR LOWER(CONCAT(COALESCE(u.firstName,''),' ',COALESCE(u.lastName,''))) LIKE LOWER(CONCAT('%',:search,'%')))"
            + " AND (:active IS NULL OR u.active = :active)"
            + " AND (:role IS NULL OR u.role = :role)"
            + " AND (:dateFrom IS NULL OR u.createdAt >= :dateFrom)"
            + " AND (:dateTo IS NULL OR u.createdAt <= :dateTo)")
    Page<User> findAllByFilters(
            @Param("search") String search,
            @Param("active") Boolean active,
            @Param("role") Role role,
            @Param("dateFrom") LocalDateTime dateFrom,
            @Param("dateTo") LocalDateTime dateTo,
            Pageable pageable);

    @Query("SELECT COUNT(u) FROM User u WHERE u.createdAt >= :from")
    long countRegisteredAfter(@Param("from") LocalDateTime from);

    @Query("SELECT COUNT(DISTINCT ts.user.id) FROM TestSession ts WHERE ts.createdAt >= :from")
    long countActiveUsersAfter(@Param("from") LocalDateTime from);

    @Query("SELECT COUNT(u) FROM User u WHERE u.createdAt >= :from AND u.createdAt < :to")
    long countRegisteredBetween(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    @Query("SELECT COUNT(DISTINCT ts.user.id) FROM TestSession ts " +
           "WHERE ts.createdAt >= :from AND ts.createdAt < :to")
    long countActiveUsersBetween(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /** [yyyy-MM, count] of registrations by month. */
    @Query(value = "SELECT to_char(date_trunc('month', created_at), 'YYYY-MM') AS ym, COUNT(*) AS cnt " +
           "FROM users WHERE created_at >= :from AND created_at < :to GROUP BY 1 ORDER BY 1", nativeQuery = true)
    List<Object[]> registrationsByMonth(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);
}
