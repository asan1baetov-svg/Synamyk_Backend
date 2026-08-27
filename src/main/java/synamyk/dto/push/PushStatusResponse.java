package synamyk.dto.push;

import java.time.LocalDateTime;
import java.util.Map;

public record PushStatusResponse(
        boolean firebaseEnabled,
        long totalTokens,
        Map<String, Long> byPlatform,
        long usersWithToken,
        long scheduledBroadcasts,
        LocalDateTime lastBroadcastAt
) {}
