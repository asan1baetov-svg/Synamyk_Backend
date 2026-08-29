package synamyk.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import synamyk.entities.SubTest;
import synamyk.entities.Test;
import synamyk.repo.UserSubTestAccessRepository;
import synamyk.repo.UserTestAccessRepository;

import java.time.LocalDateTime;

/**
 * Single source of truth for "can this user open this sub-test right now?".
 * Replaces the two ad-hoc checks that used to live in {@code TestService} and
 * {@code TestSessionService}.
 *
 * <p>Order of precedence:
 * <ol>
 *   <li>sub-test is not paid → open</li>
 *   <li>parent test is inside its free window → open for everyone</li>
 *   <li>sub-test is inside its free window → open for everyone</li>
 *   <li>user has an active whole-test grant (bundle purchase / manual grant / legacy) → open</li>
 *   <li>user has an active sub-test grant (independent purchase / manual grant) → open</li>
 *   <li>otherwise → closed</li>
 * </ol>
 */
@Component
@RequiredArgsConstructor
public class AccessResolver {

    private final UserTestAccessRepository userTestAccessRepo;
    private final UserSubTestAccessRepository userSubTestAccessRepo;

    /**
     * A free window is active only if at least one bound is set and {@code now}
     * falls inside it. Both bounds {@code null} means "no window" (normal paid
     * behaviour), not "free forever".
     */
    public static boolean isFreeNow(LocalDateTime freeFrom, LocalDateTime freeUntil, LocalDateTime now) {
        if (freeFrom == null && freeUntil == null) return false;
        boolean afterStart = (freeFrom == null) || !now.isBefore(freeFrom);
        boolean beforeEnd = (freeUntil == null) || now.isBefore(freeUntil);
        return afterStart && beforeEnd;
    }

    /** True if either the parent test or the sub-test is currently in a free window. */
    public boolean isEffectivelyFree(SubTest st, LocalDateTime now) {
        Test t = st.getTest();
        return isFreeNow(t.getFreeFrom(), t.getFreeUntil(), now)
                || isFreeNow(st.getFreeFrom(), st.getFreeUntil(), now);
    }

    /**
     * Nearest date on which the current free window ends, or {@code null} if the
     * content is not currently free or the active window is open-ended.
     * Used by the mobile client for a "free for N more days" badge.
     */
    public LocalDateTime freeUntilBoundary(SubTest st, LocalDateTime now) {
        Test t = st.getTest();
        LocalDateTime result = null;
        if (isFreeNow(t.getFreeFrom(), t.getFreeUntil(), now) && t.getFreeUntil() != null) {
            result = t.getFreeUntil();
        }
        if (isFreeNow(st.getFreeFrom(), st.getFreeUntil(), now) && st.getFreeUntil() != null) {
            if (result == null || st.getFreeUntil().isBefore(result)) result = st.getFreeUntil();
        }
        return result;
    }

    public boolean hasSubTestAccess(Long userId, SubTest st, LocalDateTime now) {
        if (!Boolean.TRUE.equals(st.getIsPaid())) return true;
        if (isEffectivelyFree(st, now)) return true;
        Long testId = st.getTest().getId();
        if (userTestAccessRepo.existsActiveAccess(userId, testId, now)) return true;
        if (userSubTestAccessRepo.existsActiveAccess(userId, st.getId(), now)) return true;
        return false;
    }
}
