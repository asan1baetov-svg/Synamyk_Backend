package synamyk.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import synamyk.dto.CreatePaymentResponse;
import synamyk.dto.InitPaymentResponse;
import synamyk.dto.PaymentHistoryEntry;
import synamyk.entities.User;
import synamyk.service.PaymentService;

import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/payments")
@RequiredArgsConstructor
@Tag(name = "Payments", description = "Finik payment endpoints")
@SecurityRequirement(name = "Bearer")
public class PaymentController {

    private final PaymentService paymentService;

    /**
     * Step 1 for Flutter finik_sdk integration.
     *
     * Flutter usage:
     *   1. Call POST /api/payments/init?testId={id}
     *   2. Use the returned values in CreateItemHandlerWidget:
     *      - requestId = paymentId
     *      - accountId = accountId
     *      - amount = FixedAmount(amount)
     *      - nameEn = nameEn
     *      - callbackUrl = callbackUrl
     *      - requiredFields = [RequiredField(fieldId: "paymentId", value: paymentId.toString(), isHidden: true)]
     */
    @PostMapping("/init")
    @Operation(
            summary = "Инициировать платеж",
            description = "Создает запись платежа в БД и возвращает параметры для Flutter ")
    public ResponseEntity<InitPaymentResponse> initPayment(
            @RequestParam Long testId,
            Authentication authentication) {
        User user = (User) authentication.getPrincipal();
        return ResponseEntity.ok(paymentService.initPayment(user.getId(), testId));
    }

    @GetMapping("/my")
    @Operation(
            summary = "История платежей текущего пользователя",
            description = "Страница платежей, от новых к старым. Статусы: PENDING, COMPLETED, EXPIRED, CANCELLED. " +
                    "Название теста локализовано по языку пользователя.")
    public ResponseEntity<Page<PaymentHistoryEntry>> myPayments(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            Authentication authentication) {
        User user = (User) authentication.getPrincipal();
        int capped = Math.min(Math.max(size, 1), 100);
        return ResponseEntity.ok(paymentService.getMyPayments(
                user.getId(), user.getLanguage(), PageRequest.of(page, capped)));
    }

    @GetMapping("/{paymentId}/status")
    @Operation(
            summary = "Статус платежа",
            description = "Возвращает текущий статус платежа по paymentId, полученному из /api/payments/init. " +
                    "Используйте для опроса (polling) после оплаты через Finik SDK, пока статус не станет COMPLETED. " +
                    "Статусы: PENDING, COMPLETED, EXPIRED, CANCELLED.")
    public ResponseEntity<CreatePaymentResponse> getPaymentStatus(
            @PathVariable UUID paymentId,
            Authentication authentication) {
        User user = (User) authentication.getPrincipal();
        return ResponseEntity.ok(paymentService.getPaymentStatus(paymentId, user.getId()));
    }

}
