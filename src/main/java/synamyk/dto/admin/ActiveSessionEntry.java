package synamyk.dto.admin;

import java.time.LocalDateTime;

/** A user currently taking a test (live monitor). */
public record ActiveSessionEntry(
        Long sessionId,
        Long userId,
        String userName,
        String userPhone,
        Long testId,
        String testTitle,
        Long subTestId,
        String subTestTitle,
        LocalDateTime startedAt,
        Integer currentIndex,
        long totalQuestions,
        long remainingSeconds
) {}
