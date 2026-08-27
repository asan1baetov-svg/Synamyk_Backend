package synamyk.dto.push;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import synamyk.enums.BroadcastAudience;
import synamyk.enums.PushDataType;

import java.time.LocalDateTime;

@Data
@Schema(description = "Push-рассылка. По умолчанию — всем пользователям с зарегистрированным устройством, немедленно.")
public class BroadcastRequest {

    @NotBlank
    @Schema(description = "Заголовок (RU)", example = "Новый тест доступен!")
    private String title;

    @NotBlank
    @Schema(description = "Текст (RU)", example = "Попробуйте новый тест по математике")
    private String body;

    @Schema(description = "Заголовок (KY), опционально — иначе используется RU")
    private String titleKy;

    @Schema(description = "Текст (KY), опционально — иначе используется RU")
    private String bodyKy;

    @Schema(description = "Кому отправлять", defaultValue = "ALL")
    private BroadcastAudience audience = BroadcastAudience.ALL;

    @Schema(description = "Параметр аудитории: csv userId / ANDROID|IOS|WEB / testId / число дней",
            example = "12,45,78")
    private String audienceRef;

    @Schema(description = "Тип deep-link для клиента", defaultValue = "NONE")
    private PushDataType dataType = PushDataType.NONE;

    @Schema(description = "ID сущности для deep-link (testId / subTestId / gameTestId)")
    private Long dataEntityId;

    @Schema(description = "Когда отправить. Если в будущем — рассылка планируется; иначе отправляется сразу.")
    private LocalDateTime scheduledAt;
}
