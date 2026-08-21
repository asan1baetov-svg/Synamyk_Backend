package synamyk.dto.game;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(
    description = """
        Текущее состояние игровой комнаты — используется для восстановления экрана после
        переподключения к WebSocket (обрыв сети, сворачивание приложения и т.п.).

        Вызывайте GET /api/game/{roomId}/state сразу после реконнекта к /ws и повторной
        подписки на /topic/game/{roomId}, чтобы понять, что показывать: ещё идёт ожидание
        соперника, текущий вопрос (и сколько секунд до его смены осталось), или игра уже
        завершилась, пока клиент был отключён.
        """
)
public class GameStateResponse {

    @Schema(
        description = "Статус комнаты",
        allowableValues = {"WAITING", "IN_PROGRESS", "FINISHED", "ABANDONED"}
    )
    private String status;

    private Long roomId;

    // Players — заполнено при IN_PROGRESS и FINISHED
    private Long player1Id;
    private Long player2Id;
    private String player1Name;
    private String player2Name;
    private String player1Avatar;
    private String player2Avatar;
    private Integer player1Score;
    private Integer player2Score;

    // Только при IN_PROGRESS
    @Schema(description = "[IN_PROGRESS] Индекс текущего вопроса")
    private Integer questionIndex;
    @Schema(description = "[IN_PROGRESS] Всего вопросов в партии")
    private Integer totalQuestions;
    @Schema(description = "[IN_PROGRESS] Изначальный лимит времени на вопрос (сек)")
    private Integer timeLimitSeconds;
    @Schema(description = "[IN_PROGRESS] Сколько секунд реально осталось до автосмены вопроса")
    private Integer remainingSeconds;
    @Schema(description = "[IN_PROGRESS] Текущий вопрос (без флага correct у вариантов)")
    private GameEvent.QuestionPayload question;
    @Schema(description = "[IN_PROGRESS] Уже ответили на текущий вопрос до реконнекта")
    private Boolean youAnswered;
    @Schema(description = "[IN_PROGRESS] Если youAnswered=true — был ли этот ответ верным")
    private Boolean yourAnswerCorrect;

    // Только при FINISHED
    @Schema(description = "[FINISHED] ID победителя (null при ничьей)")
    private Long winnerId;
}
