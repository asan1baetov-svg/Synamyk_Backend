package synamyk.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import synamyk.dto.SubTestAttemptEntry;
import synamyk.entities.Question;
import synamyk.entities.TestSession;
import synamyk.repo.QuestionRepository;
import synamyk.repo.SubTestRepository;
import synamyk.repo.TestSessionRepository;
import synamyk.repo.UserAnswerRepository;
import synamyk.repo.UserRepository;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TestSessionServiceHistoryTest {

    @Mock SubTestRepository subTestRepository;
    @Mock QuestionRepository questionRepository;
    @Mock AccessResolver accessResolver;
    @Mock TestSessionRepository sessionRepository;
    @Mock UserAnswerRepository answerRepository;
    @Mock UserRepository userRepository;
    @Mock ClaudeAiService claudeAiService;
    @Mock MinioService minioService;
    @Mock PushNotificationService pushNotificationService;

    @InjectMocks TestSessionService service;

    private Question question(int points) {
        Question q = new Question();
        q.setPointValue(points);
        return q;
    }

    private TestSession session(long id, TestSession.SessionStatus status, Integer earned) {
        TestSession s = TestSession.builder()
                .status(status)
                .earnedPoints(earned)
                .correctAnswers(earned == null ? null : 1)
                .startedAt(LocalDateTime.now().minusHours(1))
                .build();
        s.setId(id);
        if (status == TestSession.SessionStatus.COMPLETED) {
            s.setCompletedAt(LocalDateTime.now());
        }
        return s;
    }

    @Test
    void getAttemptHistory_computesPercentageFromPointTotal() {
        when(questionRepository.findBySubTestIdAndActiveTrueOrderByOrderIndexAsc(10L))
                .thenReturn(List.of(question(2), question(3)));
        TestSession completed = session(100L, TestSession.SessionStatus.COMPLETED, 4);
        when(sessionRepository.findByUserIdAndSubTestIdOrderByCreatedAtDesc(eq(7L), eq(10L), any()))
                .thenReturn(new PageImpl<>(List.of(completed)));

        Page<SubTestAttemptEntry> page = service.getAttemptHistory(10L, 7L, PageRequest.of(0, 20));

        assertThat(page.getContent()).hasSize(1);
        SubTestAttemptEntry e = page.getContent().get(0);
        assertThat(e.getSessionId()).isEqualTo(100L);
        assertThat(e.getEarnedPoints()).isEqualTo(4);
        assertThat(e.getTotalQuestions()).isEqualTo(2L);
        assertThat(e.getPercentage()).isEqualTo(80); // 4 of 5 points
        assertThat(e.getCompletedAt()).isNotNull();
    }

    @Test
    void getAttemptHistory_noQuestions_percentageZeroNoDivideByZero() {
        when(questionRepository.findBySubTestIdAndActiveTrueOrderByOrderIndexAsc(10L))
                .thenReturn(List.of());
        TestSession inProgress = session(101L, TestSession.SessionStatus.IN_PROGRESS, null);
        when(sessionRepository.findByUserIdAndSubTestIdOrderByCreatedAtDesc(eq(7L), eq(10L), any()))
                .thenReturn(new PageImpl<>(List.of(inProgress)));

        Page<SubTestAttemptEntry> page = service.getAttemptHistory(10L, 7L, PageRequest.of(0, 20));

        SubTestAttemptEntry e = page.getContent().get(0);
        assertThat(e.getPercentage()).isEqualTo(0);
        assertThat(e.getEarnedPoints()).isEqualTo(0);
        assertThat(e.getStatus()).isEqualTo("IN_PROGRESS");
        assertThat(e.getCompletedAt()).isNull();
    }
}
