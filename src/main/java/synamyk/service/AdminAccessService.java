package synamyk.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import synamyk.dto.admin.AccessGrantResponse;
import synamyk.dto.admin.GrantAccessRequest;
import synamyk.entities.Test;
import synamyk.entities.User;
import synamyk.entities.UserTestAccess;
import synamyk.exception.AppException;
import synamyk.repo.TestRepository;
import synamyk.repo.UserRepository;
import synamyk.repo.UserTestAccessRepository;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class AdminAccessService {

    private final UserTestAccessRepository accessRepository;
    private final UserRepository userRepository;
    private final TestRepository testRepository;

    /** Grant a new access or extend/replace the term of an existing one. */
    @Transactional
    public AccessGrantResponse grant(GrantAccessRequest r) {
        User user = userRepository.findById(r.getUserId())
                .orElseThrow(() -> new AppException("Пользователь не найден.", "Колдонуучу табылган жок."));
        Test test = testRepository.findById(r.getTestId())
                .orElseThrow(() -> new AppException("Тест не найден.", "Тест табылган жок."));

        UserTestAccess access = accessRepository.findByUserIdAndTestId(user.getId(), test.getId())
                .orElseGet(() -> UserTestAccess.builder().user(user).test(test).build());
        access.setGrantedAt(LocalDateTime.now());
        access.setExpiresAt(resolveExpiry(r));

        UserTestAccess saved = accessRepository.save(access);
        log.info("Admin granted access: userId={}, testId={}, expiresAt={}",
                user.getId(), test.getId(), saved.getExpiresAt());
        return toResponse(saved);
    }

    @Transactional
    public void revoke(Long userId, Long testId) {
        accessRepository.deleteByUserIdAndTestId(userId, testId);
        log.info("Admin revoked access: userId={}, testId={}", userId, testId);
    }

    @Transactional(readOnly = true)
    public List<AccessGrantResponse> listByUser(Long userId) {
        return accessRepository.findByUserIdOrderByGrantedAtDesc(userId).stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public List<AccessGrantResponse> listByTest(Long testId) {
        return accessRepository.findByTestIdOrderByGrantedAtDesc(testId).stream().map(this::toResponse).toList();
    }

    private LocalDateTime resolveExpiry(GrantAccessRequest r) {
        if (r.getExpiresAt() != null) return r.getExpiresAt();
        int days = r.getDurationDays() != null ? Math.max(0, r.getDurationDays()) : 0;
        int hours = r.getDurationHours() != null ? Math.max(0, r.getDurationHours()) : 0;
        if (days == 0 && hours == 0) return null; // permanent
        return LocalDateTime.now().plusDays(days).plusHours(hours);
    }

    private AccessGrantResponse toResponse(UserTestAccess a) {
        String status = a.getExpiresAt() == null
                ? "PERMANENT"
                : a.getExpiresAt().isAfter(LocalDateTime.now()) ? "ACTIVE" : "EXPIRED";
        User u = a.getUser();
        Test t = a.getTest();
        String name = ((u.getFirstName() == null ? "" : u.getFirstName()) + " "
                + (u.getLastName() == null ? "" : u.getLastName())).trim();
        return new AccessGrantResponse(
                a.getId(), u.getId(), name.isBlank() ? "—" : name, u.getPhone(),
                t.getId(), t.getTitle(), a.getGrantedAt(), a.getExpiresAt(), status);
    }
}
