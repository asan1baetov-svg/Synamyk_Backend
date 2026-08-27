package synamyk.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import synamyk.dto.admin.AccessGrantResponse;
import synamyk.dto.admin.GrantAccessRequest;
import synamyk.exception.AppException;
import synamyk.service.AdminAccessService;

import java.util.List;

@RestController
@RequestMapping("/api/admin/access")
@RequiredArgsConstructor
@Tag(name = "Админ — Доступ к тестам",
        description = "Ручная выдача, продление и отзыв доступа пользователя к тесту. Требуется роль ADMIN.")
@SecurityRequirement(name = "Bearer")
public class AdminAccessController {

    private final AdminAccessService adminAccessService;

    @PostMapping
    @Operation(
            summary = "Выдать / продлить доступ",
            description = "Создаёт доступ или заменяет срок у существующего. "
                    + "Срок: `expiresAt` (точная дата) → `durationDays`/`durationHours` (от текущего момента) → "
                    + "ничего не передано = бессрочно."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Доступ выдан/обновлён"),
            @ApiResponse(responseCode = "400", description = "Пользователь или тест не найден"),
            @ApiResponse(responseCode = "403", description = "Требуется роль ADMIN")
    })
    public ResponseEntity<AccessGrantResponse> grant(@Valid @RequestBody GrantAccessRequest request) {
        return ResponseEntity.ok(adminAccessService.grant(request));
    }

    @DeleteMapping
    @Operation(summary = "Отозвать доступ", description = "Полностью удаляет запись доступа пользователя к тесту.")
    @ApiResponse(responseCode = "200", description = "Доступ отозван (или его не было)")
    public ResponseEntity<Void> revoke(
            @Parameter(description = "ID пользователя") @RequestParam Long userId,
            @Parameter(description = "ID теста") @RequestParam Long testId) {
        adminAccessService.revoke(userId, testId);
        return ResponseEntity.ok().build();
    }

    @GetMapping
    @Operation(
            summary = "Список доступов",
            description = "Передайте `userId` — доступы пользователя, либо `testId` — все, у кого есть доступ к тесту. "
                    + "Поле `status`: PERMANENT | ACTIVE | EXPIRED."
    )
    public ResponseEntity<List<AccessGrantResponse>> list(
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) Long testId) {
        if (userId != null) return ResponseEntity.ok(adminAccessService.listByUser(userId));
        if (testId != null) return ResponseEntity.ok(adminAccessService.listByTest(testId));
        throw new AppException("Укажите userId или testId.", "userId же testId көрсөтүңүз.");
    }
}
