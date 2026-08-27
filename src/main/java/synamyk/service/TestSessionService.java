package synamyk.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import synamyk.dto.*;
import synamyk.entities.*;
import synamyk.exception.AppException;
import synamyk.repo.*;

import synamyk.enums.PushCategory;
import synamyk.enums.PushDataType;
import synamyk.util.L10n;
import synamyk.util.PushMessages;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@Slf4j
@Service
@RequiredArgsConstructor
public class TestSessionService {

    private final SubTestRepository subTestRepository;
    private final QuestionRepository questionRepository;
    private final UserTestAccessRepository accessRepository;
    private final TestSessionRepository sessionRepository;
    private final UserAnswerRepository answerRepository;
    private final UserRepository userRepository;
    private final ClaudeAiService claudeAiService;
    private final MinioService minioService;
    private final PushNotificationService pushNotificationService;

    /**
     * Start or resume a sub-test session.
     */
    @Transactional
    public StartSessionResponse startSession(Long subTestId, Long userId, String lang) {
        log.info("startSession: userId={}, subTestId={}", userId, subTestId);

        SubTest subTest = subTestRepository.findById(subTestId)
                .orElseThrow(() -> new AppException("Подтест не найден.", "Подтест табылган жок."));

        // Check access
        if (subTest.getIsPaid() && !accessRepository.existsActiveAccess(
                userId, subTest.getTest().getId(), LocalDateTime.now())) {
            log.warn("Access denied: userId={}, subTestId={} — paid subtest, no access granted", userId, subTestId);
            throw new AppException(
                    "Нет доступа. Пожалуйста, приобретите тест.",
                    "Мүмкүнчүлүк жок. Тестти сатып алыңыз.");
        }

        // Look for any resumable session (IN_PROGRESS or PAUSED)
        List<TestSession> resumable = sessionRepository.findResumable(userId, subTestId);
        log.debug("Found {} resumable session(s) for userId={}, subTestId={}", resumable.size(), userId, subTestId);

        if (!resumable.isEmpty()) {
            TestSession session = resumable.get(0);

            // If session was paused, extend expiresAt by the time it was paused
            if (session.getStatus() == TestSession.SessionStatus.PAUSED && session.getPausedAt() != null) {
                long pausedSeconds = java.time.Duration.between(session.getPausedAt(), LocalDateTime.now()).getSeconds();
                log.info("Extending expiresAt by {}s for paused sessionId={}", pausedSeconds, session.getId());
                session.setExpiresAt(session.getExpiresAt().plusSeconds(pausedSeconds));
                session.setPausedAt(null);
            }

            // If timer expired, mark as EXPIRED and start fresh
            if (session.isExpired()) {
                session.setStatus(TestSession.SessionStatus.EXPIRED);
                sessionRepository.save(session);
                log.info("Session expired: sessionId={}", session.getId());
            } else {
                // Resume
                session.setStatus(TestSession.SessionStatus.IN_PROGRESS);
                sessionRepository.save(session);
                log.info("Resuming session: sessionId={}, userId={}, index={}",
                        session.getId(), userId, session.getCurrentIndex());
                int total = questionRepository.findBySubTestIdAndActiveTrueOrderByOrderIndexAsc(subTestId).size();
                return StartSessionResponse.builder()
                        .sessionId(session.getId())
                        .subTestId(subTestId)
                        .subTestTitle(L10n.pick(subTest.getTitle(), subTest.getTitleKy(), lang))
                        .levelName(L10n.pick(subTest.getLevelName(), subTest.getLevelNameKy(), lang))
                        .totalQuestions(total)
                        .durationMinutes(subTest.getDurationMinutes())
                        .expiresAt(session.getExpiresAt())
                        .remainingSeconds(session.getRemainingSeconds())
                        .currentIndex(session.getCurrentIndex())
                        .isResumed(true)
                        .build();
            }
        }

        List<Question> questions = questionRepository
                .findBySubTestIdAndActiveTrueOrderByOrderIndexAsc(subTestId);

        if (questions.isEmpty()) {
            log.warn("No active questions found for subTestId={}", subTestId);
            throw new AppException("В этом подтесте нет вопросов.", "Бул подтестте суроолор жок.");
        }
        log.debug("Loaded {} questions for subTestId={}", questions.size(), subTestId);

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime expiresAt = now.plusMinutes(subTest.getDurationMinutes());

        User user = userRepository.getReferenceById(userId);
        TestSession session = TestSession.builder()
                .user(user)
                .subTest(subTest)
                .status(TestSession.SessionStatus.IN_PROGRESS)
                .currentIndex(0)
                .startedAt(now)
                .expiresAt(expiresAt)
                .build();

        session = sessionRepository.save(session);
        log.info("New session started: sessionId={}, userId={}, subTestId={}", session.getId(), userId, subTestId);

        return StartSessionResponse.builder()
                .sessionId(session.getId())
                .subTestId(subTestId)
                .subTestTitle(L10n.pick(subTest.getTitle(), subTest.getTitleKy(), lang))
                .levelName(L10n.pick(subTest.getLevelName(), subTest.getLevelNameKy(), lang))
                .totalQuestions(questions.size())
                .durationMinutes(subTest.getDurationMinutes())
                .expiresAt(expiresAt)
                .remainingSeconds(session.getRemainingSeconds())
                .currentIndex(0)
                .isResumed(false)
                .build();
    }

    /**
     * Get current question for a session.
     */
    public QuestionForSessionResponse getCurrentQuestion(Long sessionId, Long userId, String lang) {
        TestSession session = getActiveSession(sessionId, userId);
        List<Question> questions = questionRepository
                .findBySubTestIdAndActiveTrueOrderByOrderIndexAsc(session.getSubTest().getId());

        int index = session.getCurrentIndex();
        if (index >= questions.size()) {
            throw new AppException("Вопросов больше нет.", "Суроолор аяктады.");
        }

        Question question = questions.get(index);
        return buildQuestionResponse(question, index, questions.size(), session, lang);
    }

    /**
     * Get question by index.
     */
    public QuestionForSessionResponse getQuestionByIndex(Long sessionId, Long userId, int index, String lang) {
        TestSession session = getActiveSession(sessionId, userId);
        List<Question> questions = questionRepository
                .findBySubTestIdAndActiveTrueOrderByOrderIndexAsc(session.getSubTest().getId());

        if (index < 0 || index >= questions.size()) {
            throw new AppException("Неверный индекс вопроса.", "Суроонун индекси туура эмес.");
        }

        Question question = questions.get(index);
        return buildQuestionResponse(question, index, questions.size(), session, lang);
    }

    /**
     * Submit answer for current question and advance to next.
     */
    @Transactional
    public QuestionForSessionResponse submitAnswer(Long sessionId, Long userId, SubmitAnswerRequest request, String lang) {
        TestSession session = getActiveSession(sessionId, userId);

        if (session.isExpired()) {
            throw new AppException("Время сессии истекло.", "Сессиянын мөөнөтү өттү.");
        }

        List<Question> questions = questionRepository
                .findBySubTestIdAndActiveTrueOrderByOrderIndexAsc(session.getSubTest().getId());

        int currentIndex = session.getCurrentIndex();
        if (currentIndex >= questions.size()) {
            throw new AppException("Все вопросы уже отвечены.", "Бардык суроолорго жооп берилди.");
        }

        Question currentQuestion = questions.get(currentIndex);

        // Validate question matches
        if (!currentQuestion.getId().equals(request.getQuestionId())) {
            throw new AppException("Вопрос не совпадает.", "Суроо дал келбейт.");
        }

        // Save/update answer
        Optional<UserAnswer> existingAnswer = answerRepository.findBySessionIdAndQuestionId(sessionId, request.getQuestionId());
        UserAnswer answer;

        if (existingAnswer.isPresent()) {
            answer = existingAnswer.get();
        } else {
            answer = UserAnswer.builder()
                    .session(session)
                    .question(currentQuestion)
                    .build();
        }

        boolean isSkipped = request.getSelectedOptionIds() == null || request.getSelectedOptionIds().isEmpty();
        answer.setIsSkipped(isSkipped);

        if (!isSkipped) {
            List<Long> requestedIds = request.getSelectedOptionIds();
            List<AnswerOption> selected = currentQuestion.getOptions().stream()
                    .filter(o -> requestedIds.contains(o.getId()))
                    .toList();
            if (selected.size() != requestedIds.size()) {
                throw new AppException(
                        "Один или несколько выбранных вариантов не найдены.",
                        "Тандалган жооптордун бири же бирнечеси табылган жок.");
            }
            answer.setSelectedOptions(selected);

            // Correct if the set of selected options exactly matches the set of correct options
            List<Long> correctIds = currentQuestion.getOptions().stream()
                    .filter(AnswerOption::getIsCorrect)
                    .map(AnswerOption::getId)
                    .sorted()
                    .toList();
            List<Long> selectedIds = selected.stream()
                    .map(AnswerOption::getId)
                    .sorted()
                    .toList();
            answer.setIsCorrect(correctIds.equals(selectedIds));
        } else {
            answer.setSelectedOptions(new java.util.ArrayList<>());
            answer.setIsCorrect(false);
        }

        answerRepository.save(answer);

        // Advance to next question
        int nextIndex = currentIndex + 1;
        session.setCurrentIndex(nextIndex);
        sessionRepository.save(session);

        // If last question, return null (caller will call finish)
        if (nextIndex >= questions.size()) {
            return null; // signals end of test
        }

        Question nextQuestion = questions.get(nextIndex);
        return buildQuestionResponse(nextQuestion, nextIndex, questions.size(), session, lang);
    }

    /**
     * Skip current question and advance to next.
     */
    @Transactional
    public QuestionForSessionResponse skipQuestion(Long sessionId, Long userId, String lang) {
        TestSession session = getActiveSession(sessionId, userId);
        List<Question> questions = questionRepository
                .findBySubTestIdAndActiveTrueOrderByOrderIndexAsc(session.getSubTest().getId());
        int currentIndex = session.getCurrentIndex();
        SubmitAnswerRequest request = new SubmitAnswerRequest();
        request.setQuestionId(questions.get(currentIndex).getId());
        request.setSelectedOptionIds(null);
        return submitAnswer(sessionId, userId, request, lang);
    }

    /**
     * Finish the test session and return results.
     */
    @Transactional
    public SessionResultResponse finishSession(Long sessionId, Long userId, String lang) {
        TestSession session = getActiveSession(sessionId, userId);

        List<UserAnswer> allAnswers = answerRepository.findBySessionIdOrderByQuestionOrderIndex(sessionId);
        long correct = allAnswers.stream().filter(UserAnswer::getIsCorrect).count();
        int earned = allAnswers.stream()
                .filter(UserAnswer::getIsCorrect)
                .mapToInt(a -> a.getQuestion().getPointValue())
                .sum();
        session.setCorrectAnswers((int) correct);
        session.setEarnedPoints(earned);
        session.setStatus(TestSession.SessionStatus.COMPLETED);
        session.setCompletedAt(LocalDateTime.now());
        sessionRepository.save(session);
        return buildResult(session, lang);
    }

    /**
     * Pause session (user clicked "Прервать тест?" → "Да, хочу").
     * Session remains resumable until timer expires.
     */
    @Transactional
    public void pauseSession(Long sessionId, Long userId) {
        TestSession session = getActiveSession(sessionId, userId);
        session.setStatus(TestSession.SessionStatus.PAUSED);
        session.setPausedAt(LocalDateTime.now());
        sessionRepository.save(session);
        log.info("Session paused: sessionId={}, currentIndex={}", sessionId, session.getCurrentIndex());
    }

    /**
     * Get result for a completed session.
     */
    public SessionResultResponse getResult(Long sessionId, Long userId, String lang) {
        TestSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new AppException("Сессия не найдена.", "Сессия табылган жок."));

        if (!session.getUser().getId().equals(userId)) {
            throw new AppException("Нет доступа.", "Мүмкүнчүлүк жок.");
        }

        return buildResult(session, lang);
    }

    /**
     * Current user's past attempts on a sub-test, newest first, paginated.
     */
    public Page<SubTestAttemptEntry> getAttemptHistory(Long subTestId, Long userId, Pageable pageable) {
        List<Question> allQuestions = questionRepository
                .findBySubTestIdAndActiveTrueOrderByOrderIndexAsc(subTestId);
        long totalQuestions = allQuestions.size();
        int totalPoints = allQuestions.stream().mapToInt(Question::getPointValue).sum();

        return sessionRepository
                .findByUserIdAndSubTestIdOrderByCreatedAtDesc(userId, subTestId, pageable)
                .map(s -> {
                    int earned = s.getEarnedPoints() != null ? s.getEarnedPoints() : 0;
                    int pct = totalPoints > 0 ? (earned * 100) / totalPoints : 0;
                    return SubTestAttemptEntry.builder()
                            .sessionId(s.getId())
                            .status(s.getStatus().name())
                            .correctAnswers(s.getCorrectAnswers())
                            .earnedPoints(earned)
                            .totalQuestions(totalQuestions)
                            .percentage(pct)
                            .startedAt(s.getStartedAt())
                            .completedAt(s.getCompletedAt())
                            .build();
                });
    }

    /**
     * AI analysis of selected wrong answers.
     */
    public ErrorAnalysisResponse analyzeErrors(Long sessionId, Long userId, ErrorAnalysisRequest request, String lang) {
        TestSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new AppException("Сессия не найдена.", "Сессия табылган жок."));

        if (!session.getUser().getId().equals(userId)) {
            throw new AppException("Нет доступа.", "Мүмкүнчүлүк жок.");
        }

        List<Question> allQuestions = questionRepository
                .findBySubTestIdAndActiveTrueOrderByOrderIndexAsc(session.getSubTest().getId());

        // Build index map: questionId -> 1-based display index
        Map<Long, Integer> questionIndexMap = IntStream.range(0, allQuestions.size())
                .boxed()
                .collect(Collectors.toMap(i -> allQuestions.get(i).getId(), i -> i + 1));

        List<UserAnswer> wrongAnswers = answerRepository.findBySessionIdOrderByQuestionOrderIndex(sessionId)
                .stream()
                .filter(a -> !a.getIsCorrect() && !a.getIsSkipped())
                .filter(a -> request.getQuestionIds().contains(a.getQuestion().getId()))
                .toList();

        List<ErrorAnalysisResponse.QuestionAnalysis> analyses = new ArrayList<>();

        for (UserAnswer wa : wrongAnswers) {
            Question q = wa.getQuestion();
            List<AnswerOption> options = q.getOptions();

            String userWrongText = wa.getSelectedOptions().isEmpty()
                    ? "—"
                    : wa.getSelectedOptions().stream()
                            .map(o -> o.getLabel() + ". " + o.getText())
                            .collect(Collectors.joining(", "));

            String correctText = options.stream()
                    .filter(AnswerOption::getIsCorrect)
                    .map(o -> o.getLabel() + ". " + o.getText())
                    .collect(Collectors.joining(", "));

            List<String> optionTexts = options.stream()
                    .map(o -> o.getLabel() + ". " + o.getText())
                    .toList();

            String explanation = claudeAiService.explainWrongAnswer(q.getText(), optionTexts, userWrongText, correctText, lang);

            int questionIndex = questionIndexMap.getOrDefault(q.getId(), 0);

            analyses.add(ErrorAnalysisResponse.QuestionAnalysis.builder()
                    .questionId(q.getId())
                    .questionIndex(questionIndex)
                    .questionText(q.getText())
                    .wrongAnswer(userWrongText)
                    .correctAnswer(correctText)
                    .explanation(explanation)
                    .build());
        }

        Long subTestId = session.getSubTest().getId();
        subTestRepository.findById(subTestId).ifPresent(st -> {
            try {
                pushNotificationService.notifyUser(userId, PushCategory.RESULTS,
                        PushMessages.errorAnalysisReady(st.getTitle(), st.getTitleKy()),
                        PushDataType.SUB_TEST, subTestId);
            } catch (Exception e) {
                log.warn("errorAnalysisReady notification failed: {}", e.getMessage());
            }
        });

        return ErrorAnalysisResponse.builder().analyses(analyses).build();
    }

    // ===== HELPERS =====

    private TestSession getActiveSession(Long sessionId, Long userId) {
        TestSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new AppException("Сессия не найдена.", "Сессия табылган жок."));

        if (!session.getUser().getId().equals(userId)) {
            throw new AppException("Нет доступа.", "Мүмкүнчүлүк жок.");
        }

        if (session.getStatus() != TestSession.SessionStatus.IN_PROGRESS) {
            throw new AppException(
                    "Сессия неактивна. Статус: " + session.getStatus(),
                    "Сессия активдүү эмес. Статус: " + session.getStatus());
        }

        return session;
    }

    private QuestionForSessionResponse buildQuestionResponse(
            Question question, int index, int total, TestSession session, String lang) {

        Optional<UserAnswer> existingAnswer = answerRepository
                .findBySessionIdAndQuestionId(session.getId(), question.getId());

        List<Long> selectedOptionIds = existingAnswer
                .map(a -> a.getSelectedOptions().stream().map(AnswerOption::getId).toList())
                .orElse(List.of());

        Boolean isSkipped = existingAnswer.map(UserAnswer::getIsSkipped).orElse(false);

        List<AnswerOptionResponse> options = question.getOptions().stream()
                .map(o -> AnswerOptionResponse.builder()
                        .id(o.getId())
                        .label(o.getLabel())
                        .text(L10n.pick(o.getText(), o.getTextKy(), lang))
                        .orderIndex(o.getOrderIndex())
                        .build())
                .toList();

        return QuestionForSessionResponse.builder()
                .questionId(question.getId())
                .index(index)
                .totalQuestions(total)
                .sectionName(L10n.pick(question.getSectionName(), question.getSectionNameKy(), lang))
                .text(L10n.pick(question.getText(), question.getTextKy(), lang))
                .imageUrl(minioService.presign(question.getImageUrl()))
                .pointValue(question.getPointValue())
                .options(options)
                .remainingSeconds(session.getRemainingSeconds())
                .selectedOptionIds(selectedOptionIds)
                .isSkipped(isSkipped)
                .build();
    }

    private SessionResultResponse buildResult(TestSession session, String lang) {
        List<UserAnswer> answers = answerRepository.findBySessionIdOrderByQuestionOrderIndex(session.getId());
        List<Question> allQuestions = questionRepository
                .findBySubTestIdAndActiveTrueOrderByOrderIndexAsc(session.getSubTest().getId());

        int total = allQuestions.size();
        long correct = answers.stream().filter(UserAnswer::getIsCorrect).count();
        long skipped = answers.stream().filter(UserAnswer::getIsSkipped).count();
        long wrong = answers.stream().filter(a -> !a.getIsCorrect() && !a.getIsSkipped()).count();

        int totalPoints = allQuestions.stream().mapToInt(Question::getPointValue).sum();
        int earnedPoints = session.getEarnedPoints() != null ? session.getEarnedPoints() :
                answers.stream().filter(UserAnswer::getIsCorrect)
                        .mapToInt(a -> a.getQuestion().getPointValue()).sum();

        int percentage = totalPoints > 0 ? (earnedPoints * 100) / totalPoints : 0;

        long timeTaken = 0;
        if (session.getCompletedAt() != null) {
            timeTaken = java.time.Duration.between(session.getStartedAt(), session.getCompletedAt()).getSeconds();
        }

        String motivation = "KY".equals(lang) ? getMotivationalMessageKy(percentage) : getMotivationalMessage(percentage);

        List<SessionResultResponse.QuestionResultItem> items = IntStream.range(0, allQuestions.size())
                .mapToObj(idx -> {
                    Question q = allQuestions.get(idx);
                    Optional<UserAnswer> ua = answers.stream()
                            .filter(a -> a.getQuestion().getId().equals(q.getId()))
                            .findFirst();

                    List<Long> correctOptionIds = q.getOptions().stream()
                            .filter(AnswerOption::getIsCorrect)
                            .map(AnswerOption::getId)
                            .toList();

                    List<Long> selectedOptionIds = ua
                            .map(a -> a.getSelectedOptions().stream().map(AnswerOption::getId).toList())
                            .orElse(List.of());

                    return SessionResultResponse.QuestionResultItem.builder()
                            .questionId(q.getId())
                            .index(idx + 1)
                            .isCorrect(ua.map(UserAnswer::getIsCorrect).orElse(false))
                            .isSkipped(ua.map(UserAnswer::getIsSkipped).orElse(true))
                            .pointValue(q.getPointValue())
                            .selectedOptionIds(selectedOptionIds)
                            .correctOptionIds(correctOptionIds)
                            .build();
                })
                .collect(Collectors.toList());

        return SessionResultResponse.builder()
                .sessionId(session.getId())
                .subTestTitle(L10n.pick(session.getSubTest().getTitle(), session.getSubTest().getTitleKy(), lang))
                .levelName(L10n.pick(session.getSubTest().getLevelName(), session.getSubTest().getLevelNameKy(), lang))
                .totalQuestions(total)
                .correctAnswers((int) correct)
                .wrongAnswers((int) wrong)
                .skippedAnswers((int) skipped)
                .totalPoints(totalPoints)
                .earnedPoints(earnedPoints)
                .percentage(percentage)
                .timeTakenSeconds(timeTaken)
                .motivationalMessage(motivation)
                .questionResults(items)
                .build();
    }

    private String getMotivationalMessage(int percentage) {
        if (percentage >= 90) return "Отлично! Ты настоящий знаток!";
        if (percentage >= 70) return "Хорошая работа! Продолжай в том же духе!";
        if (percentage >= 50) return "Неплохо! Ещё немного практики и будет отлично!";
        return "Не сдавайся! Каждая попытка делает тебя сильнее!";
    }

    private String getMotivationalMessageKy(int percentage) {
        if (percentage >= 90) return "Мыкты! Сен чыныгы билимдүүсүң!";
        if (percentage >= 70) return "Жакшы иш! Ошондой эле улантыгыла!";
        if (percentage >= 50) return "Жаман эмес! Дагы бир аз машыгуу керек!";
        return "Баш тартпагыла! Ар бир аракет сени күчтүүрөөк кылат!";
    }
}