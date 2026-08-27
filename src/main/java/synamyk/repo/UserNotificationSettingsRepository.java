package synamyk.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import synamyk.entities.UserNotificationSettings;

import java.util.Collection;
import java.util.List;

public interface UserNotificationSettingsRepository extends JpaRepository<UserNotificationSettings, Long> {

    /** User ids (within the given set) that have explicitly disabled the marketing category. */
    List<UserNotificationSettings> findByUserIdInAndMarketingFalse(Collection<Long> userIds);

    List<UserNotificationSettings> findByUserIdInAndRemindersFalse(Collection<Long> userIds);
}
