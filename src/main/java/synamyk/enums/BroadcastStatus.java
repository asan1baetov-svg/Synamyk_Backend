package synamyk.enums;

/** Lifecycle of an admin push broadcast. */
public enum BroadcastStatus {
    PENDING,    // queued for immediate send
    SCHEDULED,  // waiting for scheduledAt
    SENDING,    // executor is pushing batches
    SENT,       // finished (may have partial failures)
    FAILED,     // top-level failure or cancelled
    CANCELLED   // scheduled broadcast cancelled before it ran
}
