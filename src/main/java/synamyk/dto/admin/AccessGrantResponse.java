package synamyk.dto.admin;

import java.time.LocalDateTime;

public record AccessGrantResponse(
        Long id,
        Long userId,
        String userName,
        String userPhone,
        Long testId,
        String testTitle,
        Long subTestId,            // null for a whole-test grant
        String subTestTitle,       // null for a whole-test grant
        LocalDateTime grantedAt,
        LocalDateTime expiresAt,   // null = permanent
        String status              // PERMANENT | ACTIVE | EXPIRED
) {}
