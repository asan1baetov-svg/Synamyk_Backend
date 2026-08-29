package synamyk.entities;

import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "tests")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class Test extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String title;

    @Column
    private String titleKy;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(columnDefinition = "TEXT")
    private String descriptionKy;

    @Column
    private String iconUrl;

    @Column(length = 100)
    private String subject;

    /**
     * Bundle price: a single purchase unlocks every paid sub-test of this test.
     * Independent of each sub-test's own {@code price}.
     */
    @Column(nullable = false, precision = 10, scale = 2)
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
    private Boolean active = true;

    @OneToMany(mappedBy = "test", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    @OrderBy("levelOrder ASC")
    @Builder.Default
    private List<SubTest> subTests = new ArrayList<>();
}