package synamyk.enums;

/** Target audience selector for a push broadcast; {@code audienceRef} carries the parameter. */
public enum BroadcastAudience {
    ALL,            // every user with at least one device token
    USER_IDS,       // audienceRef = csv of user ids
    PLATFORM,       // audienceRef = ANDROID | IOS | WEB
    PURCHASED_TEST, // audienceRef = testId
    INACTIVE_DAYS   // audienceRef = number of days without a completed session
}
