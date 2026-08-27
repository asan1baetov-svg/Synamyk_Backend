package synamyk.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import synamyk.dto.SubTestResponse;
import synamyk.dto.TestDetailResponse;
import synamyk.dto.TestListResponse;
import synamyk.entities.SubTest;
import synamyk.entities.Test;
import synamyk.entities.TestSession;
import synamyk.repo.QuestionRepository;
import synamyk.repo.SubTestRepository;
import synamyk.repo.TestRepository;
import synamyk.repo.TestSessionRepository;
import synamyk.repo.UserTestAccessRepository;
import synamyk.util.L10n;

import java.util.List;

@Service
@RequiredArgsConstructor
public class TestService {

    private final TestRepository testRepository;
    private final SubTestRepository subTestRepository;
    private final QuestionRepository questionRepository;
    private final UserTestAccessRepository accessRepository;
    private final TestSessionRepository sessionRepository;
    private final MinioService minioService;

    public List<TestListResponse> getAllTests(Long userId, String lang) {
        return testRepository.findByActiveTrueOrderByCreatedAtAsc().stream()
                .map(t -> {
                    List<SubTest> subTests = subTestRepository
                            .findByTestIdAndActiveTrueOrderByLevelOrderAsc(t.getId());
                    int subTestCount = subTests.size();
                    int completed = sessionRepository.findCompletedSubTestCounts(userId, t.getId()).size();
                    int progress = subTestCount > 0 ? (completed * 100) / subTestCount : 0;

                    return TestListResponse.builder()
                            .id(t.getId())
                            .title(L10n.pick(t.getTitle(), t.getTitleKy(), lang))
                            .description(L10n.pick(t.getDescription(), t.getDescriptionKy(), lang))
                            .iconUrl(minioService.presign(t.getIconUrl()))
                            .price(t.getPrice())
                            .subTestCount(subTestCount)
                            .completedSubTestCount(completed)
                            .progressPercent(progress)
                            .build();
                })
                .toList();
    }

    public TestDetailResponse getTestDetail(Long testId, Long userId, String lang) {
        Test test = testRepository.findById(testId)
                .orElseThrow(() -> new RuntimeException("Test not found"));

        boolean hasAccess = accessRepository.existsByUserIdAndTestId(userId, testId);

        List<SubTestResponse> subTests = subTestRepository
                .findByTestIdAndActiveTrueOrderByLevelOrderAsc(testId)
                .stream()
                .map(st -> {
                    long questionCount = questionRepository.countBySubTestIdAndActiveTrue(st.getId());
                    boolean subTestAccess = !st.getIsPaid() || hasAccess;

                    List<TestSession> sessions = sessionRepository
                            .findByUserIdAndSubTestIdOrderByCreatedAtDesc(userId, st.getId());

                    boolean hasCompleted = sessions.stream()
                            .anyMatch(s -> s.getStatus() == TestSession.SessionStatus.COMPLETED);

                    TestSession best = sessions.stream()
                            .filter(s -> s.getStatus() == TestSession.SessionStatus.COMPLETED)
                            .filter(s -> s.getEarnedPoints() != null)
                            .max(java.util.Comparator.comparingInt(TestSession::getEarnedPoints))
                            .orElse(null);

                    return SubTestResponse.builder()
                            .id(st.getId())
                            .title(L10n.pick(st.getTitle(), st.getTitleKy(), lang))
                            .levelName(L10n.pick(st.getLevelName(), st.getLevelNameKy(), lang))
                            .levelOrder(st.getLevelOrder())
                            .isPaid(st.getIsPaid())
                            .durationMinutes(st.getDurationMinutes())
                            .questionCount(questionCount)
                            .hasAccess(subTestAccess)
                            .hasCompleted(hasCompleted)
                            .bestScore(best != null ? best.getEarnedPoints() : null)
                            .bestSessionId(best != null ? best.getId() : null)
                            .attemptsCount(sessions.size())
                            .build();
                })
                .toList();

        return TestDetailResponse.builder()
                .id(test.getId())
                .title(L10n.pick(test.getTitle(), test.getTitleKy(), lang))
                .description(L10n.pick(test.getDescription(), test.getDescriptionKy(), lang))
                .price(test.getPrice())
                .hasPaidAccess(hasAccess)
                .subTests(subTests)
                .build();
    }
}
