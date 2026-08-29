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
import synamyk.entities.SubTest;
import synamyk.entities.Test;
import synamyk.entities.User;
import synamyk.entities.UserSubTestAccess;
import synamyk.entities.UserTestAccess;
import synamyk.config.FinikConfig;
import synamyk.exception.AppException;
import synamyk.repo.PaymentRepository;
import synamyk.repo.SubTestRepository;
import synamyk.repo.TestRepository;
import synamyk.repo.UserRepository;
import synamyk.repo.UserSubTestAccessRepository;
import synamyk.repo.UserTestAccessRepository;

import java.math.BigDecimal;
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
    private final SubTestRepository subTestRepository;
    private final UserTestAccessRepository accessRepository;
    private final UserSubTestAccessRepository subTestAccessRepository;
    private final AccessResolver accessResolver;
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
    /** Current user's payment history, newest first. PENDING (abandoned) attempts are hidden. */
    @Transactional(readOnly = true)
    public Page<PaymentHistoryEntry> getMyPayments(Long userId, String lang, Pageable pageable) {
        return paymentRepository.findMyPayments(userId, Payment.PaymentStatus.PENDING, pageable)
                .map(p -> new PaymentHistoryEntry(
                        p.getPaymentId(),
                        p.getTest().getId(),
                        L10n.pick(p.getTest().getTitle(), p.getTest().getTitleKy(), lang),
                        p.getSubTest() != null ? p.getSubTest().getId() : null,
                        p.getSubTest() != null
                                ? L10n.pick(p.getSubTest().getTitle(), p.getSubTest().getTitleKy(), lang)
                                : null,
                        p.getAmount(),
                        p.getStatus().name(),
                        p.getReceiptNumber(),
                        p.getPaymentUrl(),
                        p.getCreatedAt(),
                        p.getPaidAt()));
    }

    /** Buy access to the whole test (bundle) — unchanged behaviour. */
    @Transactional
    public InitPaymentResponse initPayment(Long userId, Long testId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));

        Test test = testRepository.findById(testId)
                .orElseThrow(() -> new RuntimeException("Test not found"));

        if (accessRepository.existsActiveAccess(userId, testId, LocalDateTime.now())) {
            throw new AppException("Уже куплено.", "Мурунтан эле сатып алынган.");
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
        log.info("Payment record created (bundle): paymentId={}, userId={}, testId={}", paymentId, userId, testId);

        return InitPaymentResponse.builder()
                .paymentId(paymentId)
                .amount(test.getPrice())
                .nameEn(truncate(test.getTitle(), 50))
                .callbackUrl(finikConfig.getWebhookUrl())
                .build();
    }

    /** Buy access to a single sub-test. */
    @Transactional
    public InitPaymentResponse initPaymentSubTest(Long userId, Long subTestId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));

        SubTest subTest = subTestRepository.findById(subTestId)
                .orElseThrow(() -> new AppException("Подтест не найден.", "Подтест табылган жок."));

        if (!Boolean.TRUE.equals(subTest.getActive())) {
            throw new AppException("Подтест недоступен.", "Подтест жеткиликтүү эмес.");
        }
        BigDecimal price = subTest.getPrice();
        if (!Boolean.TRUE.equals(subTest.getIsPaid()) || price == null || price.signum() <= 0) {
            throw new AppException("Этот подтест не продаётся.", "Бул подтест сатылбайт.");
        }
        if (accessResolver.hasSubTestAccess(userId, subTest, LocalDateTime.now())) {
            throw new AppException("Уже куплено.", "Мурунтан эле сатып алынган.");
        }

        Test test = subTest.getTest();
        UUID paymentId = UUID.randomUUID();

        Payment payment = Payment.builder()
                .user(user)
                .test(test)
                .subTest(subTest)
                .paymentId(paymentId)
                .amount(price)
                .status(Payment.PaymentStatus.PENDING)
                .build();

        paymentRepository.save(payment);
        log.info("Payment record created (sub-test): paymentId={}, userId={}, subTestId={}", paymentId, userId, subTestId);

        return InitPaymentResponse.builder()
                .paymentId(paymentId)
                .amount(price)
                .nameEn(truncate(test.getTitle() + " — " + subTest.getTitle(), 50))
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

        grantAccess(payment);

        log.info("Payment completed: paymentId={}, userId={}, testId={}, subTestId={}",
                payment.getPaymentId(), payment.getUser().getId(), payment.getTest().getId(),
                payment.getSubTest() != null ? payment.getSubTest().getId() : null);
    }

    /** Route a completed payment to the right access grant. */
    private void grantAccess(Payment payment) {
        if (payment.getSubTest() != null) {
            User user = payment.getUser();
            SubTest subTest = payment.getSubTest();
            UserSubTestAccess access = subTestAccessRepository
                    .findByUserIdAndSubTestId(user.getId(), subTest.getId())
                    .orElseGet(() -> UserSubTestAccess.builder().user(user).subTest(subTest).build());
            access.setGrantedAt(LocalDateTime.now());
            access.setExpiresAt(null); // a purchase grants permanent access
            subTestAccessRepository.save(access);
            log.info("Sub-test access granted (permanent): userId={}, subTestId={}", user.getId(), subTest.getId());
        } else {
            grantTestAccess(payment.getUser(), payment.getTest());
        }
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
        UserTestAccess access = accessRepository.findByUserIdAndTestId(user.getId(), test.getId())
                .orElseGet(() -> UserTestAccess.builder().user(user).test(test).build());
        access.setGrantedAt(LocalDateTime.now());
        access.setExpiresAt(null); // a purchase grants permanent access
        accessRepository.save(access);

        log.info("Test access granted (permanent): userId={}, testId={}", user.getId(), test.getId());
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