package synamyk.repo;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import synamyk.entities.GamePlayerResult;

import java.time.LocalDateTime;
import java.util.List;

public interface GamePlayerResultRepository extends JpaRepository<GamePlayerResult, Long> {
    List<GamePlayerResult> findByUserId(Long userId);
    List<GamePlayerResult> findByUserIdOrderByCreatedAtDesc(Long userId);
    List<GamePlayerResult> findByGameTestId(Long gameTestId);
    Page<GamePlayerResult> findByGameTestId(Long gameTestId, Pageable pageable);

    @Query("SELECT r FROM GamePlayerResult r WHERE r.roomId = :roomId ORDER BY r.score DESC")
    List<GamePlayerResult> findByRoomId(Long roomId);

    long countByGameTestId(Long gameTestId);

    @Query("SELECT COUNT(DISTINCT r.userId) FROM GamePlayerResult r WHERE r.gameTestId = :gameTestId")
    long countDistinctUsersByGameTestId(Long gameTestId);

    @Query("SELECT DISTINCT r.gameTestId FROM GamePlayerResult r WHERE r.userId = :userId")
    List<Long> findPlayedGameTestIds(Long userId);

    /** Per-game stats for one user: best score, last played, number of plays. */
    @Query("SELECT r.gameTestId AS gameTestId, MAX(r.score) AS bestScore, " +
           "MAX(r.createdAt) AS lastPlayedAt, COUNT(r) AS playCount " +
           "FROM GamePlayerResult r WHERE r.userId = :userId GROUP BY r.gameTestId")
    List<GameStatsView> findMyGameStats(Long userId);

    /** Current leader score for a game (0 if nobody has played). */
    @Query("SELECT COALESCE(MAX(r.score), 0) FROM GamePlayerResult r WHERE r.gameTestId = :gameTestId")
    int findTopScore(Long gameTestId);

    /** User ids ordered by best score desc; use PageRequest.of(0,1) for the current leader. */
    @Query("SELECT r.userId FROM GamePlayerResult r WHERE r.gameTestId = :gameTestId ORDER BY r.score DESC")
    List<Long> findTopUserIds(Long gameTestId, org.springframework.data.domain.Pageable pageable);

    interface GameStatsView {
        Long getGameTestId();
        Integer getBestScore();
        LocalDateTime getLastPlayedAt();
        Long getPlayCount();
    }
}
