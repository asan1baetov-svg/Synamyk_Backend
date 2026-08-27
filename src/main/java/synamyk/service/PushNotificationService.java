package synamyk.service;

import com.google.firebase.messaging.BatchResponse;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.Message;
import com.google.firebase.messaging.MessagingErrorCode;
import com.google.firebase.messaging.MulticastMessage;
import com.google.firebase.messaging.Notification;
import com.google.firebase.messaging.SendResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import synamyk.dto.push.BroadcastRequest;
import synamyk.dto.push.NotificationSettingsRequest;
import synamyk.dto.push.PushStatusResponse;
import synamyk.entities.DeviceToken;
import synamyk.entities.PushBroadcast;
import synamyk.entities.User;
import synamyk.entities.UserNotification;
import synamyk.entities.UserNotificationSettings;
import synamyk.enums.BroadcastAudience;
import synamyk.enums.BroadcastStatus;
import synamyk.enums.DevicePlatform;
import synamyk.enums.PushCategory;
import synamyk.enums.PushDataType;
import synamyk.exception.AppException;
import synamyk.repo.DeviceTokenRepository;
import synamyk.repo.DeviceTokenRepository.TokenLangView;
import synamyk.repo.PushBroadcastRepository;
import synamyk.repo.TestSessionRepository;
import synamyk.repo.UserNotificationRepository;
import synamyk.repo.UserNotificationSettingsRepository;
import synamyk.repo.UserRepository;
import synamyk.repo.UserTestAccessRepository;
import synamyk.util.PushMessages;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class PushNotificationService {

    private static final int FCM_BATCH_SIZE = 500;
    private static final int SQL_IN_CHUNK = 1000;
    private static final int STALE_TOKEN_TTL_DAYS = 270;

    private final DeviceTokenRepository deviceTokenRepository;
    private final PushBroadcastRepository pushBroadcastRepository;
    private final UserRepository userRepository;
    private final UserNotificationRepository userNotificationRepository;
    private final UserNotificationSettingsRepository settingsRepository;
    private final TestSessionRepository testSessionRepository;
    private final UserTestAccessRepository userTestAccessRepository;

    // Optional: null when firebase.enabled=false or credentials failed to load (see FirebaseConfig).
    @Autowired(required = false)
    private FirebaseMessaging firebaseMessaging;

    // Self-reference so @Async dispatch goes through the Spring proxy.
    @Autowired
    @Lazy
    private PushNotificationService self;

    // ===================== DEVICE TOKENS =====================

    @Transactional
    public void registerDevice(Long userId, String token, DevicePlatform platform) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new AppException("Пользователь не найден.", "Колдонуучу табылган жок."));

        DeviceToken existing = deviceTokenRepository.findByToken(token).orElse(null);
        if (existing != null) {
            existing.setUser(user);
            existing.setPlatform(platform);
            existing.setLastSeenAt(LocalDateTime.now());
            deviceTokenRepository.save(existing);
            return;
        }
        deviceTokenRepository.save(DeviceToken.builder()
                .user(user)
                .token(token)
                .platform(platform)
                .lastSeenAt(LocalDateTime.now())
                .build());
    }

    @Transactional
    public void unregisterDevice(String token) {
        deviceTokenRepository.deleteByToken(token);
    }

    // ===================== USER SETTINGS =====================

    @Transactional
    public UserNotificationSettings getOrCreateSettings(Long userId) {
        return settingsRepository.findById(userId)
                .orElseGet(() -> settingsRepository.save(
                        UserNotificationSettings.builder().userId(userId).build()));
    }

    @Transactional
    public UserNotificationSettings updateSettings(Long userId, NotificationSettingsRequest req) {
        UserNotificationSettings s = getOrCreateSettings(userId);
        if (req.getResults() != null) s.setResults(req.getResults());
        if (req.getReminders() != null) s.setReminders(req.getReminders());
        if (req.getMarketing() != null) s.setMarketing(req.getMarketing());
        return settingsRepository.save(s);
    }

    private boolean categoryAllowed(Long userId, PushCategory category) {
        UserNotificationSettings s = settingsRepository.findById(userId).orElse(null);
        if (s == null) return true;
        return switch (category) {
            case RESULTS -> s.isResults();
            case REMINDERS -> s.isReminders();
            case MARKETING -> s.isMarketing();
        };
    }

    // ===================== INBOX =====================

    public Page<UserNotification> listInbox(Long userId, Pageable pageable, boolean unreadOnly) {
        return unreadOnly
                ? userNotificationRepository.findByUserIdAndReadAtIsNullOrderByCreatedAtDesc(userId, pageable)
                : userNotificationRepository.findByUserIdOrderByCreatedAtDesc(userId, pageable);
    }

    public long unreadCount(Long userId) {
        return userNotificationRepository.countByUserIdAndReadAtIsNull(userId);
    }

    @Transactional
    public void markRead(Long userId, Long notificationId) {
        UserNotification n = userNotificationRepository.findById(notificationId)
                .filter(x -> x.getUser().getId().equals(userId))
                .orElseThrow(() -> new AppException("Уведомление не найдено.", "Билдирүү табылган жок."));
        if (n.getReadAt() == null) {
            n.setReadAt(LocalDateTime.now());
            userNotificationRepository.save(n);
        }
    }

    @Transactional
    public void markAllRead(Long userId) {
        userNotificationRepository.markAllRead(userId, LocalDateTime.now());
    }

    // ===================== TRIGGERED NOTIFICATIONS =====================

    /** One user: always writes an inbox entry, sends a push when the category is enabled. */
    @Transactional
    public void notifyUser(Long userId, PushCategory category, PushMessages.Text text,
                           PushDataType type, Long entityId) {
        User u = userRepository.findById(userId).orElse(null);
        if (u == null) return;
        String lang = normLang(u.getLanguage());
        String title = text.title(lang);
        String body = text.body(lang);

        userNotificationRepository.save(UserNotification.builder()
                .user(u).title(title).body(body)
                .dataType(type).dataEntityId(entityId)
                .build());

        if (firebaseMessaging == null || !categoryAllowed(userId, category)) return;
        for (DeviceToken dt : deviceTokenRepository.findByUserId(userId)) {
            sendSingle(dt.getToken(), title, body, type, entityId);
        }
    }

    /** Fan-out to many users off the request thread (e.g. "new sub-test in a purchased test"). */
    @Async
    public void notifyUsersAsync(List<Long> userIds, PushCategory category, PushMessages.Text text,
                                 PushDataType type, Long entityId) {
        for (Long id : new HashSet<>(userIds)) {
            try {
                self.notifyUser(id, category, text, type, entityId);
            } catch (Exception e) {
                log.warn("notifyUser failed for userId={}: {}", id, e.getMessage());
            }
        }
    }

    // ===================== ADMIN BROADCASTS =====================

    public PushBroadcast enqueueBroadcast(Long adminUserId, BroadcastRequest r) {
        validate(r);

        boolean scheduled = r.getScheduledAt() != null && r.getScheduledAt().isAfter(LocalDateTime.now());
        if (!scheduled && firebaseMessaging == null) {
            throw new AppException("Push-уведомления не настроены.", "Push-билдирүүлөр тууралоо эмес.");
        }

        String dedupKey = dedupKey(r);
        PushBroadcast existing = pushBroadcastRepository.findByDedupKey(dedupKey).orElse(null);
        if (existing != null) return existing;

        User admin = userRepository.findById(adminUserId).orElse(null);
        PushBroadcast b = PushBroadcast.builder()
                .title(r.getTitle())
                .body(r.getBody())
                .titleKy(r.getTitleKy())
                .bodyKy(r.getBodyKy())
                .status(scheduled ? BroadcastStatus.SCHEDULED : BroadcastStatus.PENDING)
                .audience(r.getAudience() != null ? r.getAudience() : BroadcastAudience.ALL)
                .audienceRef(r.getAudienceRef())
                .dataType(r.getDataType() != null ? r.getDataType() : PushDataType.NONE)
                .dataEntityId(r.getDataEntityId())
                .sentBy(admin)
                .scheduledAt(r.getScheduledAt())
                .dedupKey(dedupKey)
                .build();
        try {
            b = pushBroadcastRepository.save(b);
        } catch (DataIntegrityViolationException dup) {
            return pushBroadcastRepository.findByDedupKey(dedupKey).orElseThrow(() -> dup);
        }

        if (!scheduled) {
            self.executeBroadcast(b.getId());
        }
        return b;
    }

    @Transactional
    public void cancelScheduled(Long broadcastId) {
        PushBroadcast b = pushBroadcastRepository.findById(broadcastId)
                .orElseThrow(() -> new AppException("Рассылка не найдена.", "Жиберүү табылган жок."));
        if (b.getStatus() != BroadcastStatus.SCHEDULED) {
            throw new AppException("Отменить можно только запланированную рассылку.",
                    "Пландалган жиберүүнү гана жокко чыгарууга болот.");
        }
        b.setStatus(BroadcastStatus.CANCELLED);
        pushBroadcastRepository.save(b);
    }

    public PushBroadcast getBroadcast(Long id) {
        return pushBroadcastRepository.findById(id)
                .orElseThrow(() -> new AppException("Рассылка не найдена.", "Жиберүү табылган жок."));
    }

    public Page<PushBroadcast> getBroadcastHistory(Pageable pageable) {
        return pushBroadcastRepository.findAllByOrderByCreatedAtDesc(pageable);
    }

    public PushStatusResponse status() {
        Map<String, Long> byPlatform = new HashMap<>();
        for (DevicePlatform p : DevicePlatform.values()) {
            byPlatform.put(p.name(), deviceTokenRepository.countByPlatform(p));
        }
        PushBroadcast last = pushBroadcastRepository.findFirstByStatusOrderByFinishedAtDesc(BroadcastStatus.SENT);
        return new PushStatusResponse(
                firebaseMessaging != null,
                deviceTokenRepository.count(),
                byPlatform,
                deviceTokenRepository.findDistinctUserIds().size(),
                pushBroadcastRepository.countByStatus(BroadcastStatus.SCHEDULED),
                last != null ? last.getFinishedAt() : null);
    }

    /** Runs on the async pool; not transactional — each save() commits on its own. */
    @Async
    public void executeBroadcast(Long broadcastId) {
        PushBroadcast b = pushBroadcastRepository.findById(broadcastId).orElse(null);
        if (b == null || b.getStatus() == BroadcastStatus.CANCELLED) return;

        b.setStatus(BroadcastStatus.SENDING);
        b.setStartedAt(LocalDateTime.now());
        pushBroadcastRepository.save(b);

        try {
            if (firebaseMessaging == null) {
                finish(b, BroadcastStatus.FAILED, 0, 0, 0);
                return;
            }

            List<Long> userIds = resolveAudience(b.getAudience(), b.getAudienceRef());
            userIds = dropMarketingOptOuts(userIds);

            List<TokenLangView> tokens = new ArrayList<>();
            for (List<Long> chunk : chunk(userIds, SQL_IN_CHUNK)) {
                tokens.addAll(deviceTokenRepository.findTokensWithLangByUserIds(chunk));
            }

            Map<String, List<String>> byLang = tokens.stream().collect(Collectors.groupingBy(
                    t -> normLang(t.getLang()),
                    Collectors.mapping(TokenLangView::getToken, Collectors.toList())));

            int success = 0;
            int failure = 0;
            List<String> stale = new ArrayList<>();

            for (Map.Entry<String, List<String>> e : byLang.entrySet()) {
                String lang = e.getKey();
                String title = "KY".equals(lang) ? coalesce(b.getTitleKy(), b.getTitle()) : b.getTitle();
                String body = "KY".equals(lang) ? coalesce(b.getBodyKy(), b.getBody()) : b.getBody();
                List<String> langTokens = e.getValue();

                for (int i = 0; i < langTokens.size(); i += FCM_BATCH_SIZE) {
                    List<String> batch = langTokens.subList(i, Math.min(i + FCM_BATCH_SIZE, langTokens.size()));
                    MulticastMessage.Builder mm = MulticastMessage.builder()
                            .addAllTokens(batch)
                            .setNotification(Notification.builder().setTitle(title).setBody(body).build());
                    applyData(mm, b.getDataType(), b.getDataEntityId());
                    try {
                        BatchResponse resp = firebaseMessaging.sendEachForMulticast(mm.build());
                        success += resp.getSuccessCount();
                        failure += resp.getFailureCount();
                        List<SendResponse> rs = resp.getResponses();
                        for (int j = 0; j < rs.size(); j++) {
                            if (!rs.get(j).isSuccessful() && isStale(rs.get(j))) {
                                stale.add(batch.get(j));
                            }
                        }
                    } catch (FirebaseMessagingException ex) {
                        log.error("FCM batch send failed", ex);
                        failure += batch.size();
                    }
                }
            }

            for (List<String> chunk : chunk(stale, SQL_IN_CHUNK)) {
                deviceTokenRepository.deleteByTokenIn(chunk);
            }
            if (!stale.isEmpty()) log.info("Removed {} stale FCM tokens", stale.size());

            writeBroadcastInbox(userIds, b);
            finish(b, BroadcastStatus.SENT, tokens.size(), success, failure);

        } catch (Exception ex) {
            log.error("Broadcast {} failed", broadcastId, ex);
            finish(b, BroadcastStatus.FAILED,
                    b.getRecipientCount() != null ? b.getRecipientCount() : 0,
                    b.getSuccessCount() != null ? b.getSuccessCount() : 0,
                    b.getFailureCount() != null ? b.getFailureCount() : 0);
        }
    }

    private void finish(PushBroadcast b, BroadcastStatus status, int recipients, int success, int failure) {
        b.setStatus(status);
        b.setRecipientCount(recipients);
        b.setSuccessCount(success);
        b.setFailureCount(failure);
        b.setFinishedAt(LocalDateTime.now());
        pushBroadcastRepository.save(b);
    }

    private void writeBroadcastInbox(List<Long> userIds, PushBroadcast b) {
        for (List<Long> chunk : chunk(userIds, SQL_IN_CHUNK)) {
            List<UserNotification> batch = new ArrayList<>();
            for (User u : userRepository.findAllById(chunk)) {
                String lang = normLang(u.getLanguage());
                String title = "KY".equals(lang) ? coalesce(b.getTitleKy(), b.getTitle()) : b.getTitle();
                String body = "KY".equals(lang) ? coalesce(b.getBodyKy(), b.getBody()) : b.getBody();
                batch.add(UserNotification.builder()
                        .user(u).title(title).body(body)
                        .dataType(b.getDataType()).dataEntityId(b.getDataEntityId())
                        .broadcastId(b.getId())
                        .build());
            }
            userNotificationRepository.saveAll(batch);
        }
    }

    private List<Long> dropMarketingOptOuts(List<Long> userIds) {
        if (userIds.isEmpty()) return userIds;
        Set<Long> optedOut = new HashSet<>();
        for (List<Long> chunk : chunk(userIds, SQL_IN_CHUNK)) {
            settingsRepository.findByUserIdInAndMarketingFalse(chunk)
                    .forEach(s -> optedOut.add(s.getUserId()));
        }
        return userIds.stream().filter(id -> !optedOut.contains(id)).toList();
    }

    private List<Long> resolveAudience(BroadcastAudience audience, String ref) {
        return switch (audience) {
            case ALL -> deviceTokenRepository.findDistinctUserIds();
            case USER_IDS -> parseCsvLongs(ref);
            case PLATFORM -> deviceTokenRepository.findDistinctUserIdsByPlatform(
                    DevicePlatform.valueOf(ref.trim().toUpperCase()));
            case PURCHASED_TEST -> userTestAccessRepository.findUserIdsByTestId(Long.parseLong(ref.trim()));
            case INACTIVE_DAYS -> {
                int days = Integer.parseInt(ref.trim());
                LocalDateTime cutoff = LocalDateTime.now().minusDays(days);
                Set<Long> active = new HashSet<>(
                        testSessionRepository.findUserIdsWithCompletedSessionAfter(cutoff));
                yield deviceTokenRepository.findDistinctUserIds().stream()
                        .filter(id -> !active.contains(id))
                        .toList();
            }
        };
    }

    private void validate(BroadcastRequest r) {
        BroadcastAudience a = r.getAudience() != null ? r.getAudience() : BroadcastAudience.ALL;
        if (a == BroadcastAudience.ALL) return;
        if (r.getAudienceRef() == null || r.getAudienceRef().isBlank()) {
            throw new AppException("Для этой аудитории нужен параметр audienceRef.",
                    "Бул аудитория үчүн audienceRef параметри керек.");
        }
        try {
            switch (a) {
                case USER_IDS -> {
                    if (parseCsvLongs(r.getAudienceRef()).isEmpty()) throw new IllegalArgumentException();
                }
                case PLATFORM -> DevicePlatform.valueOf(r.getAudienceRef().trim().toUpperCase());
                case PURCHASED_TEST, INACTIVE_DAYS -> Long.parseLong(r.getAudienceRef().trim());
                default -> { }
            }
        } catch (RuntimeException ex) {
            throw new AppException("Неверный audienceRef для выбранной аудитории.",
                    "Тандалган аудитория үчүн audienceRef туура эмес.");
        }
    }

    // ===================== SCHEDULED JOBS =====================

    @Scheduled(cron = "0 * * * * *")
    public void dispatchScheduledBroadcasts() {
        List<PushBroadcast> due = pushBroadcastRepository
                .findByStatusAndScheduledAtLessThanEqual(BroadcastStatus.SCHEDULED, LocalDateTime.now());
        for (PushBroadcast b : due) {
            self.executeBroadcast(b.getId());
        }
    }

    @Scheduled(cron = "0 0 3 * * *")
    @Transactional
    public void pruneStaleTokens() {
        int removed = deviceTokenRepository.deleteByLastSeenAtBefore(
                LocalDateTime.now().minusDays(STALE_TOKEN_TTL_DAYS));
        if (removed > 0) log.info("Pruned {} stale device tokens", removed);
    }

    // Weekly (Mon 10:00) rather than daily, so a long-inactive user is nudged at most once a week.
    @Scheduled(cron = "0 0 10 * * MON")
    public void remindInactiveUsers() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(7);
        Set<Long> active = new HashSet<>(testSessionRepository.findUserIdsWithCompletedSessionAfter(cutoff));
        List<Long> inactive = deviceTokenRepository.findDistinctUserIds().stream()
                .filter(id -> !active.contains(id))
                .toList();
        if (inactive.isEmpty()) return;
        self.notifyUsersAsync(inactive, PushCategory.REMINDERS, PushMessages.inactiveReminder(),
                PushDataType.NONE, null);
    }

    // ===================== FCM HELPERS =====================

    private void sendSingle(String token, String title, String body, PushDataType type, Long entityId) {
        if (firebaseMessaging == null) return;
        Message.Builder m = Message.builder()
                .setToken(token)
                .setNotification(Notification.builder().setTitle(title).setBody(body).build());
        if (type != null && type != PushDataType.NONE) {
            m.putData("type", type.name());
            if (entityId != null) m.putData("entityId", String.valueOf(entityId));
        }
        try {
            firebaseMessaging.send(m.build());
        } catch (FirebaseMessagingException e) {
            MessagingErrorCode code = e.getMessagingErrorCode();
            if (code == MessagingErrorCode.UNREGISTERED
                    || code == MessagingErrorCode.INVALID_ARGUMENT
                    || code == MessagingErrorCode.SENDER_ID_MISMATCH) {
                deviceTokenRepository.deleteByToken(token);
            } else {
                log.warn("FCM send failed for one token: {}", e.getMessage());
            }
        }
    }

    private static void applyData(MulticastMessage.Builder mm, PushDataType type, Long entityId) {
        if (type != null && type != PushDataType.NONE) {
            mm.putData("type", type.name());
            if (entityId != null) mm.putData("entityId", String.valueOf(entityId));
        }
    }

    private static boolean isStale(SendResponse r) {
        if (r.getException() == null) return false;
        MessagingErrorCode code = r.getException().getMessagingErrorCode();
        return code == MessagingErrorCode.UNREGISTERED
                || code == MessagingErrorCode.INVALID_ARGUMENT
                || code == MessagingErrorCode.SENDER_ID_MISMATCH;
    }

    // ===================== MISC HELPERS =====================

    private static String normLang(String lang) {
        return "KY".equalsIgnoreCase(lang) ? "KY" : "RU";
    }

    private static String coalesce(String a, String b) {
        return a != null && !a.isBlank() ? a : b;
    }

    private static List<Long> parseCsvLongs(String csv) {
        List<Long> out = new ArrayList<>();
        if (csv == null) return out;
        for (String part : csv.split(",")) {
            String t = part.trim();
            if (!t.isEmpty()) out.add(Long.parseLong(t));
        }
        return out;
    }

    private static <T> List<List<T>> chunk(List<T> list, int size) {
        List<List<T>> out = new ArrayList<>();
        for (int i = 0; i < list.size(); i += size) {
            out.add(list.subList(i, Math.min(i + size, list.size())));
        }
        return out;
    }

    private static String dedupKey(BroadcastRequest r) {
        BroadcastAudience a = r.getAudience() != null ? r.getAudience() : BroadcastAudience.ALL;
        long epochMinute = System.currentTimeMillis() / 60_000L;
        String raw = a + "|" + r.getAudienceRef() + "|" + r.getTitle() + "|" + r.getBody() + "|" + epochMinute;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte x : digest) sb.append(String.format("%02x", x));
            return sb.substring(0, 64);
        } catch (Exception e) {
            return Integer.toHexString(raw.hashCode());
        }
    }
}
