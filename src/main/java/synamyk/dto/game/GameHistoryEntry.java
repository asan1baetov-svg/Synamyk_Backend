package synamyk.dto.game;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
@Schema(description = "Одна завершённая игра из истории текущего пользователя (GET /api/game/history)")
public class GameHistoryEntry {

    @Schema(description = "ID комнаты")
    private Long roomId;

    @Schema(description = "ID игрового теста")
    private Long gameTestId;

    @Schema(description = "Название игрового теста")
    private String gameTestTitle;

    @Schema(description = "Счёт текущего пользователя")
    private Integer score;

    @Schema(description = "Счёт соперника")
    private Integer opponentScore;

    @Schema(description = "Всего вопросов в партии")
    private Integer totalQuestions;

    @Schema(description = "Победил ли текущий пользователь")
    private Boolean won;

    @Schema(description = "Ничья")
    private Boolean draw;

    @Schema(description = "Игра была против бота (соперник не найден за 15 сек)")
    private Boolean vsBot;

    @Schema(description = "Игра закончилась досрочной сдачей одной из сторон")
    private Boolean forfeited;

    @Schema(description = "Имя соперника (или \"Бот\", если vsBot=true)")
    private String opponentName;

    @Schema(description = "Аватар соперника (null для бота)")
    private String opponentAvatar;

    @Schema(description = "Дата и время завершения игры")
    private LocalDateTime playedAt;
}
