package synamyk.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import synamyk.dto.push.BroadcastDetailResponse;
import synamyk.dto.push.BroadcastHistoryEntry;
import synamyk.dto.push.BroadcastRequest;
import synamyk.dto.push.BroadcastResultResponse;
import synamyk.dto.push.PushStatusResponse;
import synamyk.entities.PushBroadcast;
import synamyk.entities.User;
import synamyk.service.PushNotificationService;

@RestController
@RequestMapping("/api/admin/notifications")
@RequiredArgsConstructor
@Tag(name = "Админ — Уведомления", description = "Push-рассылки: таргетинг, планирование, история. Требуется роль ADMIN.")
@SecurityRequirement(name = "Bearer")
public class AdminNotificationController {

    private final PushNotificationService pushNotificationService;

    @PostMapping("/broadcast")
    @Operation(
            summary = "Поставить push-рассылку в очередь",
            description = "Возвращает 202 сразу — отправка идёт асинхронно. Опрашивайте GET /broadcast/{id} для прогресса. " +
                    "Аудитория: ALL | USER_IDS | PLATFORM | PURCHASED_TEST | INACTIVE_DAYS (параметр в audienceRef). " +
                    "Если scheduledAt в будущем — рассылка планируется. Одинаковые запросы в пределах минуты дедуплицируются."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "202", description = "Рассылка принята"),
            @ApiResponse(responseCode = "400", description = "Push не настроен / неверный audienceRef"),
            @ApiResponse(responseCode = "403", description = "Требуется роль ADMIN")
    })
    public ResponseEntity<BroadcastResultResponse> broadcast(
            @AuthenticationPrincipal User admin,
            @Valid @RequestBody BroadcastRequest req) {
        PushBroadcast b = pushNotificationService.enqueueBroadcast(admin.getId(), req);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(new BroadcastResultResponse(
                b.getId(), b.getStatus(), b.getAudience(), b.getScheduledAt(), b.getCreatedAt()));
    }

    @GetMapping("/broadcast/{id}")
    @Operation(summary = "Детали и прогресс рассылки")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Рассылка"),
            @ApiResponse(responseCode = "400", description = "Не найдена")
    })
    public ResponseEntity<BroadcastDetailResponse> getBroadcast(@PathVariable Long id) {
        return ResponseEntity.ok(BroadcastDetailResponse.of(pushNotificationService.getBroadcast(id)));
    }

    @DeleteMapping("/broadcast/{id}")
    @Operation(summary = "Отменить запланированную рассылку",
            description = "Разрешено только при статусе SCHEDULED.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Отменена"),
            @ApiResponse(responseCode = "400", description = "Нельзя отменить в текущем статусе")
    })
    public ResponseEntity<Void> cancel(@PathVariable Long id) {
        pushNotificationService.cancelScheduled(id);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/broadcast")
    @Operation(summary = "История рассылок", description = "Список отправленных и запланированных рассылок, от новых к старым.")
    public ResponseEntity<Page<BroadcastHistoryEntry>> history(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<PushBroadcast> results = pushNotificationService.getBroadcastHistory(PageRequest.of(page, size));
        return ResponseEntity.ok(results.map(b -> new BroadcastHistoryEntry(
                b.getId(),
                b.getTitle(),
                b.getBody(),
                b.getStatus(),
                b.getAudience(),
                b.getSentBy() != null
                        ? ((safe(b.getSentBy().getFirstName()) + " " + safe(b.getSentBy().getLastName())).trim())
                        : "—",
                b.getRecipientCount(),
                b.getSuccessCount(),
                b.getFailureCount(),
                b.getScheduledAt(),
                b.getCreatedAt())));
    }

    @GetMapping("/status")
    @Operation(summary = "Статус push-подсистемы и метрики токенов")
    public ResponseEntity<PushStatusResponse> status() {
        return ResponseEntity.ok(pushNotificationService.status());
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
