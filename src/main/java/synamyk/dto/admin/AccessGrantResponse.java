package synamyk.dto.admin;

import java.time.LocalDateTime;

public record AccessGrantResponse(
        Long id,
        Long userId,
        String userName,
        String userPhone,
        Long testId,
        String testTitle,
        LocalDateTime grantedAt,
        LocalDateTime expiresAt,   // null = permanent
        String status              // PERMANENT | ACTIVE | EXPIRED
) {}
