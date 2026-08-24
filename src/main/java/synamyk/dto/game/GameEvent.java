package synamyk.dto.game;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(
    description = """
        WebSocket STOMP событие игры. Клиент получает эти события, подписавшись на /topic/game/{roomId}.
        Поле type определяет тип события и набор заполненных полей:

        **GAME_STARTED** — игра началась. Содержит: player1Id, player2Id, player1Name, player2Name, player1Avatar, player2Avatar, player1Score=0, player2Score=0
        **NEXT_QUESTION** — новый вопрос. Содержит: questionIndex, totalQuestions, timeLimitSeconds, question (id, text, imageUrl, options без флага correct), player1Score, player2Score
        **GAME_OVER** — игра завершена. Содержит: player1Score, player2Score, winnerId (null при ничьей), forfeitedBy (заполнено, только если игра закончилась досрочной сдачей — id того, кто сдался)

        Личные события приходят на /topic/game/{roomId}/answers/{userId}:
        **ANSWER_RESULT** — результат ответа конкретного игрока. Содержит: correct, player1Score, player2Score
        """
)
public class GameEvent {

    @Schema(
        description = "Тип события",
        example = "NEXT_QUESTION",
        allowableValues = {"GAME_STARTED", "NEXT_QUESTION", "ANSWER_RESULT", "GAME_OVER"}
    )
    private String type;

    @Schema(description = "ID комнаты", example = "7")
    private Long roomId;

    // GAME_STARTED
    @Schema(description = "[GAME_STARTED] ID первого игрока", example = "42")
    private Long player1Id;

    @Schema(description = "[GAME_STARTED] ID второго игрока", example = "55")
    private Long player2Id;

    @Schema(description = "[GAME_STARTED] Имя первого игрока", example = "Айнур Токтоматова")
    private String player1Name;

    @Schema(description = "[GAME_STARTED] Имя второго игрока", example = "Бекзат")
    private String player2Name;

    @Schema(description = "[GAME_STARTED] URL аватара первого игрока")
    private String player1Avatar;

    @Schema(description = "[GAME_STARTED] URL аватара второго игрока")
    private String player2Avatar;

    // NEXT_QUESTION
    @Schema(description = "[NEXT_QUESTION] Индекс текущего вопроса (начиная с 0)", example = "0")
    private Integer questionIndex;

    @Schema(description = "[NEXT_QUESTION] Всего вопросов в партии", example = "10")
    private Integer totalQuestions;

    @Schema(description = "[NEXT_QUESTION] Лимит времени на этот вопрос (секунды)", example = "30")
    private Integer timeLimitSeconds;

    @Schema(description = "[NEXT_QUESTION] Данные вопроса. Варианты ответа НЕ содержат флаг correct")
    private QuestionPayload question;

    // Scores — присутствуют в NEXT_QUESTION, ANSWER_RESULT, GAME_OVER
    @Schema(description = "Текущий счёт первого игрока", example = "3")
    private Integer player1Score;

    @Schema(description = "Текущий счёт второго игрока", example = "2")
    private Integer player2Score;

    // ANSWER_RESULT (личное)
    @Schema(description = "[ANSWER_RESULT] Правильный ли ответ дал этот игрок", example = "true")
    private Boolean correct;

    // GAME_OVER
    @Schema(description = "[GAME_OVER] ID победителя (null при ничьей)", example = "42")
    private Long winnerId;

    @Schema(description = "[GAME_OVER] ID игрока, который сдался (заполнено только при досрочной сдаче через POST /api/game/{roomId}/forfeit)", example = "55")
    private Long forfeitedBy;

    @Schema(description = "Сообщение об ошибке (при необходимости)")
    private String message;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(description = "Данные вопроса в событии NEXT_QUESTION. Поле correct у вариантов не передаётся клиенту")
    public static class QuestionPayload {
        @Schema(description = "ID вопроса", example = "88")
        private Long id;

        @Schema(description = "Текст вопроса", example = "Чему равно среднее арифметическое чисел 10, 20, 30?")
        private String text;

        @Schema(description = "URL изображения (может отсутствовать)")
        private String imageUrl;

        @Schema(description = "Варианты ответа (без поля correct)")
        private List<OptionPayload> options;
    }

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    @Schema(description = "Вариант ответа, отображаемый игроку. Поле correct скрыто")
    public static class OptionPayload {
        @Schema(description = "ID варианта. Передаётся в /app/game/{roomId}/answer как optionId", example = "103")
        private Long id;

        @Schema(description = "Текст варианта", example = "20")
        private String text;
    }
}
