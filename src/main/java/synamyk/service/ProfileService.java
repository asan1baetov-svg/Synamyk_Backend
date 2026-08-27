package synamyk.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import synamyk.dto.*;
import synamyk.entities.OTPCode;
import synamyk.entities.Region;
import synamyk.entities.User;
import synamyk.enums.MediaFileType;
import synamyk.exception.AppException;
import synamyk.repo.OtpCodeRepository;
import synamyk.repo.RegionRepository;
import synamyk.repo.TestSessionRepository;
import synamyk.repo.UserRepository;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProfileService {

    private final UserRepository userRepository;
    private final RegionRepository regionRepository;
    private final TestSessionRepository sessionRepository;
    private final OtpCodeRepository otpCodeRepository;
    private final PasswordEncoder passwordEncoder;
    private final SmsProService smsProService;
    private final MinioService minioService;

    public ProfileResponse getProfile(Long userId) {
        User user = findUser(userId);

        long completedTests = sessionRepository.countByUserIdAndStatus(userId, synamyk.entities.TestSession.SessionStatus.COMPLETED);
        long totalScore = sessionRepository.sumCorrectAnswersByUserId(userId);

        return ProfileResponse.builder()
                .id(user.getId())
                .phone(user.getPhone())
                .firstName(user.getFirstName())
                .lastName(user.getLastName())
                .bio(user.getBio())
                .avatarUrl(minioService.presign(user.getAvatarUrl()))
                .language(user.getLanguage())
                .regionId(user.getRegion() != null ? user.getRegion().getId() : null)
                .regionName(user.getRegion() != null ? user.getRegion().getName() : null)
                .completedTests(completedTests)
                .totalScore(totalScore)
                .build();
    }

    @Transactional
    public ProfileResponse updateProfile(Long userId, UpdateProfileRequest request) {
        User user = findUser(userId);
        if (request.getFirstName() != null) user.setFirstName(request.getFirstName());
        if (request.getLastName() != null) user.setLastName(request.getLastName());
        if (request.getBio() != null) user.setBio(request.getBio());
        if (request.getAvatarUrl() != null) user.setAvatarUrl(minioService.extractKey(request.getAvatarUrl()));
        userRepository.save(user);
        return getProfile(userId);
    }

    /**
     * One-call avatar upload: stores the image, points the profile at it and
     * removes the previously stored file. Returns the refreshed profile.
     */
    @Transactional
    public ProfileResponse updateAvatar(Long userId, MultipartFile file) {
        User user = findUser(userId);
        String oldKey = user.getAvatarUrl();

        String newKey = minioService.upload(file, MediaFileType.AVATAR, String.valueOf(userId));
        user.setAvatarUrl(newKey);
        userRepository.save(user);

        deleteQuietly(oldKey);
        return getProfile(userId);
    }

    @Transactional
    public ProfileResponse deleteAvatar(Long userId) {
        User user = findUser(userId);
        String oldKey = user.getAvatarUrl();
        user.setAvatarUrl(null);
        userRepository.save(user);

        deleteQuietly(oldKey);
        return getProfile(userId);
    }

    private void deleteQuietly(String objectKey) {
        if (objectKey == null || objectKey.isBlank()) return;
        try {
            minioService.delete(objectKey);
        } catch (Exception e) {
            log.warn("Failed to delete old avatar {}: {}", objectKey, e.getMessage());
        }
    }

    /**
     * Step 1: verify old phone, check new phone is free, send OTP to new phone.
     */
    @Transactional
    public OtpSendResponse requestPhoneChange(Long userId, ChangePhoneRequest request) {
        User user = findUser(userId);
        String oldPhone = formatPhone(request.getOldPhone());
        String newPhone = formatPhone(request.getNewPhone());

        if (!user.getPhone().equals(oldPhone)) {
            throw new AppException("Старый номер телефона не совпадает.", "Эски номер дал келбейт.");
        }
        if (userRepository.existsByPhone(newPhone)) {
            throw new AppException("Этот номер уже зарегистрирован.", "Бул номер катталган.");
        }

        return smsProService.sendOtp(newPhone, OTPCode.OtpType.PHONE_CHANGE);
    }

    /**
     * Step 2: verify OTP sent to new phone, then switch user's phone.
     */
    @Transactional
    public MessageResponse confirmPhoneChange(Long userId, ConfirmPhoneChangeRequest request) {
        User user = findUser(userId);
        String newPhone = formatPhone(request.getNewPhone());

        if (userRepository.existsByPhone(newPhone)) {
            throw new AppException("Этот номер уже зарегистрирован.", "Бул номер катталган.");
        }

        List<OTPCode> otpCodes = otpCodeRepository
                .findByPhoneAndTypeAndVerifiedFalseOrderByCreatedAtDesc(newPhone, OTPCode.OtpType.PHONE_CHANGE);

        if (otpCodes.isEmpty()) {
            throw new AppException("OTP не найден или уже использован.", "OTP табылган жок же колдонулган.");
        }

        OTPCode otp = otpCodes.get(0);

        if (otp.isExpired()) {
            throw new AppException("Код OTP истёк. Запросите новый.", "OTP мөөнөтү өттү. Жаңы код суранычы.");
        }
        if (!otp.getCode().equals(request.getCode())) {
            throw new AppException("Неверный код подтверждения.", "Туура эмес код.");
        }

        otp.setVerified(true);
        otp.setVerifiedAt(LocalDateTime.now());
        otp.setUsed(true);
        otp.setUsedAt(LocalDateTime.now());
        otpCodeRepository.save(otp);

        user.setPhone(newPhone);
        userRepository.save(user);

        log.info("Phone changed successfully for user {}: {}", userId, newPhone);
        return MessageResponse.builder().success(true).message("Phone number updated successfully.").build();
    }

    @Transactional
    public MessageResponse changePassword(Long userId, ChangePasswordRequest request) {
        if (!request.getNewPassword().equals(request.getConfirmPassword())) {
            throw new AppException("Новые пароли не совпадают.", "Жаңы сырсөздөр дал келбейт.");
        }

        User user = findUser(userId);

        if (!passwordEncoder.matches(request.getOldPassword(), user.getPassword())) {
            throw new AppException("Старый пароль неверен.", "Эски сырсөз туура эмес.");
        }

        user.setPassword(passwordEncoder.encode(request.getNewPassword()));
        userRepository.save(user);

        return MessageResponse.builder().success(true).message("Password changed successfully.").build();
    }

    @Transactional
    public MessageResponse changeRegion(Long userId, ChangeRegionRequest request) {
        User user = findUser(userId);
        Region region = regionRepository.findById(request.getRegionId())
                .orElseThrow(() -> new AppException("Регион не найден.", "Аймак табылган жок."));
        user.setRegion(region);
        userRepository.save(user);
        return MessageResponse.builder().success(true).message("Region updated successfully.").build();
    }

    @Transactional
    public MessageResponse changeLanguage(Long userId, ChangeLanguageRequest request) {
        User user = findUser(userId);
        user.setLanguage(request.getLanguage());
        userRepository.save(user);
        return MessageResponse.builder().success(true).message("Language updated successfully.").build();
    }

    /**
     * Soft-deletes the account: sets active = false.
     * The user will no longer be able to log in (isEnabled() returns false).
     */
    @Transactional
    public MessageResponse deleteAccount(Long userId) {
        User user = findUser(userId);
        user.setActive(false);
        userRepository.save(user);
        log.info("Account soft-deleted for user {}", userId);
        return MessageResponse.builder().success(true).message("Account deleted successfully.").build();
    }

    private User findUser(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new AppException("Пользователь не найден.", "Колдонуучу табылган жок."));
    }

    private String formatPhone(String phone) {
        String cleaned = phone.replaceAll("[^0-9]", "");
        if (cleaned.startsWith("0")) cleaned = "996" + cleaned.substring(1);
        if (!cleaned.startsWith("996")) cleaned = "996" + cleaned;
        return cleaned;
    }
}