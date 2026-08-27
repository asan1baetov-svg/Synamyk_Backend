package synamyk.repo;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import synamyk.entities.PushBroadcast;
import synamyk.enums.BroadcastStatus;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface PushBroadcastRepository extends JpaRepository<PushBroadcast, Long> {

    Page<PushBroadcast> findAllByOrderByCreatedAtDesc(Pageable pageable);

    Optional<PushBroadcast> findByDedupKey(String dedupKey);

    List<PushBroadcast> findByStatusAndScheduledAtLessThanEqual(BroadcastStatus status, LocalDateTime cutoff);

    long countByStatus(BroadcastStatus status);

    PushBroadcast findFirstByStatusOrderByFinishedAtDesc(BroadcastStatus status);
}
