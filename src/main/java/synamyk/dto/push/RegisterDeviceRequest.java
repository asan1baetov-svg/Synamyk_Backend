package synamyk.dto.push;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import synamyk.enums.DevicePlatform;

@Data
@Schema(description = "Регистрация FCM-токена устройства текущего пользователя")
public class RegisterDeviceRequest {

    @NotBlank
    @Schema(description = "FCM registration token")
    private String token;

    @NotNull
    @Schema(description = "Платформа устройства")
    private DevicePlatform platform;
}
