package synamyk.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import synamyk.dto.*;
import synamyk.repo.UserRepository;
import synamyk.service.ProfileService;

@RestController
@RequestMapping("/api/profile")
@RequiredArgsConstructor
@Tag(name = "Профиль", description = "Просмотр и редактирование профиля пользователя, смена данных, удаление аккаунта")
@SecurityRequirement(name = "Bearer")
public class ProfileController {

    private final ProfileService profileService;
    private final UserRepository userRepository;

    @GetMapping
    @Operation(
            summary = "Получить профиль текущего пользователя",
            description = "Возвращает данные профиля и статистику: количество пройденных тестов, " +
                    "суммарный балл (сумма правильных ответов за все сессии) и количество приглашённых пользователей."
    )
    @ApiResponse(responseCode = "200", description = "Данные профиля со статистикой")
    public ResponseEntity<ProfileResponse> getProfile(@AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(profileService.getProfile(resolveUserId(userDetails)));
    }

    @PutMapping
    @Operation(
            summary = "Редактировать профиль",
            description = "Обновляет имя, фамилию, описание (bio) и URL аватара. " +
                    "Аватар загружается через POST /api/upload (тип AVATAR) и URL передаётся в это поле."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Обновлённый профиль"),
            @ApiResponse(responseCode = "401", description = "Не авторизован")
    })
    public ResponseEntity<ProfileResponse> updateProfile(
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestBody UpdateProfileRequest request) {
        return ResponseEntity.ok(profileService.updateProfile(resolveUserId(userDetails), request));
    }

    @PostMapping(value = "/avatar", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(
            summary = "Загрузить / сменить аватар",
            description = "Один запрос: multipart-поле `file` (изображение до 10 МБ). Загружает файл, " +
                    "делает его аватаром текущего пользователя, удаляет предыдущий. " +
                    "Возвращает обновлённый профиль с presigned `avatarUrl` (действителен 1 час)."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Аватар обновлён"),
            @ApiResponse(responseCode = "400", description = "Файл пустой, не изображение или больше 10 МБ"),
            @ApiResponse(responseCode = "401", description = "Не авторизован")
    })
    public ResponseEntity<ProfileResponse> uploadAvatar(
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestPart("file") MultipartFile file) {
        return ResponseEntity.ok(profileService.updateAvatar(resolveUserId(userDetails), file));
    }

    @DeleteMapping("/avatar")
    @Operation(summary = "Удалить аватар", description = "Сбрасывает `avatarUrl` в null и удаляет файл из хранилища.")
    @ApiResponse(responseCode = "200", description = "Аватар удалён")
    public ResponseEntity<ProfileResponse> deleteAvatar(@AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(profileService.deleteAvatar(resolveUserId(userDetails)));
    }

    @PostMapping("/change-phone/request")
    @Operation(
            summary = "Запрос на смену номера телефона",
            description = "Проверяет, что старый номер совпадает с текущим, и отправляет 4-значный OTP-код " +
                    "на новый номер через SMSPRO. Новый номер не должен быть уже зарегистрирован."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "OTP отправлен на новый номер"),
            @ApiResponse(responseCode = "400", description = "Старый номер не совпадает или новый уже зарегистрирован")
    })
    public ResponseEntity<OtpSendResponse> requestPhoneChange(
            @AuthenticationPrincipal UserDetails userDetails,
            @Valid @RequestBody ChangePhoneRequest request) {
        return ResponseEntity.ok(profileService.requestPhoneChange(resolveUserId(userDetails), request));
    }

    @PostMapping("/change-phone/confirm")
    @Operation(
            summary = "Подтверждение смены номера телефона",
            description = "Проверяет OTP-код, отправленный на новый номер, и обновляет номер пользователя. " +
                    "После успешной смены нужно повторно войти для получения нового JWT."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Номер телефона успешно изменён"),
            @ApiResponse(responseCode = "400", description = "Неверный или истёкший OTP-код")
    })
    public ResponseEntity<MessageResponse> confirmPhoneChange(
            @AuthenticationPrincipal UserDetails userDetails,
            @Valid @RequestBody ConfirmPhoneChangeRequest request) {
        return ResponseEntity.ok(profileService.confirmPhoneChange(resolveUserId(userDetails), request));
    }

    @PutMapping("/change-password")
    @Operation(
            summary = "Смена пароля",
            description = "Проверяет старый пароль и устанавливает новый. " +
                    "Новый пароль должен совпадать с полем `confirmPassword` и содержать минимум 6 символов."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Пароль успешно изменён"),
            @ApiResponse(responseCode = "400", description = "Старый пароль неверен или пароли не совпадают")
    })
    public ResponseEntity<MessageResponse> changePassword(
            @AuthenticationPrincipal UserDetails userDetails,
            @Valid @RequestBody ChangePasswordRequest request) {
        return ResponseEntity.ok(profileService.changePassword(resolveUserId(userDetails), request));
    }

    @PutMapping("/change-region")
    @Operation(
            summary = "Смена региона",
            description = "Обновляет регион пользователя. Список регионов: GET /api/regions."
    )
    @ApiResponse(responseCode = "200", description = "Регион обновлён")
    public ResponseEntity<MessageResponse> changeRegion(
            @AuthenticationPrincipal UserDetails userDetails,
            @Valid @RequestBody ChangeRegionRequest request) {
        return ResponseEntity.ok(profileService.changeRegion(resolveUserId(userDetails), request));
    }

    @PutMapping("/language")
    @Operation(
            summary = "Смена языка интерфейса",
            description = "Устанавливает предпочтительный язык интерфейса: **RU** (русский) или **KY** (кыргызский). " +
                    "После смены все эндпоинты с локализованным контентом будут возвращать данные на выбранном языке."
    )
    @ApiResponse(responseCode = "200", description = "Язык изменён")
    public ResponseEntity<MessageResponse> changeLanguage(
            @AuthenticationPrincipal UserDetails userDetails,
            @Valid @RequestBody ChangeLanguageRequest request) {
        return ResponseEntity.ok(profileService.changeLanguage(resolveUserId(userDetails), request));
    }

    @DeleteMapping
    @Operation(
            summary = "Удалить аккаунт",
            description = "удаление аккаунта " +
                    "Пользователь больше не сможет войти " +
                    "Данные сохраняются в базе для истории."
    )
    @ApiResponse(responseCode = "200", description = "Аккаунт удалён")
    public ResponseEntity<MessageResponse> deleteAccount(@AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(profileService.deleteAccount(resolveUserId(userDetails)));
    }

    private Long resolveUserId(UserDetails userDetails) {
        return userRepository.findByPhone(userDetails.getUsername())
                .orElseThrow(() -> new RuntimeException("Пользователь не найден"))
                .getId();
    }
}