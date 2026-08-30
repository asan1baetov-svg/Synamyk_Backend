package synamyk.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;
import synamyk.dto.OtpSendResponse;
import synamyk.dto.OtpVerifyResponse;
import synamyk.dto.VerifyOtpRequest;
import synamyk.entities.OTPCode;
import synamyk.entities.User;
import synamyk.exception.AppException;
import synamyk.repo.OtpCodeRepository;
import synamyk.repo.UserRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Random;

@Slf4j
@Service
@RequiredArgsConstructor
public class SmsProService {

    private final RestTemplate restTemplate;
    private final OtpCodeRepository otpCodeRepository;
    private final UserRepository userRepository;

    @Value("${sms.smspro.login}")
    private String login;

    @Value("${sms.smspro.password}")
    private String password;

    @Value("${sms.smspro.sender}")
    private String sender;

    @Value("${sms.smspro.enabled:true}")
    private boolean enabled;

    /** TEMPORARY: when true, any code passes OTP verification (for testing). */
    @Value("${sms.smspro.bypass:false}")
    private boolean bypass;

    @Value("${sms.smspro.ttl-minutes:5}")
    private int otpTtlMinutes;

    @Value("${sms.smspro.url}")
    private String smsUrl;

    @Transactional
    public OtpSendResponse sendOtp(String phone, OTPCode.OtpType type) {
        return sendOtp(phone, type, "RU");
    }

    @Transactional
    public OtpSendResponse sendOtp(String phone, OTPCode.OtpType type, String lang) {
        log.info("Sending OTP to phone: {} for type: {}", phone, type);

        String formattedPhone = formatPhoneNumber(phone);
        String otpCode = generateOtpCode();
        String messageId = generateMessageId();

        if (bypass) {
            log.warn("OTP BYPASS enabled — issuing test OTP without sending SMS for phone: {}", formattedPhone);
            return createTestOtp(formattedPhone, messageId, type, otpCode, lang);
        }

        // Check for existing active OTP
        otpCodeRepository.findFirstByPhoneAndTypeAndVerifiedFalseOrderByCreatedAtDesc(formattedPhone, type)
                .ifPresent(existingOtp -> {
                    if (!existingOtp.isExpired()) {
                        log.warn("Active OTP already exists for phone: {}", formattedPhone);
                        throw new AppException(
                                "OTP уже отправлен. Подождите перед повторным запросом.",
                                "OTP жөнөтүлдү. Жаңы код суроодон мурун күтүңүз.");
                    }
                });

        if (!enabled) {
            log.warn("SMS sending DISABLED. Test mode. OTP: {}", otpCode);
            return createTestOtp(formattedPhone, messageId, type, otpCode, lang);
        }

        try {
            String xmlRequest = String.format(
                    """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <message>
                        <login>%s</login>
                        <pwd>%s</pwd>
                        <id>%s</id>
                        <sender>%s</sender>
                        <text>Vash kod podtverjdeniya: %s</text>
                        <phones>
                            <phone>%s</phone>
                        </phones>
                    </message>
                    """,
                    login, password, messageId, sender, otpCode, formattedPhone
            );

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_XML);
            HttpEntity<String> entity = new HttpEntity<>(xmlRequest, headers);

            log.debug("Sending SMS to: {}, messageId: {}", formattedPhone, messageId);

            ResponseEntity<String> response = restTemplate.exchange(smsUrl, HttpMethod.POST, entity, String.class);
            String xmlResponse = response.getBody();

            if (xmlResponse == null || xmlResponse.isEmpty()) {
                throw new RuntimeException("Empty response from SMS service");
            }

            Integer status = extractXmlIntValue(xmlResponse, "status");
            String message = extractXmlStringValue(xmlResponse, "message");

            log.info("SMS API response - status: {}, message: {}", status, message);

            if (status == null || status != 0) {
                String errorMessage = getSmsErrorMessage(status, message);
                log.error("SMS API error: {}", errorMessage);
                throw new AppException(
                        "Ошибка отправки SMS: " + errorMessage,
                        "SMS жөнөтүүдө ката: " + errorMessage);
            }

            OTPCode otpCodeEntity = OTPCode.builder()
                    .phone(formattedPhone)
                    .code(otpCode)
                    .token(messageId)
                    .type(type)
                    .verified(false)
                    .expiresAt(LocalDateTime.now().plusMinutes(otpTtlMinutes))
                    .build();

            otpCodeEntity = otpCodeRepository.save(otpCodeEntity);
            log.info("OTP saved with id: {}", otpCodeEntity.getId());

            String sentMsg = ky(lang)
                    ? "Код ийгиликтүү жөнөтүлдү."
                    : "Код успешно отправлен.";

            return OtpSendResponse.builder()
                    .success(true)
                    .phone(formattedPhone)
                    .transactionId(messageId)
                    .token(messageId)
                    .expiresAt(otpCodeEntity.getExpiresAt())
                    .message(sentMsg)
                    .build();

        } catch (AppException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error sending OTP to {}: {}", phone, e.getMessage(), e);
            throw new AppException(
                    "Не удалось отправить SMS. Попробуйте позже.",
                    "SMS жөнөтүү мүмкүн болгон жок. Кийинчерээк аракет кылыңыз.");
        }
    }

    @Transactional
    public OtpVerifyResponse verifyOtp(VerifyOtpRequest request) {
        return verifyOtp(request, "RU");
    }

    @Transactional
    public OtpVerifyResponse verifyOtp(VerifyOtpRequest request, String lang) {
        String formattedPhone = formatPhoneNumber(request.getPhone());
        log.info("Verifying OTP for phone: {}, type: {}", formattedPhone, request.getType());

        List<OTPCode> otpCodes = otpCodeRepository
                .findByPhoneAndTypeAndVerifiedFalseOrderByCreatedAtDesc(formattedPhone, request.getType());

        if (otpCodes.isEmpty()) {
            throw new AppException(
                    "OTP не найден или уже подтверждён.",
                    "OTP табылган жок же мурунтан тастыкталган.");
        }

        OTPCode otpCode = otpCodes.get(0);

        // Invalidate older codes
        if (otpCodes.size() > 1) {
            for (int i = 1; i < otpCodes.size(); i++) {
                OTPCode oldCode = otpCodes.get(i);
                oldCode.setVerified(true);
                otpCodeRepository.save(oldCode);
            }
        }

        if (otpCode.isExpired()) {
            log.warn("OTP expired for phone: {}", formattedPhone);
            throw new AppException(
                    "Срок действия кода истёк. Запросите новый.",
                    "Код мөөнөтү өттү. Жаңы код суранычы.");
        }

        if (otpCode.getAttempts() >= 5) {
            log.warn("Too many OTP attempts for phone: {}", formattedPhone);
            throw new AppException(
                    "Превышено количество попыток. Запросите новый код.",
                    "Аракеттер саны ашып кетти. Жаңы код суранычы.");
        }

        if (bypass) {
            log.warn("OTP BYPASS enabled — accepting any code for phone: {}", formattedPhone);
        } else if (!otpCode.getCode().equals(request.getCode())) {
            otpCode.setAttempts(otpCode.getAttempts() + 1);
            otpCodeRepository.save(otpCode);
            log.warn("Invalid OTP code for phone: {}, attempts: {}", formattedPhone, otpCode.getAttempts());
            return OtpVerifyResponse.builder()
                    .success(false)
                    .message(ky(lang) ? "Туура эмес код." : "Неверный код подтверждения.")
                    .build();
        }

        otpCode.setVerified(true);
        otpCode.setVerifiedAt(LocalDateTime.now());

        if (otpCode.getType() == OTPCode.OtpType.REGISTRATION) {
            User user = userRepository.findByPhone(formattedPhone)
                    .orElseThrow(() -> new AppException("Пользователь не найден.", "Колдонуучу табылган жок."));
            user.setPhoneVerified(true);
            otpCode.setUsed(true);
            otpCode.setUsedAt(LocalDateTime.now());
            userRepository.save(user);
        }

        otpCodeRepository.save(otpCode);
        log.info("OTP verified successfully for phone: {}", formattedPhone);

        return OtpVerifyResponse.builder()
                .success(true)
                .phone(formattedPhone)
                .message(ky(lang) ? "Код ийгиликтүү тастыкталды." : "Код успешно подтверждён.")
                .build();
    }

    @Transactional
    public OtpSendResponse resendOtp(String phone, OTPCode.OtpType type) {
        return resendOtp(phone, type, "RU");
    }

    @Transactional
    public OtpSendResponse resendOtp(String phone, OTPCode.OtpType type, String lang) {
        String formattedPhone = formatPhoneNumber(phone);
        log.info("Resending OTP for phone: {}, type: {}", formattedPhone, type);

        int deactivated = otpCodeRepository.deactivateOldCodes(formattedPhone, type, LocalDateTime.now());
        if (deactivated > 0) {
            log.info("Deactivated {} old OTP codes for {}", deactivated, formattedPhone);
        }

        return sendOtp(phone, type, lang);
    }

    // ===== HELPER METHODS =====

    private String formatPhoneNumber(String phone) {
        String cleaned = phone.replaceAll("[^0-9]", "");
        if (cleaned.startsWith("0")) {
            cleaned = "996" + cleaned.substring(1);
        }
        if (!cleaned.startsWith("996")) {
            cleaned = "996" + cleaned;
        }
        return cleaned;
    }

    private String generateMessageId() {
        return "OTP" + System.currentTimeMillis();
    }

    /** Generates a 4-digit OTP code as shown in the design */
    private String generateOtpCode() {
        Random random = new Random();
        int code = 1000 + random.nextInt(9000);
        return String.valueOf(code);
    }

    private OtpSendResponse createTestOtp(String phone, String messageId, OTPCode.OtpType type, String testCode, String lang) {
        String testToken = "test_token_" + System.currentTimeMillis();

        OTPCode otpCode = OTPCode.builder()
                .phone(phone)
                .code(testCode)
                .token(testToken)
                .type(type)
                .verified(false)
                .expiresAt(LocalDateTime.now().plusMinutes(otpTtlMinutes))
                .build();

        otpCode = otpCodeRepository.save(otpCode);
        log.warn("TEST MODE - OTP code: {}", testCode);

        String msg = ky(lang)
                ? "Тест режими — код: " + testCode
                : "Тестовый режим — код: " + testCode;

        return OtpSendResponse.builder()
                .success(true)
                .phone(phone)
                .transactionId(messageId)
                .token(testToken)
                .expiresAt(otpCode.getExpiresAt())
                .message(msg)
                .build();
    }

    private boolean ky(String lang) {
        return "KY".equalsIgnoreCase(lang);
    }

    private Integer extractXmlIntValue(String xml, String tagName) {
        String value = extractXmlStringValue(xml, tagName);
        if (value == null || value.isEmpty()) return null;
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            log.error("Failed to parse {} as Integer: {}", tagName, value);
            return null;
        }
    }

    private String extractXmlStringValue(String xml, String tagName) {
        if (xml == null || xml.isEmpty()) return null;
        String pattern = "<" + tagName + ">([^<]*)</" + tagName + ">";
        java.util.regex.Pattern r = java.util.regex.Pattern.compile(pattern);
        java.util.regex.Matcher m = r.matcher(xml);
        return m.find() ? m.group(1).trim() : null;
    }

    private String getSmsErrorMessage(Integer status, String description) {
        if (status == null) return description != null ? description : "Unknown error";
        return switch (status) {
            case 0 -> "Success";
            case 1 -> "Invalid request format";
            case 2 -> "Invalid authorization";
            case 3 -> "Sender IP address is not allowed";
            case 4 -> "Insufficient account balance";
            case 5 -> "Invalid sender name";
            case 6 -> "Message blocked due to stop words";
            case 7 -> "Invalid phone number format";
            case 8 -> "Invalid send time format";
            case 9 -> "Request processing timeout exceeded";
            case 10 -> "Sending blocked due to duplicate ID";
            case 11 -> "Test mode - message not sent";
            default -> description != null ? description : "Unknown error";
        };
    }
}