package synamyk.dto.push;

import synamyk.enums.BroadcastAudience;
import synamyk.enums.BroadcastStatus;

import java.time.LocalDateTime;

public record BroadcastHistoryEntry(
        Long id,
        String title,
        String body,
        BroadcastStatus status,
        BroadcastAudience audience,
        String sentByName,
        Integer recipientCount,
        Integer successCount,
        Integer failureCount,
        LocalDateTime scheduledAt,
        LocalDateTime createdAt
) {}
