package synamyk.dto;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Builder
public class SubTestResponse {
    private Long id;
    private String title;
    private String levelName;
    private Integer levelOrder;
    private Boolean isPaid;
    private BigDecimal price;        // price to unlock this single sub-test
    private Integer durationMinutes;
    private Long questionCount;
    private Boolean hasAccess;     // true if user has paid OR subtest is free (full access logic)
    private Boolean effectiveFree;  // true if a free window (test or sub-test) is active now
    private LocalDateTime freeUntil; // when the active free window ends, null if n/a or open-ended
    private Boolean hasCompleted;  // true if user has completed this sub-test
    private Integer bestScore;     // best earnedPoints among COMPLETED sessions, null if none
    private Long bestSessionId;    // sessionId of that best COMPLETED attempt, null if none
    private Integer attemptsCount; // total number of attempts (any status)
}