package synamyk.service;

import com.google.firebase.messaging.FirebaseMessaging;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import synamyk.dto.push.BroadcastRequest;
import synamyk.entities.DeviceToken;
import synamyk.entities.PushBroadcast;
import synamyk.entities.User;
import synamyk.enums.BroadcastAudience;
import synamyk.enums.BroadcastStatus;
import synamyk.enums.DevicePlatform;
import synamyk.exception.AppException;
import synamyk.repo.DeviceTokenRepository;
import synamyk.repo.PushBroadcastRepository;
import synamyk.repo.TestSessionRepository;
import synamyk.repo.UserNotificationRepository;
import synamyk.repo.UserNotificationSettingsRepository;
import synamyk.repo.UserRepository;
import synamyk.repo.UserTestAccessRepository;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PushNotificationServiceTest {

    @Mock DeviceTokenRepository deviceTokenRepository;
    @Mock PushBroadcastRepository pushBroadcastRepository;
    @Mock UserRepository userRepository;
    @Mock UserNotificationRepository userNotificationRepository;
    @Mock UserNotificationSettingsRepository settingsRepository;
    @Mock TestSessionRepository testSessionRepository;
    @Mock UserTestAccessRepository userTestAccessRepository;

    @InjectMocks PushNotificationService service;

    @BeforeEach
    void wireSelf() {
        ReflectionTestUtils.setField(service, "self", service);
    }

    @Test
    void registerDevice_newToken_persistsWithLastSeen() {
        User u = new User();
        u.setId(1L);
        when(userRepository.findById(1L)).thenReturn(Optional.of(u));
        when(deviceTokenRepository.findByToken("t1")).thenReturn(Optional.empty());

        service.registerDevice(1L, "t1", DevicePlatform.ANDROID);

        ArgumentCaptor<DeviceToken> cap = ArgumentCaptor.forClass(DeviceToken.class);
        verify(deviceTokenRepository).save(cap.capture());
        assertThat(cap.getValue().getToken()).isEqualTo("t1");
        assertThat(cap.getValue().getUser()).isSameAs(u);
        assertThat(cap.getValue().getLastSeenAt()).isNotNull();
    }

    @Test
    void registerDevice_existingToken_reassignedAndTouched() {
        User u = new User();
        u.setId(2L);
        DeviceToken existing = DeviceToken.builder()
                .token("t1").platform(DevicePlatform.IOS)
                .lastSeenAt(LocalDateTime.now().minusDays(5))
                .build();
        when(userRepository.findById(2L)).thenReturn(Optional.of(u));
        when(deviceTokenRepository.findByToken("t1")).thenReturn(Optional.of(existing));

        service.registerDevice(2L, "t1", DevicePlatform.ANDROID);

        assertThat(existing.getUser()).isSameAs(u);
        assertThat(existing.getPlatform()).isEqualTo(DevicePlatform.ANDROID);
        assertThat(existing.getLastSeenAt()).isAfter(LocalDateTime.now().minusMinutes(1));
        verify(deviceTokenRepository).save(existing);
    }

    @Test
    void enqueueBroadcast_immediateWithoutFirebase_throws() {
        BroadcastRequest r = new BroadcastRequest();
        r.setTitle("t");
        r.setBody("b");

        assertThatThrownBy(() -> service.enqueueBroadcast(9L, r)).isInstanceOf(AppException.class);
        verify(pushBroadcastRepository, never()).save(any());
    }

    @Test
    void enqueueBroadcast_nonAllAudienceWithoutRef_throws() {
        ReflectionTestUtils.setField(service, "firebaseMessaging", mock(FirebaseMessaging.class));
        BroadcastRequest r = new BroadcastRequest();
        r.setTitle("t");
        r.setBody("b");
        r.setAudience(BroadcastAudience.PURCHASED_TEST);

        assertThatThrownBy(() -> service.enqueueBroadcast(1L, r)).isInstanceOf(AppException.class);
    }

    @Test
    void enqueueBroadcast_duplicateWithinMinute_returnsExisting() {
        ReflectionTestUtils.setField(service, "firebaseMessaging", mock(FirebaseMessaging.class));
        BroadcastRequest r = new BroadcastRequest();
        r.setTitle("t");
        r.setBody("b");
        PushBroadcast existing = new PushBroadcast();
        existing.setId(5L);
        when(pushBroadcastRepository.findByDedupKey(anyString())).thenReturn(Optional.of(existing));

        PushBroadcast out = service.enqueueBroadcast(9L, r);

        assertThat(out.getId()).isEqualTo(5L);
        verify(pushBroadcastRepository, never()).save(any());
    }

    @Test
    void executeBroadcast_firebaseUnavailable_marksFailed() {
        PushBroadcast b = new PushBroadcast();
        b.setId(7L);
        b.setStatus(BroadcastStatus.PENDING);
        b.setAudience(BroadcastAudience.ALL);
        when(pushBroadcastRepository.findById(7L)).thenReturn(Optional.of(b));
        when(pushBroadcastRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        service.executeBroadcast(7L);

        assertThat(b.getStatus()).isEqualTo(BroadcastStatus.FAILED);
        assertThat(b.getFinishedAt()).isNotNull();
    }
}
