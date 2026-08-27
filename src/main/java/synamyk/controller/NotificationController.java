package synamyk.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import synamyk.dto.push.NotificationSettingsRequest;
import synamyk.dto.push.NotificationSettingsResponse;
import synamyk.dto.push.RegisterDeviceRequest;
import synamyk.dto.push.UnreadCountResponse;
import synamyk.dto.push.UserNotificationEntry;
import synamyk.entities.User;
import synamyk.service.PushNotificationService;

@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
@Tag(name = "Уведомления", description = "Регистрация устройства, настройки и in-app инбокс уведомлений")
@SecurityRequirement(name = "Bearer")
public class NotificationController {

    private final PushNotificationService pushNotificationService;

    @PostMapping("/device-token")
    @Operation(
            summary = "Зарегистрировать FCM-токен устройства",
            description = "Вызывается клиентом после получения FCM-токена. Если токен уже зарегистрирован " +
                    "за другим пользователем (переустановка / смена аккаунта), он переназначается текущему."
    )
    @ApiResponse(responseCode = "200", description = "Токен зарегистрирован")
    public ResponseEntity<Void> register(
            @AuthenticationPrincipal User user,
            @Valid @RequestBody RegisterDeviceRequest req) {
        pushNotificationService.registerDevice(user.getId(), req.getToken(), req.getPlatform());
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/device-token")
    @Operation(summary = "Удалить токен устройства", description = "Вызывается при выходе из аккаунта, до очистки JWT.")
    @ApiResponse(responseCode = "200", description = "Токен удалён")
    public ResponseEntity<Void> unregister(
            @Parameter(description = "FCM-токен") @RequestParam String token) {
        pushNotificationService.unregisterDevice(token);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/settings")
    @Operation(summary = "Настройки уведомлений текущего пользователя",
            description = "Создаёт строку с дефолтами (всё включено) при первом обращении.")
    public ResponseEntity<NotificationSettingsResponse> getSettings(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(NotificationSettingsResponse.of(
                pushNotificationService.getOrCreateSettings(user.getId())));
    }

    @PatchMapping("/settings")
    @Operation(summary = "Изменить настройки уведомлений",
            description = "Частичное обновление: передавайте только изменяемые поля.")
    public ResponseEntity<NotificationSettingsResponse> updateSettings(
            @AuthenticationPrincipal User user,
            @RequestBody NotificationSettingsRequest req) {
        return ResponseEntity.ok(NotificationSettingsResponse.of(
                pushNotificationService.updateSettings(user.getId(), req)));
    }

    @GetMapping
    @Operation(summary = "Инбокс уведомлений", description = "Список уведомлений пользователя, от новых к старым.")
    public ResponseEntity<Page<UserNotificationEntry>> inbox(
            @AuthenticationPrincipal User user,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "false") boolean unreadOnly) {
        int capped = Math.min(Math.max(size, 1), 100);
        return ResponseEntity.ok(pushNotificationService
                .listInbox(user.getId(), PageRequest.of(page, capped), unreadOnly)
                .map(UserNotificationEntry::of));
    }

    @GetMapping("/unread-count")
    @Operation(summary = "Число непрочитанных уведомлений")
    public ResponseEntity<UnreadCountResponse> unreadCount(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(new UnreadCountResponse(pushNotificationService.unreadCount(user.getId())));
    }

    @PostMapping("/{id}/read")
    @Operation(summary = "Отметить уведомление прочитанным")
    @ApiResponse(responseCode = "200", description = "Отмечено")
    public ResponseEntity<Void> markRead(@AuthenticationPrincipal User user, @PathVariable Long id) {
        pushNotificationService.markRead(user.getId(), id);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/read-all")
    @Operation(summary = "Отметить все уведомления прочитанными")
    @ApiResponse(responseCode = "200", description = "Отмечено")
    public ResponseEntity<Void> markAllRead(@AuthenticationPrincipal User user) {
        pushNotificationService.markAllRead(user.getId());
        return ResponseEntity.ok().build();
    }
}
