package synamyk.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import synamyk.dto.admin.*;
import synamyk.service.AdminTestService;

import org.springframework.data.domain.Page;

import java.util.List;

@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
@Tag(name = "Админ — Тесты", description = "Управление тестами, подтестами и вопросами. Требуется роль ADMIN.")
@SecurityRequirement(name = "Bearer")
public class AdminTestController {

    private final AdminTestService adminTestService;

    // ===== ТЕСТЫ =====

    @GetMapping("/tests")
    @Operation(summary = "Список тестов (пагинация + фильтры)",
            description = "Передай `full=true` чтобы получить полный список с подтестами (без пагинации). По умолчанию — краткий список для таблицы.")
    public ResponseEntity<?> getAllTests(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String subject,
            @RequestParam(required = false) Boolean active,
            @RequestParam(defaultValue = "false") boolean full) {
        if (full) return ResponseEntity.ok(adminTestService.getAllTests());
        return ResponseEntity.ok(adminTestService.listTests(page, size, search, subject, active));
    }

    @GetMapping("/tests/subjects")
    @Operation(summary = "Список предметов (уникальные значения subject)")
    public ResponseEntity<List<String>> getSubjects() {
        return ResponseEntity.ok(adminTestService.getSubjects());
    }

    @GetMapping("/tests/{testId}")
    @Operation(summary = "Получить тест по ID")
    @ApiResponse(responseCode = "200", description = "Тест с подтестами")
    public ResponseEntity<AdminTestResponse> getTest(
            @Parameter(description = "ID теста") @PathVariable Long testId) {
        return ResponseEntity.ok(adminTestService.getTest(testId));
    }

    @PostMapping("/tests")
    @Operation(
            summary = "Создать новый тест",
            description = "Создает тест. Укажите `title` (RU) и `titleKy` (KY). " +
                    "Иконка загружается через POST /api/upload и URL передаётся в поле iconUrl"
    )
    public ResponseEntity<AdminTestResponse> createTest(@Valid @RequestBody CreateTestRequest request) {
        return ResponseEntity.ok(adminTestService.createTest(request));
    }

    @PutMapping("/tests/{testId}")
    @Operation(summary = "Обновить тест")
    public ResponseEntity<AdminTestResponse> updateTest(
            @Parameter(description = "ID теста") @PathVariable Long testId,
            @Valid @RequestBody CreateTestRequest request) {
        return ResponseEntity.ok(adminTestService.updateTest(testId, request));
    }

    @DeleteMapping("/tests/{testId}")
    @Operation(summary = "Деактивировать тест", description = "Мягкое удаление: тест скрывается из списка пользователей")
    @ApiResponse(responseCode = "204", description = "Тест деактивирован")
    public ResponseEntity<Void> deleteTest(
            @Parameter(description = "ID теста") @PathVariable Long testId) {
        adminTestService.deleteTest(testId);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/tests/{testId}/pricing")
    @Operation(
            summary = "Настроить цену и платные подтесты",
            description = "Устанавливает цену теста и указывает, какие подтесты являются платными " +
                    "Все подтесты этого теста, НЕ вошедшие в `paidSubTestIds`, становятся бесплатными " +
                    "Одна оплата даёт доступ ко всем платным подтестам этого теста"
    )
    public ResponseEntity<AdminTestResponse> updateTestPricing(
            @Parameter(description = "ID теста") @PathVariable Long testId,
            @Valid @RequestBody UpdateTestPricingRequest request) {
        return ResponseEntity.ok(adminTestService.updateTestPricing(testId, request));
    }

    @PatchMapping("/tests/{testId}/schedule")
    @Operation(
            summary = "Расписание платности теста",
            description = "Окно бесплатности всего теста. В это окно тест бесплатен для всех пользователей. "
                    + "`{ \"freeFrom\": null, \"freeUntil\": null }` — очистить окно. Время — Asia/Bishkek."
    )
    public ResponseEntity<AdminTestResponse> updateTestSchedule(
            @Parameter(description = "ID теста") @PathVariable Long testId,
            @Valid @RequestBody ScheduleRequest request) {
        return ResponseEntity.ok(adminTestService.updateTestSchedule(testId, request));
    }

    // ===== ПОДТЕСТЫ =====

    @PostMapping("/tests/{testId}/sub-tests")
    @Operation(
            summary = "Добавить подтест к тесту",
            description = "Создает подтест (уровень) внутри теста " +
                    "`levelOrder` определяет порядок отображения (меньше = выше). " +
                    "`durationMinutes` — время на прохождение в минутах"
    )
    public ResponseEntity<AdminTestResponse.AdminSubTestResponse> createSubTest(
            @Parameter(description = "ID теста") @PathVariable Long testId,
            @Valid @RequestBody CreateSubTestRequest request) {
        return ResponseEntity.ok(adminTestService.createSubTest(testId, request));
    }

    @PutMapping("/sub-tests/{subTestId}")
    @Operation(summary = "Обновить подтест")
    public ResponseEntity<AdminTestResponse.AdminSubTestResponse> updateSubTest(
            @Parameter(description = "ID подтеста") @PathVariable Long subTestId,
            @Valid @RequestBody CreateSubTestRequest request) {
        return ResponseEntity.ok(adminTestService.updateSubTest(subTestId, request));
    }

    @PatchMapping("/sub-tests/{subTestId}/schedule")
    @Operation(
            summary = "Расписание платности подтеста",
            description = "Окно бесплатности одного подтеста. В это окно подтест бесплатен для всех пользователей. "
                    + "`{ \"freeFrom\": null, \"freeUntil\": null }` — очистить окно. Время — Asia/Bishkek."
    )
    public ResponseEntity<AdminTestResponse.AdminSubTestResponse> updateSubTestSchedule(
            @Parameter(description = "ID подтеста") @PathVariable Long subTestId,
            @Valid @RequestBody ScheduleRequest request) {
        return ResponseEntity.ok(adminTestService.updateSubTestSchedule(subTestId, request));
    }

    @PatchMapping("/sub-tests/{subTestId}/paid")
    @Operation(
            summary = "Сделать подтест платным или бесплатным",
            description = "Передай `true` — подтест станет платным, `false` — бесплатным. " +
                    "Изменение применяется мгновенно без затрагивания остальных настроек подтеста."
    )
    public ResponseEntity<AdminTestResponse.AdminSubTestResponse> setSubTestPaid(
            @Parameter(description = "ID подтеста") @PathVariable Long subTestId,
            @RequestParam boolean paid) {
        return ResponseEntity.ok(adminTestService.setSubTestPaid(subTestId, paid));
    }

    @DeleteMapping("/sub-tests/{subTestId}")
    @Operation(summary = "Деактивировать подтест")
    @ApiResponse(responseCode = "204", description = "Подтест деактивирован")
    public ResponseEntity<Void> deleteSubTest(
            @Parameter(description = "ID подтеста") @PathVariable Long subTestId) {
        adminTestService.deleteSubTest(subTestId);
        return ResponseEntity.noContent().build();
    }

    // ===== ВОПРОСЫ =====

    @GetMapping("/sub-tests/{subTestId}/questions")
    @Operation(summary = "Список вопросов подтеста", description = "Возвращает все вопросы (включая неактивные) с вариантами ответов")
    public ResponseEntity<List<AdminQuestionResponse>> getQuestions(
            @Parameter(description = "ID подтеста") @PathVariable Long subTestId) {
        return ResponseEntity.ok(adminTestService.getQuestions(subTestId));
    }

    @PostMapping("/sub-tests/{subTestId}/questions")
    @Operation(
            summary = "Добавить вопрос к подтесту",
            description = "Создает вопрос с вариантами ответа (2–6 штук) " +
                    "**Ровно один** вариант должен иметь `isCorrect = true` "
    )
    public ResponseEntity<AdminQuestionResponse> createQuestion(
            @Parameter(description = "ID подтеста") @PathVariable Long subTestId,
            @Valid @RequestBody CreateQuestionRequest request) {
        return ResponseEntity.ok(adminTestService.createQuestion(subTestId, request));
    }

    @PutMapping("/questions/{questionId}")
    @Operation(summary = "Обновить вопрос", description = "Полностью заменяет вопрос и все варианты ответа")
    public ResponseEntity<AdminQuestionResponse> updateQuestion(
            @Parameter(description = "ID вопроса") @PathVariable Long questionId,
            @Valid @RequestBody CreateQuestionRequest request) {
        return ResponseEntity.ok(adminTestService.updateQuestion(questionId, request));
    }

    @DeleteMapping("/questions/{questionId}")
    @Operation(summary = "Деактивировать вопрос")
    @ApiResponse(responseCode = "204", description = "Вопрос деактивирован")
    public ResponseEntity<Void> deleteQuestion(
            @Parameter(description = "ID вопроса") @PathVariable Long questionId) {
        adminTestService.deleteQuestion(questionId);
        return ResponseEntity.noContent().build();
    }
}