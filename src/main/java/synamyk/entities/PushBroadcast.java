package synamyk.entities;

import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;
import synamyk.enums.BroadcastAudience;
import synamyk.enums.BroadcastStatus;
import synamyk.enums.PushDataType;

import java.time.LocalDateTime;

@Entity
@Table(name = "push_broadcasts")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class PushBroadcast extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 255)
    private String title;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String body;

    @Column(length = 255)
    private String titleKy;

    @Column(columnDefinition = "TEXT")
    private String bodyKy;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private BroadcastStatus status;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private BroadcastAudience audience;

    /** Parameter for the audience selector (csv user ids / platform / testId / days). */
    @Column(name = "audience_ref", length = 255)
    private String audienceRef;

    @Enumerated(EnumType.STRING)
    @Column(name = "data_type", length = 30)
    private PushDataType dataType;

    @Column(name = "data_entity_id")
    private Long dataEntityId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "sent_by")
    private User sentBy;

    @Column
    private Integer recipientCount;

    @Column
    private Integer successCount;

    @Column
    private Integer failureCount;

    @Column
    private LocalDateTime scheduledAt;

    @Column
    private LocalDateTime startedAt;

    @Column
    private LocalDateTime finishedAt;

    /** Dedup guard: identical broadcasts requested within the same minute collapse to one. */
    @Column(name = "dedup_key", length = 64)
    private String dedupKey;
}
