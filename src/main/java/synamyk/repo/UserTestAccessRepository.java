package synamyk.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import synamyk.entities.UserTestAccess;

import java.util.List;
import java.util.Optional;

@Repository
public interface UserTestAccessRepository extends JpaRepository<UserTestAccess, Long> {
    boolean existsByUserIdAndTestId(Long userId, Long testId);
    Optional<UserTestAccess> findByUserIdAndTestId(Long userId, Long testId);

    @Query("SELECT a.user.id FROM UserTestAccess a WHERE a.test.id = :testId")
    List<Long> findUserIdsByTestId(Long testId);
}