package synamyk.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class SubTestAttemptEntry {
    private Long sessionId;
    private String status;
    private Integer correctAnswers;
    private Integer earnedPoints;
    private Long totalQuestions;
    private Integer percentage;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
}
