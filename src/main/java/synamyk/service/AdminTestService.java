package synamyk.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import synamyk.dto.admin.*;
import synamyk.entities.*;
import synamyk.enums.PushCategory;
import synamyk.enums.PushDataType;
import synamyk.exception.AppException;
import synamyk.repo.*;
import synamyk.util.PushMessages;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class AdminTestService {

    private final TestRepository testRepository;
    private final SubTestRepository subTestRepository;
    private final QuestionRepository questionRepository;
    private final AnswerOptionRepository optionRepository;
    private final MinioService minioService;
    private final UserTestAccessRepository userTestAccessRepository;
    private final PushNotificationService pushNotificationService;

    // ===== TESTS =====

    public List<AdminTestResponse> getAllTests() {
        return testRepository.findAll().stream()
                .map(this::toAdminTestResponse)
                .toList();
    }

    public Page<AdminTestListResponse> listTests(int page, int size, String search, String subject, Boolean active) {
        String s = (search != null && !search.isBlank()) ? search.trim() : null;
        String sub = (subject != null && !subject.isBlank()) ? subject.trim() : null;
        return testRepository.findAllByFilters(s, sub, active, PageRequest.of(page, size))
                .map(t -> AdminTestListResponse.builder()
                        .id(t.getId())
                        .title(t.getTitle())
                        .iconUrl(minioService.presign(t.getIconUrl()))
                        .subject(t.getSubject())
                        .price(t.getPrice())
                        .questionCount(testRepository.countQuestionsByTestId(t.getId()))
                        .attemptsCount(testRepository.countAttemptsByTestId(t.getId()))
                        .createdAt(t.getCreatedAt())
                        .active(t.getActive())
                        .build());
    }

    public List<String> getSubjects() {
        return testRepository.findAllSubjects();
    }

    public AdminTestResponse getTest(Long testId) {
        Test test = testRepository.findById(testId)
                .orElseThrow(() -> new AppException("Тест не найден.", "Тест табылган жок."));
        return toAdminTestResponse(test);
    }

    @Transactional
    public AdminTestResponse createTest(CreateTestRequest request) {
        Test test = Test.builder()
                .title(request.getTitle())
                .titleKy(request.getTitleKy())
                .description(request.getDescription())
                .descriptionKy(request.getDescriptionKy())
                .iconUrl(request.getIconUrl())
                .subject(request.getSubject())
                .price(request.getPrice())
                .active(true)
                .build();
        return toAdminTestResponse(testRepository.save(test));
    }

    @Transactional
    public AdminTestResponse updateTest(Long testId, CreateTestRequest request) {
        Test test = testRepository.findById(testId)
                .orElseThrow(() -> new AppException("Тест не найден.", "Тест табылган жок."));
        test.setTitle(request.getTitle());
        test.setTitleKy(request.getTitleKy());
        test.setDescription(request.getDescription());
        test.setDescriptionKy(request.getDescriptionKy());
        test.setIconUrl(request.getIconUrl());
        test.setSubject(request.getSubject());
        test.setPrice(request.getPrice());
        return toAdminTestResponse(testRepository.save(test));
    }

    @Transactional
    public void deleteTest(Long testId) {
        Test test = testRepository.findById(testId)
                .orElseThrow(() -> new AppException("Тест не найден.", "Тест табылган жок."));
        test.setActive(false);
        testRepository.save(test);
    }

    /**
     * Full pricing rewrite: sets the bundle price and, for every sub-test of the
     * test, its {@code isPaid}/{@code price}. Sub-tests absent from the request
     * are reset to free (isPaid=false, price=0).
     */
    @Transactional
    public AdminTestResponse updateTestPricing(Long testId, UpdateTestPricingRequest request) {
        Test test = testRepository.findById(testId)
                .orElseThrow(() -> new AppException("Тест не найден.", "Тест табылган жок."));

        List<SubTest> subTests = subTestRepository.findByTestIdOrderByLevelOrderAsc(testId);
        Map<Long, SubTest> byId = subTests.stream().collect(Collectors.toMap(SubTest::getId, Function.identity()));

        Map<Long, UpdateTestPricingRequest.SubTestPricing> incoming = request.getSubTests().stream()
                .collect(Collectors.toMap(UpdateTestPricingRequest.SubTestPricing::getSubTestId, Function.identity(),
                        (a, b) -> b));

        for (UpdateTestPricingRequest.SubTestPricing p : incoming.values()) {
            if (!byId.containsKey(p.getSubTestId())) {
                throw new AppException(
                        "Подтест " + p.getSubTestId() + " не принадлежит этому тесту.",
                        "Подтест " + p.getSubTestId() + " бул тестке таандык эмес.");
            }
            validatePaidHasPrice(Boolean.TRUE.equals(p.getIsPaid()), p.getPrice(), p.getSubTestId());
        }

        test.setPrice(request.getPrice());
        testRepository.save(test);

        for (SubTest st : subTests) {
            UpdateTestPricingRequest.SubTestPricing p = incoming.get(st.getId());
            if (p != null) {
                st.setIsPaid(Boolean.TRUE.equals(p.getIsPaid()));
                st.setPrice(p.getPrice());
            } else {
                st.setIsPaid(false);
                st.setPrice(BigDecimal.ZERO);
            }
            subTestRepository.save(st);
        }

        log.info("Updated pricing for testId={}: bundlePrice={}, subTests={}",
                testId, request.getPrice(), incoming.keySet());

        return toAdminTestResponse(test);
    }

    @Transactional
    public AdminTestResponse.AdminSubTestResponse setSubTestPaid(Long subTestId, boolean paid) {
        SubTest subTest = subTestRepository.findById(subTestId)
                .orElseThrow(() -> new AppException("Подтест не найден.", "Подтест табылган жок."));
        validatePaidHasPrice(paid, subTest.getPrice(), subTestId);
        subTest.setIsPaid(paid);
        subTestRepository.save(subTest);
        log.info("SubTest {} marked as {}", subTestId, paid ? "PAID" : "FREE");
        return toAdminSubTestResponse(subTest);
    }

    // ===== SCHEDULE (free windows) =====

    @Transactional
    public AdminTestResponse updateTestSchedule(Long testId, ScheduleRequest request) {
        Test test = testRepository.findById(testId)
                .orElseThrow(() -> new AppException("Тест не найден.", "Тест табылган жок."));
        validateWindow(request);
        test.setFreeFrom(request.getFreeFrom());
        test.setFreeUntil(request.getFreeUntil());
        testRepository.save(test);
        log.info("Updated schedule for testId={}: freeFrom={}, freeUntil={}",
                testId, request.getFreeFrom(), request.getFreeUntil());
        return toAdminTestResponse(test);
    }

    @Transactional
    public AdminTestResponse.AdminSubTestResponse updateSubTestSchedule(Long subTestId, ScheduleRequest request) {
        SubTest subTest = subTestRepository.findById(subTestId)
                .orElseThrow(() -> new AppException("Подтест не найден.", "Подтест табылган жок."));
        validateWindow(request);
        subTest.setFreeFrom(request.getFreeFrom());
        subTest.setFreeUntil(request.getFreeUntil());
        subTestRepository.save(subTest);
        log.info("Updated schedule for subTestId={}: freeFrom={}, freeUntil={}",
                subTestId, request.getFreeFrom(), request.getFreeUntil());
        return toAdminSubTestResponse(subTest);
    }

    private void validateWindow(ScheduleRequest r) {
        if (r.getFreeFrom() != null && r.getFreeUntil() != null && !r.getFreeUntil().isAfter(r.getFreeFrom())) {
            throw new AppException(
                    "Дата окончания бесплатности должна быть позже даты начала.",
                    "Бекер мөөнөттүн аякталышы башталышынан кийин болушу керек.");
        }
    }

    private void validatePaidHasPrice(boolean paid, BigDecimal price, Long subTestId) {
        if (paid && (price == null || price.signum() <= 0)) {
            String suffix = subTestId != null ? " (подтест " + subTestId + ")" : "";
            String suffixKy = subTestId != null ? " (подтест " + subTestId + ")" : "";
            throw new AppException(
                    "Платный подтест должен иметь цену больше 0" + suffix + ".",
                    "Акылуу подтесттин баасы 0дөн жогору болушу керек" + suffixKy + ".");
        }
    }

    // ===== SUB-TESTS =====

    @Transactional
    public AdminTestResponse.AdminSubTestResponse createSubTest(Long testId, CreateSubTestRequest request) {
        Test test = testRepository.findById(testId)
                .orElseThrow(() -> new AppException("Тест не найден.", "Тест табылган жок."));

        boolean paid = request.getIsPaid() != null && request.getIsPaid();
        BigDecimal price = request.getPrice() != null ? request.getPrice() : BigDecimal.ZERO;
        validatePaidHasPrice(paid, price, null);

        SubTest subTest = SubTest.builder()
                .test(test)
                .title(request.getTitle())
                .titleKy(request.getTitleKy())
                .levelName(request.getLevelName())
                .levelNameKy(request.getLevelNameKy())
                .levelOrder(request.getLevelOrder())
                .isPaid(paid)
                .price(price)
                .durationMinutes(request.getDurationMinutes())
                .active(true)
                .build();

        subTest = subTestRepository.save(subTest);

        // Notify everyone who owns the parent test that new content is available.
        List<Long> owners = userTestAccessRepository.findActiveUserIdsByTestId(testId, java.time.LocalDateTime.now());
        if (!owners.isEmpty()) {
            pushNotificationService.notifyUsersAsync(owners, PushCategory.MARKETING,
                    PushMessages.newSubTest(test.getTitle(), test.getTitleKy()),
                    PushDataType.SUB_TEST, subTest.getId());
        }

        return toAdminSubTestResponse(subTest);
    }

    @Transactional
    public AdminTestResponse.AdminSubTestResponse updateSubTest(Long subTestId, CreateSubTestRequest request) {
        SubTest subTest = subTestRepository.findById(subTestId)
                .orElseThrow(() -> new AppException("Подтест не найден.", "Подтест табылган жок."));

        boolean paid = request.getIsPaid() != null && request.getIsPaid();
        BigDecimal price = request.getPrice() != null ? request.getPrice() : BigDecimal.ZERO;
        validatePaidHasPrice(paid, price, subTestId);

        subTest.setTitle(request.getTitle());
        subTest.setTitleKy(request.getTitleKy());
        subTest.setLevelName(request.getLevelName());
        subTest.setLevelNameKy(request.getLevelNameKy());
        subTest.setLevelOrder(request.getLevelOrder());
        subTest.setIsPaid(paid);
        subTest.setPrice(price);
        subTest.setDurationMinutes(request.getDurationMinutes());

        return toAdminSubTestResponse(subTestRepository.save(subTest));
    }

    @Transactional
    public void deleteSubTest(Long subTestId) {
        SubTest subTest = subTestRepository.findById(subTestId)
                .orElseThrow(() -> new AppException("Подтест не найден.", "Подтест табылган жок."));
        subTest.setActive(false);
        subTestRepository.save(subTest);
    }

    // ===== QUESTIONS =====

    public List<AdminQuestionResponse> getQuestions(Long subTestId) {
        return questionRepository.findBySubTestIdOrderByOrderIndexAsc(subTestId).stream()
                .map(this::toAdminQuestionResponse)
                .toList();
    }

    @Transactional
    public AdminQuestionResponse createQuestion(Long subTestId, CreateQuestionRequest request) {
        SubTest subTest = subTestRepository.findById(subTestId)
                .orElseThrow(() -> new AppException("Подтест не найден.", "Подтест табылган жок."));

        boolean hasCorrect = request.getOptions().stream()
                .anyMatch(o -> Boolean.TRUE.equals(o.getIsCorrect()));
        if (!hasCorrect) {
            throw new AppException("Хотя бы один вариант должен быть отмечен как правильный.", "Жок дегенде бир туура жооп белгиленүү керек.");
        }

        Question question = Question.builder()
                .subTest(subTest)
                .text(request.getText())
                .textKy(request.getTextKy())
                .sectionName(request.getSectionName())
                .sectionNameKy(request.getSectionNameKy())
                .imageUrl(request.getImageUrl())
                .explanation(request.getExplanation())
                .explanationKy(request.getExplanationKy())
                .orderIndex(request.getOrderIndex())
                .pointValue(request.getPointValue())
                .active(true)
                .build();

        question = questionRepository.save(question);

        int optIndex = 0;
        for (CreateQuestionRequest.AnswerOptionRequest optReq : request.getOptions()) {
            AnswerOption option = AnswerOption.builder()
                    .question(question)
                    .label(optReq.getLabel())
                    .text(optReq.getText())
                    .textKy(optReq.getTextKy())
                    .isCorrect(Boolean.TRUE.equals(optReq.getIsCorrect()))
                    .orderIndex(optIndex++)
                    .build();
            optionRepository.save(option);
        }

        return toAdminQuestionResponse(questionRepository.findById(question.getId()).orElseThrow());
    }

    @Transactional
    public AdminQuestionResponse updateQuestion(Long questionId, CreateQuestionRequest request) {
        Question question = questionRepository.findById(questionId)
                .orElseThrow(() -> new AppException("Вопрос не найден.", "Суроо табылган жок."));

        question.setText(request.getText());
        question.setTextKy(request.getTextKy());
        question.setSectionName(request.getSectionName());
        question.setSectionNameKy(request.getSectionNameKy());
        question.setImageUrl(request.getImageUrl());
        question.setExplanation(request.getExplanation());
        question.setExplanationKy(request.getExplanationKy());
        question.setOrderIndex(request.getOrderIndex());
        question.setPointValue(request.getPointValue());

        // Replace options — merge in place so options already referenced by user
        // answers (user_answer_selected_options) are not hard-deleted.
        List<AnswerOption> existing =
                optionRepository.findByQuestionIdOrderByOrderIndexAsc(questionId);
        List<CreateQuestionRequest.AnswerOptionRequest> incoming = request.getOptions();

        for (int i = 0; i < Math.max(existing.size(), incoming.size()); i++) {
            if (i < existing.size() && i < incoming.size()) {
                AnswerOption option = existing.get(i);
                CreateQuestionRequest.AnswerOptionRequest optReq = incoming.get(i);
                option.setLabel(optReq.getLabel());
                option.setText(optReq.getText());
                option.setTextKy(optReq.getTextKy());
                option.setIsCorrect(Boolean.TRUE.equals(optReq.getIsCorrect()));
                option.setOrderIndex(i);
                optionRepository.save(option);
            } else if (i < incoming.size()) {
                CreateQuestionRequest.AnswerOptionRequest optReq = incoming.get(i);
                optionRepository.save(AnswerOption.builder()
                        .question(question)
                        .label(optReq.getLabel())
                        .text(optReq.getText())
                        .textKy(optReq.getTextKy())
                        .isCorrect(Boolean.TRUE.equals(optReq.getIsCorrect()))
                        .orderIndex(i)
                        .build());
            } else {
                AnswerOption option = existing.get(i);
                if (optionRepository.countUserAnswerReferences(option.getId()) > 0) {
                    throw new AppException(
                            "Нельзя удалить вариант ответа, который уже выбирали пользователи. Отредактируйте его текст вместо удаления.",
                            "Колдонуучулар мурда тандаган жооп вариантын өчүрүүгө болбойт. Өчүрүүнүн ордуна текстин оңдоңуз.");
                }
                optionRepository.delete(option);
            }
        }

        return toAdminQuestionResponse(questionRepository.findById(questionId).orElseThrow());
    }

    @Transactional
    public void deleteQuestion(Long questionId) {
        Question question = questionRepository.findById(questionId)
                .orElseThrow(() -> new AppException("Вопрос не найден.", "Суроо табылган жок."));
        question.setActive(false);
        questionRepository.save(question);
    }

    // ===== MAPPERS =====

    private AdminTestResponse toAdminTestResponse(Test test) {
        List<SubTest> subTests = subTestRepository.findByTestIdOrderByLevelOrderAsc(test.getId());
        return AdminTestResponse.builder()
                .id(test.getId())
                .title(test.getTitle())
                .titleKy(test.getTitleKy())
                .description(test.getDescription())
                .descriptionKy(test.getDescriptionKy())
                .iconUrl(minioService.presign(test.getIconUrl()))
                .price(test.getPrice())
                .freeFrom(test.getFreeFrom())
                .freeUntil(test.getFreeUntil())
                .active(test.getActive())
                .subTests(subTests.stream().map(this::toAdminSubTestResponse).toList())
                .build();
    }

    private AdminTestResponse.AdminSubTestResponse toAdminSubTestResponse(SubTest st) {
        return AdminTestResponse.AdminSubTestResponse.builder()
                .id(st.getId())
                .title(st.getTitle())
                .titleKy(st.getTitleKy())
                .levelName(st.getLevelName())
                .levelNameKy(st.getLevelNameKy())
                .levelOrder(st.getLevelOrder())
                .isPaid(st.getIsPaid())
                .price(st.getPrice())
                .freeFrom(st.getFreeFrom())
                .freeUntil(st.getFreeUntil())
                .durationMinutes(st.getDurationMinutes())
                .questionCount(questionRepository.countBySubTestIdAndActiveTrue(st.getId()))
                .active(st.getActive())
                .build();
    }

    private AdminQuestionResponse toAdminQuestionResponse(Question q) {
        List<AdminQuestionResponse.OptionResponse> options = optionRepository
                .findByQuestionIdOrderByOrderIndexAsc(q.getId()).stream()
                .map(o -> AdminQuestionResponse.OptionResponse.builder()
                        .id(o.getId())
                        .label(o.getLabel())
                        .text(o.getText())
                        .textKy(o.getTextKy())
                        .isCorrect(o.getIsCorrect())
                        .orderIndex(o.getOrderIndex())
                        .build())
                .toList();

        return AdminQuestionResponse.builder()
                .id(q.getId())
                .sectionName(q.getSectionName())
                .sectionNameKy(q.getSectionNameKy())
                .text(q.getText())
                .textKy(q.getTextKy())
                .imageUrl(minioService.presign(q.getImageUrl()))
                .explanation(q.getExplanation())
                .explanationKy(q.getExplanationKy())
                .orderIndex(q.getOrderIndex())
                .pointValue(q.getPointValue())
                .active(q.getActive())
                .options(options)
                .build();
    }
}