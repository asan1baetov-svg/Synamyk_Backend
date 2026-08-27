package synamyk.dto.push;

import synamyk.entities.UserNotification;
import synamyk.enums.PushDataType;

import java.time.LocalDateTime;

public record UserNotificationEntry(
        Long id,
        String title,
        String body,
        PushDataType dataType,
        Long dataEntityId,
        boolean read,
        LocalDateTime createdAt
) {
    public static UserNotificationEntry of(UserNotification n) {
        return new UserNotificationEntry(
                n.getId(), n.getTitle(), n.getBody(),
                n.getDataType() != null ? n.getDataType() : PushDataType.NONE,
                n.getDataEntityId(), n.getReadAt() != null, n.getCreatedAt());
    }
}
