package synamyk.entities;

import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "sub_tests")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class SubTest extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "test_id", nullable = false)
    private Test test;

    @Column(nullable = false)
    private String title;

    @Column
    private String titleKy;

    /**
     * Display name for the level, e.g. "Бесплатный уровень", "1-уровень"
     */
    @Column(nullable = false)
    private String levelName;

    @Column
    private String levelNameKy;

    @Column(nullable = false)
    @Builder.Default
    private Integer levelOrder = 0;

    /**
     * Whether this sub-test requires payment to access.
     */
    @Column(nullable = false)
    @Builder.Default
    private Boolean isPaid = false;

    /**
     * Price to unlock this single sub-test. Independent of the parent test's
     * bundle price. Must be &gt; 0 when {@link #isPaid} is true.
     */
    @Column(nullable = false, precision = 10, scale = 2, columnDefinition = "numeric(10,2) default 0")
    @Builder.Default
    private BigDecimal price = BigDecimal.ZERO;

    /** Start of the "free for everyone" window; {@code null} = open-ended start. */
    @Column
    private LocalDateTime freeFrom;

    /** End of the "free for everyone" window; {@code null} = open-ended end. */
    @Column
    private LocalDateTime freeUntil;

    @Column(nullable = false)
    @Builder.Default
    private Integer durationMinutes = 30;

    @Column(nullable = false)
    @Builder.Default
    private Boolean active = true;

    @OneToMany(mappedBy = "subTest", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    @OrderBy("orderIndex ASC")
    @Builder.Default
    private List<Question> questions = new ArrayList<>();
}