package synamyk.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import synamyk.dto.admin.AccessGrantResponse;
import synamyk.dto.admin.GrantAccessRequest;
import synamyk.entities.SubTest;
import synamyk.entities.Test;
import synamyk.entities.User;
import synamyk.entities.UserSubTestAccess;
import synamyk.entities.UserTestAccess;
import synamyk.exception.AppException;
import synamyk.repo.SubTestRepository;
import synamyk.repo.TestRepository;
import synamyk.repo.UserRepository;
import synamyk.repo.UserSubTestAccessRepository;
import synamyk.repo.UserTestAccessRepository;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

@Slf4j
@Service
@RequiredArgsConstructor
public class AdminAccessService {

    private final UserTestAccessRepository accessRepository;
    private final UserSubTestAccessRepository subTestAccessRepository;
    private final UserRepository userRepository;
    private final TestRepository testRepository;
    private final SubTestRepository subTestRepository;

    /** Grant a new access or extend/replace the term of an existing one. */
    @Transactional
    public AccessGrantResponse grant(GrantAccessRequest r) {
        if ((r.getTestId() == null) == (r.getSubTestId() == null)) {
            throw new AppException("Укажите ровно один из testId / subTestId.",
                    "testId / subTestId ичинен так бирөөнү көрсөтүңүз.");
        }
        User user = userRepository.findById(r.getUserId())
                .orElseThrow(() -> new AppException("Пользователь не найден.", "Колдонуучу табылган жок."));

        if (r.getSubTestId() != null) {
            SubTest subTest = subTestRepository.findById(r.getSubTestId())
                    .orElseThrow(() -> new AppException("Подтест не найден.", "Подтест табылган жок."));
            UserSubTestAccess access = subTestAccessRepository
                    .findByUserIdAndSubTestId(user.getId(), subTest.getId())
                    .orElseGet(() -> UserSubTestAccess.builder().user(user).subTest(subTest).build());
            access.setGrantedAt(LocalDateTime.now());
            access.setExpiresAt(resolveExpiry(r));
            UserSubTestAccess saved = subTestAccessRepository.save(access);
            log.info("Admin granted sub-test access: userId={}, subTestId={}, expiresAt={}",
                    user.getId(), subTest.getId(), saved.getExpiresAt());
            return toResponse(saved);
        }

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
    public void revoke(Long userId, Long testId, Long subTestId) {
        if ((testId == null) == (subTestId == null)) {
            throw new AppException("Укажите ровно один из testId / subTestId.",
                    "testId / subTestId ичинен так бирөөнү көрсөтүңүз.");
        }
        if (subTestId != null) {
            subTestAccessRepository.deleteByUserIdAndSubTestId(userId, subTestId);
            log.info("Admin revoked sub-test access: userId={}, subTestId={}", userId, subTestId);
        } else {
            accessRepository.deleteByUserIdAndTestId(userId, testId);
            log.info("Admin revoked access: userId={}, testId={}", userId, testId);
        }
    }

    /** Combined list of test-level and sub-test-level grants for a user, newest first. */
    @Transactional(readOnly = true)
    public List<AccessGrantResponse> listByUser(Long userId) {
        Stream<AccessGrantResponse> tests = accessRepository.findByUserIdOrderByGrantedAtDesc(userId)
                .stream().map(this::toResponse);
        Stream<AccessGrantResponse> subTests = subTestAccessRepository.findByUserIdOrderByGrantedAtDesc(userId)
                .stream().map(this::toResponse);
        return Stream.concat(tests, subTests)
                .sorted(Comparator.comparing(AccessGrantResponse::grantedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<AccessGrantResponse> listByTest(Long testId) {
        return accessRepository.findByTestIdOrderByGrantedAtDesc(testId).stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public List<AccessGrantResponse> listBySubTest(Long subTestId) {
        return subTestAccessRepository.findBySubTestIdOrderByGrantedAtDesc(subTestId).stream()
                .map(this::toResponse).toList();
    }

    private LocalDateTime resolveExpiry(GrantAccessRequest r) {
        if (r.getExpiresAt() != null) return r.getExpiresAt();
        int days = r.getDurationDays() != null ? Math.max(0, r.getDurationDays()) : 0;
        int hours = r.getDurationHours() != null ? Math.max(0, r.getDurationHours()) : 0;
        if (days == 0 && hours == 0) return null; // permanent
        return LocalDateTime.now().plusDays(days).plusHours(hours);
    }

    private String status(LocalDateTime expiresAt) {
        if (expiresAt == null) return "PERMANENT";
        return expiresAt.isAfter(LocalDateTime.now()) ? "ACTIVE" : "EXPIRED";
    }

    private String userName(User u) {
        String name = ((u.getFirstName() == null ? "" : u.getFirstName()) + " "
                + (u.getLastName() == null ? "" : u.getLastName())).trim();
        return name.isBlank() ? "—" : name;
    }

    private AccessGrantResponse toResponse(UserTestAccess a) {
        User u = a.getUser();
        Test t = a.getTest();
        return new AccessGrantResponse(
                a.getId(), u.getId(), userName(u), u.getPhone(),
                t.getId(), t.getTitle(), null, null,
                a.getGrantedAt(), a.getExpiresAt(), status(a.getExpiresAt()));
    }

    private AccessGrantResponse toResponse(UserSubTestAccess a) {
        User u = a.getUser();
        SubTest st = a.getSubTest();
        Test t = st.getTest();
        return new AccessGrantResponse(
                a.getId(), u.getId(), userName(u), u.getPhone(),
                t.getId(), t.getTitle(), st.getId(), st.getTitle(),
                a.getGrantedAt(), a.getExpiresAt(), status(a.getExpiresAt()));
    }
}
