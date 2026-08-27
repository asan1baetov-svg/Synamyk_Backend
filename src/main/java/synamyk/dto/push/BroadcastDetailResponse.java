package synamyk.dto.push;

import synamyk.entities.PushBroadcast;
import synamyk.enums.BroadcastAudience;
import synamyk.enums.BroadcastStatus;
import synamyk.enums.PushDataType;

import java.time.LocalDateTime;

public record BroadcastDetailResponse(
        Long id,
        BroadcastStatus status,
        String title,
        String body,
        String titleKy,
        String bodyKy,
        BroadcastAudience audience,
        String audienceRef,
        PushDataType dataType,
        Long dataEntityId,
        String sentByName,
        Integer recipientCount,
        Integer successCount,
        Integer failureCount,
        LocalDateTime scheduledAt,
        LocalDateTime startedAt,
        LocalDateTime finishedAt,
        LocalDateTime createdAt
) {
    public static BroadcastDetailResponse of(PushBroadcast b) {
        String sentBy = b.getSentBy() != null
                ? (nullToEmpty(b.getSentBy().getFirstName()) + " " + nullToEmpty(b.getSentBy().getLastName())).trim()
                : "—";
        if (sentBy.isEmpty()) sentBy = "—";
        return new BroadcastDetailResponse(
                b.getId(), b.getStatus(), b.getTitle(), b.getBody(), b.getTitleKy(), b.getBodyKy(),
                b.getAudience(), b.getAudienceRef(), b.getDataType(), b.getDataEntityId(),
                sentBy, b.getRecipientCount(), b.getSuccessCount(), b.getFailureCount(),
                b.getScheduledAt(), b.getStartedAt(), b.getFinishedAt(), b.getCreatedAt());
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
