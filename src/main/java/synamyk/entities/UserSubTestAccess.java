package synamyk.entities;

import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.time.LocalDateTime;

/**
 * Sub-test level access grant. Mirror of {@link UserTestAccess} but scoped to a
 * single sub-test (independent purchase or manual admin grant).
 */
@Entity
@Table(name = "user_sub_test_access", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"user_id", "sub_test_id"})
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class UserSubTestAccess extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "sub_test_id", nullable = false)
    private SubTest subTest;

    @Column(nullable = false)
    private LocalDateTime grantedAt;

    /** When the access expires. {@code null} = permanent (e.g. a purchase). */
    @Column
    private LocalDateTime expiresAt;
}
