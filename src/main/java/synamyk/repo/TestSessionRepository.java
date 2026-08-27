package synamyk.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import synamyk.entities.TestSession;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface TestSessionRepository extends JpaRepository<TestSession, Long> {

    long countByUserIdAndStatus(Long userId, TestSession.SessionStatus status);

    @Query("SELECT COALESCE(SUM(s.earnedPoints), 0) FROM TestSession s WHERE s.user.id = :userId AND s.status = 'COMPLETED'")
    long sumCorrectAnswersByUserId(@Param("userId") Long userId);

    /** Find active (IN_PROGRESS) session for a user + sub-test. */
    Optional<TestSession> findByUserIdAndSubTestIdAndStatus(
            Long userId, Long subTestId, TestSession.SessionStatus status);

    /** Find any resumable session (IN_PROGRESS or PAUSED) for a user + sub-test. */
    @Query("SELECT s FROM TestSession s WHERE s.user.id = :userId AND s.subTest.id = :subTestId " +
           "AND s.status IN ('IN_PROGRESS', 'PAUSED') ORDER BY s.createdAt DESC")
    List<TestSession> findResumable(@Param("userId") Long userId, @Param("subTestId") Long subTestId);

    List<TestSession> findByUserIdOrderByCreatedAtDesc(Long userId);

    List<TestSession> findByUserIdAndSubTestIdOrderByCreatedAtDesc(Long userId, Long subTestId);

    org.springframework.data.domain.Page<TestSession> findByUserIdAndSubTestIdOrderByCreatedAtDesc(
            Long userId, Long subTestId, org.springframework.data.domain.Pageable pageable);

    /** [subTestId, distinctCompleted] — sub-tests this user has completed at least once. */
    @Query("SELECT s.subTest.id, COUNT(DISTINCT s.id) FROM TestSession s " +
           "WHERE s.user.id = :userId AND s.subTest.test.id = :testId AND s.status = 'COMPLETED' " +
           "GROUP BY s.subTest.id")
    List<Object[]> findCompletedSubTestCounts(@Param("userId") Long userId, @Param("testId") Long testId);

    /** User ids that finished at least one session on or after the cutoff. */
    @Query("SELECT DISTINCT s.user.id FROM TestSession s WHERE s.status = 'COMPLETED' AND s.completedAt >= :from")
    List<Long> findUserIdsWithCompletedSessionAfter(@Param("from") LocalDateTime from);

    // ===== ADMIN REPORTS =====

    /** Live: users currently taking a test (IN_PROGRESS, timer not expired), newest first. */
    @Query("SELECT s FROM TestSession s JOIN FETCH s.user u JOIN FETCH s.subTest st JOIN FETCH st.test t " +
           "WHERE s.status = 'IN_PROGRESS' AND s.expiresAt > :now ORDER BY s.startedAt DESC")
    List<TestSession> findActiveSessions(@Param("now") LocalDateTime now);

    /**
     * Per sub-test activity in [from, to) by start time:
     * [testId, testTitle, subTestId, subTestTitle, attempts, completed, distinctUsers, avgEarnedPointsOfCompleted]
     */
    @Query("SELECT st.test.id, st.test.title, st.id, st.title, " +
           "COUNT(s), " +
           "SUM(CASE WHEN s.status = 'COMPLETED' THEN 1 ELSE 0 END), " +
           "COUNT(DISTINCT s.user.id), " +
           "AVG(CASE WHEN s.status = 'COMPLETED' THEN s.earnedPoints ELSE NULL END) " +
           "FROM TestSession s JOIN s.subTest st " +
           "WHERE s.startedAt >= :from AND s.startedAt < :to " +
           "GROUP BY st.test.id, st.test.title, st.id, st.title " +
           "ORDER BY st.test.id, st.id")
    List<Object[]> reportBySubTest(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /** [subTestId, questionCount, totalPoints] for active questions. */
    @Query("SELECT q.subTest.id, COUNT(q), COALESCE(SUM(q.pointValue), 0) " +
           "FROM Question q WHERE q.active = true GROUP BY q.subTest.id")
    List<Object[]> questionStatsBySubTest();

    long countByStartedAtGreaterThanEqualAndStartedAtLessThan(LocalDateTime from, LocalDateTime to);

    @Query("SELECT COUNT(s) FROM TestSession s WHERE s.status = 'COMPLETED' " +
           "AND s.completedAt >= :from AND s.completedAt < :to")
    long countCompletedBetween(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /** [yyyy-MM, sessionsStarted, sessionsCompleted] by start month. */
    @Query(value = "SELECT to_char(date_trunc('month', started_at), 'YYYY-MM') AS ym, " +
           "COUNT(*) AS started, " +
           "SUM(CASE WHEN status = 'COMPLETED' THEN 1 ELSE 0 END) AS completed " +
           "FROM test_sessions WHERE started_at >= :from AND started_at < :to " +
           "GROUP BY 1 ORDER BY 1", nativeQuery = true)
    List<Object[]> sessionsByMonth(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /**
     * Leaderboard: for each user return their best (max) correctAnswers
     * across all completed sessions of sub-tests belonging to the given test.
     * Returns Object[]: [userId, firstName, lastName, bestScore]
     */
    @Query("""
            SELECT s.user.id,
                   s.user.firstName,
                   s.user.lastName,
                   s.user.phone,
                   MAX(s.earnedPoints)
            FROM TestSession s
            WHERE s.subTest.test.id = :testId
              AND s.status = 'COMPLETED'
            GROUP BY s.user.id, s.user.firstName, s.user.lastName, s.user.phone
            ORDER BY MAX(s.earnedPoints) DESC
            """)
    List<Object[]> findRankingByTestId(@Param("testId") Long testId);

    /**
     * Daily chart data for analytics: returns [date (LocalDate), sumScore (Long)] rows.
     * When testId is null, aggregates across all tests.
     */
    @Query(value = """
            SELECT DATE(ts.completed_at) AS day,
                   SUM(ts.earned_points) AS score
            FROM test_sessions ts
            WHERE ts.user_id = :userId
              AND ts.status = 'COMPLETED'
              AND ts.completed_at >= :from
              AND ts.completed_at <= :to
              AND (:testId IS NULL OR ts.sub_test_id IN (
                      SELECT id FROM sub_tests WHERE test_id = :testId))
            GROUP BY DATE(ts.completed_at)
            ORDER BY DATE(ts.completed_at)
            """, nativeQuery = true)
    List<Object[]> findDailyScores(
            @Param("userId") Long userId,
            @Param("testId") Long testId,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to
    );

    /**
     * Total score (sum of correctAnswers) for a user in a time range.
     * When testId is null, sums across all tests.
     */
    @Query(value = """
            SELECT COALESCE(SUM(ts.earned_points), 0)
            FROM test_sessions ts
            WHERE ts.user_id = :userId
              AND ts.status = 'COMPLETED'
              AND ts.completed_at >= :from
              AND ts.completed_at <= :to
              AND (:testId IS NULL OR ts.sub_test_id IN (
                      SELECT id FROM sub_tests WHERE test_id = :testId))
            """, nativeQuery = true)
    Long findTotalScore(
            @Param("userId") Long userId,
            @Param("testId") Long testId,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to
    );

    @Query("SELECT COUNT(ts) FROM TestSession ts WHERE ts.status = 'COMPLETED' AND ts.completedAt >= :from")
    long countCompletedAfter(@Param("from") LocalDateTime from);

    @Query("SELECT s.user.id, s.user.firstName, s.user.lastName, s.user.phone, SUM(s.earnedPoints)"
            + " FROM TestSession s"
            + " WHERE s.status = 'COMPLETED'"
            + " AND (s.completedAt >= COALESCE(:from, s.completedAt))"
            + " AND (s.completedAt <= COALESCE(:to, s.completedAt))"
            + " GROUP BY s.user.id, s.user.firstName, s.user.lastName, s.user.phone"
            + " ORDER BY SUM(s.earnedPoints) DESC")
    List<Object[]> findGlobalRanking(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    @Query(value = "SELECT t.subject,"
            + " CASE WHEN COUNT(ts.id) = 0 THEN 0.0"
            + "      ELSE ROUND(CAST(SUM(CASE WHEN ts.status = 'COMPLETED' THEN 1 ELSE 0 END) AS DECIMAL) / COUNT(ts.id) * 100, 1)"
            + " END"
            + " FROM tests t"
            + " LEFT JOIN sub_tests st ON st.test_id = t.id"
            + " LEFT JOIN test_sessions ts ON ts.sub_test_id = st.id"
            + " WHERE t.subject IS NOT NULL"
            + " GROUP BY t.subject"
            + " ORDER BY t.subject",
            nativeQuery = true)
    List<Object[]> findSuccessRateBySubject();

    @org.springframework.data.jpa.repository.Modifying
    @Query("UPDATE TestSession ts SET ts.earnedPoints = 0 WHERE ts.status = 'COMPLETED'")
    void resetAllEarnedPoints();
}