package synamyk.entities;

import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;
import synamyk.enums.PushDataType;

import java.time.LocalDateTime;

/**
 * In-app notification inbox entry. Created for every per-user push (triggered or broadcast),
 * independent of whether the FCM delivery succeeded or Firebase is configured at all.
 */
@Entity
@Table(name = "user_notifications")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class UserNotification extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false, length = 255)
    private String title;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String body;

    @Enumerated(EnumType.STRING)
    @Column(name = "data_type", length = 30)
    private PushDataType dataType;

    @Column(name = "data_entity_id")
    private Long dataEntityId;

    /** Set when this entry came from an admin broadcast; null for triggered notifications. */
    @Column(name = "broadcast_id")
    private Long broadcastId;

    /** Null while unread. */
    @Column(name = "read_at")
    private LocalDateTime readAt;
}
