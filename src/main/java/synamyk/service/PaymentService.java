package synamyk.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import synamyk.dto.CreatePaymentResponse;
import synamyk.dto.InitPaymentResponse;
import synamyk.dto.PaymentHistoryEntry;
import synamyk.dto.WebhookData;
import synamyk.util.L10n;
import synamyk.entities.Payment;
import synamyk.entities.Test;
import synamyk.entities.User;
import synamyk.entities.UserTestAccess;
import synamyk.config.FinikConfig;
import synamyk.exception.AppException;
import synamyk.repo.PaymentRepository;
import synamyk.repo.TestRepository;
import synamyk.repo.UserRepository;
import synamyk.repo.UserTestAccessRepository;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentService {

    private final PaymentRepository paymentRepository;
    private final UserRepository userRepository;
    private final TestRepository testRepository;
    private final UserTestAccessRepository accessRepository;
    private final FinikConfig finikConfig;

    /**
     * Step 1 for Flutter SDK: create a Payment record in DB and return config
     * for the Flutter finik_sdk (CreateItemHandlerWidget).
     *
     * Flutter should:
     *   - pass paymentId as `requestId`
     *   - pass paymentId in `requiredFields` as a hidden field so it comes back in webhook fields
     *   - pass callbackUrl as `callbackUrl`
     */
    /** Current user's payment history, newest first. */
    @Transactional(readOnly = true)
    public Page<PaymentHistoryEntry> getMyPayments(Long userId, String lang, Pageable pageable) {
        return paymentRepository.findMyPayments(userId, pageable)
                .map(p -> new PaymentHistoryEntry(
                        p.getPaymentId(),
                        p.getTest().getId(),
                        L10n.pick(p.getTest().getTitle(), p.getTest().getTitleKy(), lang),
                        p.getAmount(),
                        p.getStatus().name(),
                        p.getReceiptNumber(),
                        p.getPaymentUrl(),
                        p.getCreatedAt(),
                        p.getPaidAt()));
    }

    @Transactional
    public InitPaymentResponse initPayment(Long userId, Long testId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));

        Test test = testRepository.findById(testId)
                .orElseThrow(() -> new RuntimeException("Test not found"));

        if (accessRepository.existsByUserIdAndTestId(userId, testId)) {
            throw new RuntimeException("Test already purchased.");
        }

        if (paymentRepository.existsByUserIdAndTestIdAndStatus(userId, testId, Payment.PaymentStatus.COMPLETED)) {
            throw new RuntimeException("Test already paid.");
        }

        UUID paymentId = UUID.randomUUID();

        Payment payment = Payment.builder()
                .user(user)
                .test(test)
                .paymentId(paymentId)
                .amount(test.getPrice())
                .status(Payment.PaymentStatus.PENDING)
                .build();

        paymentRepository.save(payment);
        log.info("Payment record created: paymentId={}, userId={}, testId={}", paymentId, userId, testId);

        return InitPaymentResponse.builder()
                .paymentId(paymentId)
                .amount(test.getPrice())
                .nameEn(truncate(test.getTitle(), 50))
                .callbackUrl(finikConfig.getWebhookUrl())
                .build();
    }

    private String truncate(String str, int max) {
        return str != null && str.length() > max ? str.substring(0, max) : str;
    }

    @Transactional
    public void processWebhook(WebhookData webhookData, String rawJson) {
        String transactionId = webhookData.getTransactionId();
        log.info("Processing webhook: transactionId={}, status={}", transactionId, webhookData.getStatus());

        if (paymentRepository.findByTransactionId(transactionId).isPresent()) {
            log.info("Webhook already processed: {}", transactionId);
            return;
        }

        if (!"SUCCEEDED".equals(webhookData.getStatus())) {
            log.warn("Unexpected webhook status: {}", webhookData.getStatus());
            return;
        }

        Payment payment = findPaymentForWebhook(webhookData);
        if (payment == null) {
            log.error("Payment not found for webhook: transactionId={}", transactionId);
            return;
        }

        if (payment.getStatus() == Payment.PaymentStatus.COMPLETED) {
            log.warn("Payment already completed: paymentId={}", payment.getPaymentId());
            return;
        }

        payment.setStatus(Payment.PaymentStatus.COMPLETED);
        payment.setPaidAt(LocalDateTime.now());
        payment.setTransactionId(transactionId);
        payment.setReceiptNumber(webhookData.getReceiptNumber());
        payment.setWebhookData(rawJson);
        paymentRepository.save(payment);

        grantTestAccess(payment.getUser(), payment.getTest());

        log.info("Payment completed: paymentId={}, userId={}, testId={}",
                payment.getPaymentId(), payment.getUser().getId(), payment.getTest().getId());
    }

    private Payment findPaymentForWebhook(WebhookData webhookData) {
        // Primary: look up by paymentId passed as requiredField from Flutter SDK
        Map<String, Object> fields = webhookData.getFields();
        if (fields != null && fields.get("paymentId") != null) {
            String paymentIdStr = fields.get("paymentId").toString();
            try {
                UUID paymentId = UUID.fromString(paymentIdStr);
                return paymentRepository.findByPaymentId(paymentId).orElse(null);
            } catch (IllegalArgumentException e) {
                log.warn("Invalid paymentId in webhook fields: {}", paymentIdStr);
            }
        }

        log.error("Cannot match webhook to payment: no paymentId in fields. transactionId={}",
                webhookData.getTransactionId());
        return null;
    }

    @Transactional
    protected void grantTestAccess(User user, Test test) {
        if (accessRepository.existsByUserIdAndTestId(user.getId(), test.getId())) {
            log.info("Access already exists: userId={}, testId={}", user.getId(), test.getId());
            return;
        }

        UserTestAccess access = UserTestAccess.builder()
                .user(user)
                .test(test)
                .grantedAt(LocalDateTime.now())
                .build();
        accessRepository.save(access);

        log.info("Test access granted: userId={}, testId={}", user.getId(), test.getId());
    }

    public CreatePaymentResponse getPaymentStatus(UUID paymentId, Long userId) {
        Payment payment = paymentRepository.findByPaymentId(paymentId)
                .orElseThrow(() -> new AppException("Платёж не найден.", "Төлөм табылган жок."));

        if (!payment.getUser().getId().equals(userId)) {
            throw new AppException("Нет доступа.", "Мүмкүнчүлүк жок.");
        }

        return new CreatePaymentResponse(payment.getPaymentId(), payment.getPaymentUrl(), payment.getStatus().name());
    }
}