package synamyk.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import synamyk.entities.UserTestAccess;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface UserTestAccessRepository extends JpaRepository<UserTestAccess, Long> {

    boolean existsByUserIdAndTestId(Long userId, Long testId);

    Optional<UserTestAccess> findByUserIdAndTestId(Long userId, Long testId);

    /** Access exists and is not expired as of {@code now}. */
    @Query("SELECT CASE WHEN COUNT(a) > 0 THEN true ELSE false END FROM UserTestAccess a "
            + "WHERE a.user.id = :userId AND a.test.id = :testId "
            + "AND (a.expiresAt IS NULL OR a.expiresAt > :now)")
    boolean existsActiveAccess(@Param("userId") Long userId,
                               @Param("testId") Long testId,
                               @Param("now") LocalDateTime now);

    /** User ids with a currently valid grant for the test (used for "new content" pushes). */
    @Query("SELECT a.user.id FROM UserTestAccess a WHERE a.test.id = :testId "
            + "AND (a.expiresAt IS NULL OR a.expiresAt > :now)")
    List<Long> findActiveUserIdsByTestId(@Param("testId") Long testId, @Param("now") LocalDateTime now);

    List<UserTestAccess> findByUserIdOrderByGrantedAtDesc(Long userId);

    List<UserTestAccess> findByTestIdOrderByGrantedAtDesc(Long testId);

    void deleteByUserIdAndTestId(Long userId, Long testId);
}
