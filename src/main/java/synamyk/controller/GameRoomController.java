package synamyk.controller;

import io.swagger.v3.oas.annotations.Operation;

import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import synamyk.dto.game.GameStateResponse;
import synamyk.dto.game.JoinGameResponse;
import synamyk.entities.User;
import synamyk.service.GameService;

import java.util.List;

@RestController
@RequestMapping("/api/game")
@RequiredArgsConstructor
@Tag(name = "Игра — клиент", description = """
        REST-эндпоинты для запуска игры в реальном времени.

        **Полный цикл:**
        1. `GET /api/game/tests` — получить список доступных тестов
        2. `POST /api/game/join/{gameTestId}` — встать в очередь
        3. Подключить WebSocket: `ws://<host>/ws` (или SockJS: `/ws`)
        4. Авторизоваться: передать `Authorization: Bearer <token>` в заголовках STOMP CONNECT
        5. Подписаться на события комнаты: `/topic/game/{roomId}`
        6. Подписаться на личные ответы: `/topic/game/{roomId}/answers/{userId}`
        7. Дождаться события `GAME_STARTED` → `NEXT_QUESTION`
        8. Отправить ответ: `/app/game/{roomId}/answer` `{ "optionId": 103 }`
        9. Получить `ANSWER_RESULT`, затем следующий `NEXT_QUESTION` или `GAME_OVER`

        > Если соперник не найдётся за **15 секунд** — игра начнётся автоматически против системы.
        """)
@SecurityRequirement(name = "Bearer")
public class GameRoomController {

    private final GameService gameService;

    @GetMapping("/tests")
    @Operation(
        summary = "Список доступных игровых тестов",
        description = "Возвращает все активные тесты. Показывает только публичные поля — без вопросов и правильных ответов."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Список тестов",
            content = @Content(array = @ArraySchema(schema = @Schema(implementation = GameService.GameTestSummary.class)))),
        @ApiResponse(responseCode = "401", description = "Не авторизован", content = @Content)
    })
    public ResponseEntity<List<GameService.GameTestSummary>> listTests() {
        return ResponseEntity.ok(gameService.listActiveGameTests());
    }

    @PostMapping("/join/{gameTestId}")
    @Operation(
        summary = "Вступить в очередь / найти соперника",
        description = """
            Ставит игрока в очередь на указанный тест.

            **Варианты ответа:**
            - `status: WAITING` — соперник ещё не найден. Клиент должен подключиться к WebSocket и подписаться на `/topic/game/{roomId}`. Через ≤15 сек придёт событие `GAME_STARTED`.
            - `status: MATCHED` — найден реальный соперник, игра начнётся через ~2 секунды. Немедленно подключиться к WebSocket и подписаться на `/topic/game/{roomId}`.

            > Один игрок не может встать в очередь дважды на один тест.
            """
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Очередь или матч",
            content = @Content(schema = @Schema(implementation = JoinGameResponse.class),
                examples = {
                    @ExampleObject(name = "WAITING",
                        summary = "Соперника нет — ожидание",
                        value = """
                            {
                              "status": "WAITING",
                              "roomId": 7,
                              "message": "Ожидание соперника..."
                            }
                            """),
                    @ExampleObject(name = "MATCHED",
                        summary = "Соперник найден",
                        value = """
                            {
                              "status": "MATCHED",
                              "roomId": 7,
                              "message": "Соперник найден! Игра начинается..."
                            }
                            """)
                })),
        @ApiResponse(responseCode = "400", description = "Тест неактивен или не содержит вопросов", content = @Content),
        @ApiResponse(responseCode = "401", description = "Не авторизован", content = @Content),
        @ApiResponse(responseCode = "404", description = "Тест не найден", content = @Content)
    })
    public ResponseEntity<JoinGameResponse> join(
        @Parameter(description = "ID игрового теста", example = "1", required = true)
        @PathVariable Long gameTestId,
        @AuthenticationPrincipal User user
    ) {
        return ResponseEntity.ok(gameService.joinGame(gameTestId, user.getId()));
    }

    @GetMapping("/{roomId}/state")
    @Operation(
        summary = "Снимок состояния комнаты (для реконнекта)",
        description = """
            Вызывайте после переподключения к WebSocket (обрыв сети, сворачивание приложения),
            сразу как только заново подписались на /topic/game/{roomId}, чтобы восстановить экран:
            какой сейчас вопрос, сколько секунд осталось, счёт, отвечали ли вы уже на текущий вопрос,
            либо что игра уже завершилась, пока клиент был отключён.
            """
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Текущее состояние комнаты"),
        @ApiResponse(responseCode = "400", description = "Комната не найдена или вы не участник этой игры", content = @Content),
        @ApiResponse(responseCode = "401", description = "Не авторизован", content = @Content)
    })
    public ResponseEntity<GameStateResponse> getState(
        @Parameter(description = "ID комнаты", example = "7", required = true)
        @PathVariable Long roomId,
        @AuthenticationPrincipal User user
    ) {
        return ResponseEntity.ok(gameService.getGameState(roomId, user.getId()));
    }

    @DeleteMapping("/queue/{gameTestId}")
    @Operation(
        summary = "Покинуть очередь ожидания",
        description = "Отменяет ожидание соперника. Применяется только если текущий игрок создал комнату со статусом WAITING. Если игра уже началась — запрос игнорируется."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "Очередь покинута", content = @Content),
        @ApiResponse(responseCode = "401", description = "Не авторизован", content = @Content)
    })
    public ResponseEntity<Void> leaveQueue(
        @Parameter(description = "ID игрового теста", example = "1", required = true)
        @PathVariable Long gameTestId,
        @AuthenticationPrincipal User user
    ) {
        gameService.leaveQueue(gameTestId, user.getId());
        return ResponseEntity.noContent().build();
    }
}
