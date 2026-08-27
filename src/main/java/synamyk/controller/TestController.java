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
import synamyk.dto.TestDetailResponse;
import synamyk.dto.TestListResponse;
import synamyk.entities.User;
import synamyk.service.TestService;
import synamyk.util.LangResolver;

import java.util.List;

@RestController
@RequestMapping("/api/tests")
@RequiredArgsConstructor
@Tag(name = "Тесты", description = "Получение списка тестов и детальной информации о тесте. " +
        "Контент возвращается на языке, выбранном пользователем в профиле (RU / KY).")
@SecurityRequirement(name = "Bearer")
public class TestController {

    private final TestService testService;
    private final LangResolver langResolver;

    @GetMapping
    @Operation(
            summary = "Список всех активных тестов",
            description = "Возвращает список тестов для главной страницы. " +
                    "Каждый тест содержит: название, описание, иконку, цену и количество подтестов. " +
                    "Контент локализован по языку пользователя."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Список тестов"),
            @ApiResponse(responseCode = "401", description = "Не авторизован — JWT токен отсутствует или недействителен")
    })
    public ResponseEntity<List<TestListResponse>> getAllTests(
            @AuthenticationPrincipal UserDetails userDetails,
            Authentication authentication) {
        User user = (User) authentication.getPrincipal();
        return ResponseEntity.ok(testService.getAllTests(user.getId(), langResolver.resolve(userDetails)));
    }

    @GetMapping("/{testId}")
    @Operation(
            summary = "Детальная информация о тесте",
            description = "Возвращает тест со списком всех подтестов. " +
                    "Для каждого подтеста указывается: доступен ли он (бесплатный или куплен), " +
                    "был ли уже пройден, количество вопросов и длительность."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Тест с подтестами"),
            @ApiResponse(responseCode = "404", description = "Тест не найден"),
            @ApiResponse(responseCode = "401", description = "Не авторизован")
    })
    public ResponseEntity<TestDetailResponse> getTestDetail(
            @Parameter(description = "ID теста") @PathVariable Long testId,
            @AuthenticationPrincipal UserDetails userDetails,
            Authentication authentication) {
        User user = (User) authentication.getPrincipal();
        return ResponseEntity.ok(testService.getTestDetail(testId, user.getId(), langResolver.resolve(userDetails)));
    }
}