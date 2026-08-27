package synamyk.dto.push;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "Частичное обновление настроек уведомлений. Передавайте только изменяемые поля.")
public class NotificationSettingsRequest {

    @Schema(description = "Результаты сессий и готовность ИИ-разбора")
    private Boolean results;

    @Schema(description = "Напоминания о занятиях")
    private Boolean reminders;

    @Schema(description = "Рассылки и новый контент")
    private Boolean marketing;
}
