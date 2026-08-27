package synamyk.dto.push;

import synamyk.enums.BroadcastAudience;
import synamyk.enums.BroadcastStatus;

import java.time.LocalDateTime;

/** Immediate ACK for a broadcast request; delivery runs asynchronously. */
public record BroadcastResultResponse(
        Long broadcastId,
        BroadcastStatus status,
        BroadcastAudience audience,
        LocalDateTime scheduledAt,
        LocalDateTime createdAt
) {}
