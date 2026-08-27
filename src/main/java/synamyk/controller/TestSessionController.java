package synamyk.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;
import synamyk.dto.*;
import synamyk.entities.User;
import synamyk.service.TestSessionService;
import synamyk.util.LangResolver;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@Tag(name = "Прохождение теста", description = "Процесс прохождения теста: старт → ответ/пропуск → завершение → результат → ИИ-разбор ошибок")
@SecurityRequirement(name = "Bearer")
public class TestSessionController {

    private final TestSessionService sessionService;
    private final LangResolver langResolver;

    @PostMapping("/sub-tests/{subTestId}/start")
    @Operation(
            summary = "Начать или возобновить подтест",
            description = "Создает новую сессию или возобновляет существующую (статус IN_PROGRESS или PAUSED). " +
                    "Таймер не сбрасывается при возобновлении — используется абсолютное время истечения. " +
                    "В ответе возвращается `currentIndex` — с какого вопроса продолжать."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Сессия создана или возобновлена"),
            @ApiResponse(responseCode = "403", description = "Нет доступа — подтест платный и не куплен"),
            @ApiResponse(responseCode = "404", description = "Подтест не найден")
    })
    public ResponseEntity<StartSessionResponse> startSession(
            @Parameter(description = "ID подтеста") @PathVariable Long subTestId,
            @AuthenticationPrincipal UserDetails userDetails,
            Authentication authentication) {
        User user = (User) authentication.getPrincipal();
        return ResponseEntity.ok(sessionService.startSession(subTestId, user.getId(), langResolver.resolve(userDetails)));
    }

    @GetMapping("/sessions/{sessionId}/question")
    @Operation(
            summary = "Получить текущий вопрос",
            description = "Возвращает вопрос по текущему индексу сессии. " +
                    "Если пользователь уже ответил или пропустил — поля `selectedOptionId` и `isSkipped` будут заполнены."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Текущий вопрос с вариантами ответа"),
            @ApiResponse(responseCode = "403", description = "Сессия не принадлежит этому пользователю"),
            @ApiResponse(responseCode = "404", description = "Сессия не найдена")
    })
    public ResponseEntity<QuestionForSessionResponse> getCurrentQuestion(
            @Parameter(description = "ID сессии") @PathVariable Long sessionId,
            @AuthenticationPrincipal UserDetails userDetails,
            Authentication authentication) {
        User user = (User) authentication.getPrincipal();
        return ResponseEntity.ok(sessionService.getCurrentQuestion(sessionId, user.getId(), langResolver.resolve(userDetails)));
    }

    @GetMapping("/sessions/{sessionId}/question/{index}")
    @Operation(
            summary = "Получить вопрос по индексу (0-based)",
            description = "Позволяет перейти к любому вопросу внутри сессии. " +
                    "Используется для навигации назад или к пропущенным вопросам."
    )
    @ApiResponse(responseCode = "200", description = "Вопрос по указанному индексу")
    public ResponseEntity<QuestionForSessionResponse> getQuestionByIndex(
            @Parameter(description = "ID сессии") @PathVariable Long sessionId,
            @Parameter(description = "Индекс вопроса (начинается с 0)") @PathVariable int index,
            @AuthenticationPrincipal UserDetails userDetails,
            Authentication authentication) {
        User user = (User) authentication.getPrincipal();
        return ResponseEntity.ok(sessionService.getQuestionByIndex(sessionId, user.getId(), index, langResolver.resolve(userDetails)));
    }

    @PostMapping("/sessions/{sessionId}/answer")
    @Operation(
            summary = "Отправить ответ на текущий вопрос",
            description = "Сохраняет выбранный ответ и переходит к следующему вопросу. " +
                    "Возвращает следующий вопрос, или **204 No Content** если это был последний вопрос — " +
                    "тогда нужно вызвать POST `/finish`."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Следующий вопрос"),
            @ApiResponse(responseCode = "204", description = "Последний вопрос отвечен — вызовите /finish"),
            @ApiResponse(responseCode = "400", description = "Неверный вариант ответа или несовпадение вопроса"),
            @ApiResponse(responseCode = "403", description = "Сессия истекла или неактивна")
    })
    public ResponseEntity<QuestionForSessionResponse> submitAnswer(
            @Parameter(description = "ID сессии") @PathVariable Long sessionId,
            @RequestBody SubmitAnswerRequest request,
            @AuthenticationPrincipal UserDetails userDetails,
            Authentication authentication) {
        User user = (User) authentication.getPrincipal();
        QuestionForSessionResponse next = sessionService.submitAnswer(sessionId, user.getId(), request, langResolver.resolve(userDetails));
        return next == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(next);
    }

    @PostMapping("/sessions/{sessionId}/skip")
    @Operation(
            summary = "Пропустить текущий вопрос",
            description = "Сохраняет вопрос как пропущенный и возвращает следующий. " +
                    "Возвращает **204** если пропущен последний вопрос — вызовите `/finish`."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Следующий вопрос"),
            @ApiResponse(responseCode = "204", description = "Последний вопрос пропущен — вызовите /finish")
    })
    public ResponseEntity<QuestionForSessionResponse> skipQuestion(
            @Parameter(description = "ID сессии") @PathVariable Long sessionId,
            @AuthenticationPrincipal UserDetails userDetails,
            Authentication authentication) {
        User user = (User) authentication.getPrincipal();
        QuestionForSessionResponse next = sessionService.skipQuestion(sessionId, user.getId(), langResolver.resolve(userDetails));
        return next == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(next);
    }

    @PostMapping("/sessions/{sessionId}/finish")
    @Operation(
            summary = "Завершить тест и получить результат",
            description = "Переводит сессию в статус COMPLETED, сохраняет количество правильных ответов " +
                    "и возвращает детальный результат: баллы, разбивку по вопросам (верно / неверно / пропущено)."
    )
    @ApiResponse(responseCode = "200", description = "Результат сессии с разбивкой по вопросам")
    public ResponseEntity<SessionResultResponse> finishSession(
            @Parameter(description = "ID сессии") @PathVariable Long sessionId,
            @AuthenticationPrincipal UserDetails userDetails,
            Authentication authentication) {
        User user = (User) authentication.getPrincipal();
        return ResponseEntity.ok(sessionService.finishSession(sessionId, user.getId(), langResolver.resolve(userDetails)));
    }

    @PostMapping("/sessions/{sessionId}/pause")
    @Operation(
            summary = "Приостановить сессию",
            description = "Переводит сессию в статус PAUSED. Сессию можно возобновить через `/start` пока не истёк таймер. " +
                    "Вызывается когда пользователь нажимает «Да, хочу» в модалке «Прервать тест?»."
    )
    @ApiResponse(responseCode = "200", description = "Сессия приостановлена")
    public ResponseEntity<MessageResponse> pauseSession(
            @Parameter(description = "ID сессии") @PathVariable Long sessionId,
            Authentication authentication) {
        User user = (User) authentication.getPrincipal();
        sessionService.pauseSession(sessionId, user.getId());
        return ResponseEntity.ok(new MessageResponse(true, "Сессия приостановлена. Вы можете продолжить позже."));
    }

    @GetMapping("/sessions/{sessionId}/result")
    @Operation(
            summary = "Получить результат завершенной сессии",
            description = "Возвращает сохранённый результат сессии. " +
                    "Используется для повторного просмотра результата без повторного завершения."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Результат сессии"),
            @ApiResponse(responseCode = "404", description = "Сессия не найдена")
    })
    public ResponseEntity<SessionResultResponse> getResult(
            @Parameter(description = "ID сессии") @PathVariable Long sessionId,
            @AuthenticationPrincipal UserDetails userDetails,
            Authentication authentication) {
        User user = (User) authentication.getPrincipal();
        return ResponseEntity.ok(sessionService.getResult(sessionId, user.getId(), langResolver.resolve(userDetails)));
    }

    @GetMapping("/sub-tests/{subTestId}/history")
    @Operation(
            summary = "История попыток текущего пользователя по подтесту",
            description = "Страница попыток прохождения подтеста (включая незавершённые), от новых к старым. " +
                    "Для детального разбора конкретной попытки используйте GET /sessions/{sessionId}/result."
    )
    @ApiResponse(responseCode = "200", description = "Страница попыток")
    public ResponseEntity<org.springframework.data.domain.Page<SubTestAttemptEntry>> getAttemptHistory(
            @Parameter(description = "ID подтеста") @PathVariable Long subTestId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @AuthenticationPrincipal UserDetails userDetails,
            Authentication authentication) {
        User user = (User) authentication.getPrincipal();
        int capped = Math.min(Math.max(size, 1), 100);
        return ResponseEntity.ok(sessionService.getAttemptHistory(
                subTestId, user.getId(), org.springframework.data.domain.PageRequest.of(page, capped)));
    }

    @PostMapping("/sessions/{sessionId}/analyze-errors")
    @Operation(
            summary = "ИИ-разбор неправильных ответов",
            description = "Отправляет выбранные неправильные ответы в ИИ для объяснения ошибок. " +
                    "Передайте список `questionIds` вопросов, которые нужно разобрать. " +
                    "Возвращает пояснение для каждого вопроса от ИИ."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "ИИ-анализ по каждому вопросу"),
            @ApiResponse(responseCode = "403", description = "Сессия не принадлежит этому пользователю")
    })
    public ResponseEntity<ErrorAnalysisResponse> analyzeErrors(
            @Parameter(description = "ID сессии") @PathVariable Long sessionId,
            @RequestBody ErrorAnalysisRequest request,
            @AuthenticationPrincipal UserDetails userDetails,
            Authentication authentication) {
        User user = (User) authentication.getPrincipal();
        return ResponseEntity.ok(sessionService.analyzeErrors(sessionId, user.getId(), request, langResolver.resolve(userDetails)));
    }
}