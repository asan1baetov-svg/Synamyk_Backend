package synamyk.enums;

/** Category of a per-user notification, matched against {@code user_notification_settings}. */
public enum PushCategory {
    RESULTS,    // session result / AI error analysis ready
    REMINDERS,  // "you haven't practiced in a while"
    MARKETING   // admin broadcasts, new content in a purchased test
}
