package synamyk.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import synamyk.entities.UserSubTestAccess;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/** Mirror of {@link UserTestAccessRepository}, scoped to a single sub-test. */
@Repository
public interface UserSubTestAccessRepository extends JpaRepository<UserSubTestAccess, Long> {

    boolean existsByUserIdAndSubTestId(Long userId, Long subTestId);

    Optional<UserSubTestAccess> findByUserIdAndSubTestId(Long userId, Long subTestId);

    /** Access exists and is not expired as of {@code now}. */
    @Query("SELECT CASE WHEN COUNT(a) > 0 THEN true ELSE false END FROM UserSubTestAccess a "
            + "WHERE a.user.id = :userId AND a.subTest.id = :subTestId "
            + "AND (a.expiresAt IS NULL OR a.expiresAt > :now)")
    boolean existsActiveAccess(@Param("userId") Long userId,
                               @Param("subTestId") Long subTestId,
                               @Param("now") LocalDateTime now);

    /** User ids with a currently valid grant for the sub-test. */
    @Query("SELECT a.user.id FROM UserSubTestAccess a WHERE a.subTest.id = :subTestId "
            + "AND (a.expiresAt IS NULL OR a.expiresAt > :now)")
    List<Long> findActiveUserIdsBySubTestId(@Param("subTestId") Long subTestId, @Param("now") LocalDateTime now);

    List<UserSubTestAccess> findByUserIdOrderByGrantedAtDesc(Long userId);

    List<UserSubTestAccess> findBySubTestIdOrderByGrantedAtDesc(Long subTestId);

    void deleteByUserIdAndSubTestId(Long userId, Long subTestId);
}
