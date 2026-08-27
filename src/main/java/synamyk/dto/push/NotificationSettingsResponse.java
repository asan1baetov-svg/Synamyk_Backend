package synamyk.dto.push;

import synamyk.entities.UserNotificationSettings;

public record NotificationSettingsResponse(
        boolean results,
        boolean reminders,
        boolean marketing
) {
    public static NotificationSettingsResponse of(UserNotificationSettings s) {
        return new NotificationSettingsResponse(s.isResults(), s.isReminders(), s.isMarketing());
    }
}
