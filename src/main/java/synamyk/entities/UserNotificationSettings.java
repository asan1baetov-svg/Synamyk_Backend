package synamyk.entities;

import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

/**
 * Per-user opt-out flags for notification categories. One row per user, created lazily
 * with everything enabled on first access.
 */
@Entity
@Table(name = "user_notification_settings")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class UserNotificationSettings extends BaseEntity {

    @Id
    @Column(name = "user_id")
    private Long userId;

    @Column(nullable = false)
    @Builder.Default
    private boolean results = true;

    @Column(nullable = false)
    @Builder.Default
    private boolean reminders = true;

    @Column(nullable = false)
    @Builder.Default
    private boolean marketing = true;
}
