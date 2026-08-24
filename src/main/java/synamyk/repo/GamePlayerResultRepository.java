package synamyk.repo;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import synamyk.entities.GamePlayerResult;

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
}
