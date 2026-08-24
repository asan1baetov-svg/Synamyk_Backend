package synamyk.entities;

import jakarta.persistence.*;
import lombok.*;
import synamyk.enums.GameRoomStatus;

import java.time.LocalDateTime;

@Entity
@Table(name = "game_rooms")
@Getter
@Setter
@NoArgsConstructor
public class GameRoom {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "game_test_id", nullable = false)
    private GameTest gameTest;

    @Column(nullable = false)
    private Long player1Id;

    @Column
    private Long player2Id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private GameRoomStatus status = GameRoomStatus.WAITING;

    @Column(nullable = false)
    private Integer player1Score = 0;

    @Column(nullable = false)
    private Integer player2Score = 0;

    /** Set when the room reaches FINISHED — null for a draw. Authoritative winner (also used for forfeits, where it isn't derivable from score). */
    @Column
    private Long winnerId;

    /** Set only if the game ended via POST /forfeit — the id of the player who conceded. */
    @Column
    private Long forfeitedBy;

    @Column
    private LocalDateTime startedAt;

    @Column
    private LocalDateTime finishedAt;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
