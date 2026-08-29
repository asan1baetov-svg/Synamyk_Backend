# Synamyk Backend — ТЗ: цены на подтесты + расписание платности

> Задача для Claude Code по **бэкенду** (`/Users/user/IdeaProjects/Synamyk-Backend`, Spring Boot + PostgreSQL + Flyway).
> Фронт-админка (`/Users/user/Desktop/synamyk-admin-main`) будет обновлена под этот контракт отдельно — здесь только серверная часть.
>
> **Две фичи:**
> 1. **Независимая покупка подтестов.** У каждого подтеста своя цена; подтест покупается отдельно. Покупка всего теста (bundle) остаётся и открывает все его подтесты.
> 2. **Расписание платности.** У теста и у подтеста есть окно бесплатности: «бесплатен до даты», «бесплатен с даты» (= платным до даты), «бесплатен в промежутке». В это окно контент бесплатен для **всех** пользователей, включая будущих, без создания строк доступа.

---

## 0. Что есть сейчас (снято из кода — не менять поведение сверх описанного)

- **Модель доступа — только на уровне ТЕСТА.** `UserTestAccess(user, test, granted_at, expires_at)`, одна строка на пару, `expires_at IS NULL` = бессрочно.
- `SubTest.isPaid: Boolean` — флаг «платный», **цены у подтеста нет**. `Test.price: BigDecimal` — единая цена, открывающая все платные подтесты.
- Проверка доступа к подтесту в 2 местах:
  - `TestService.getTestDetail(...)` → `boolean subTestAccess = !st.getIsPaid() || hasAccess;` где `hasAccess = accessRepository.existsActiveAccess(userId, testId, now)`.
  - `TestSessionService.startSession(...)` → `if (subTest.getIsPaid() && !accessRepository.existsActiveAccess(userId, subTest.getTest().getId(), now)) throw ...`.
- Оплата: `PaymentService.initPayment(userId, testId)` → `amount = test.getPrice()`, guard `existsActiveAccess`. Вебхук `processWebhook` → `grantTestAccess(user, test)` → `UserTestAccess` с `expires_at = null` (permanent).
- Ручная выдача: `AdminAccessService.grant(GrantAccessRequest{userId, testId, durationDays?, durationHours?, expiresAt?})`. Приоритет срока: `expiresAt` → `durationDays+durationHours` → ничего = бессрочно.
- Миграции: Flyway, `src/main/resources/db/migration/`, последняя `V15`. Следующая — **`V16`**.
- Тесты: JUnit есть (`src/test/java/synamyk/service/*`).

---

## 1. Изменения схемы (`V16__subtest_pricing_and_schedule.sql`)

```sql
-- 1. Цена подтеста
ALTER TABLE sub_tests
    ADD COLUMN IF NOT EXISTS price NUMERIC(10, 2) NOT NULL DEFAULT 0;

-- Бэкфилл: у уже платных подтестов ставим цену родительского теста,
-- чтобы фича не «разлочила» их бесплатно.
UPDATE sub_tests s
SET price = COALESCE((SELECT t.price FROM tests t WHERE t.id = s.test_id), 0)
WHERE s.is_paid = TRUE AND s.price = 0;

-- 2. Окно бесплатности на тесте и подтесте
ALTER TABLE tests
    ADD COLUMN IF NOT EXISTS free_from  TIMESTAMP,
    ADD COLUMN IF NOT EXISTS free_until TIMESTAMP;

ALTER TABLE sub_tests
    ADD COLUMN IF NOT EXISTS free_from  TIMESTAMP,
    ADD COLUMN IF NOT EXISTS free_until TIMESTAMP;

-- 3. Доступ на уровне подтеста (зеркало user_test_access)
CREATE TABLE IF NOT EXISTS user_sub_test_access
(
    id          BIGSERIAL PRIMARY KEY,
    user_id     BIGINT    NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    sub_test_id BIGINT    NOT NULL REFERENCES sub_tests (id) ON DELETE CASCADE,
    granted_at  TIMESTAMP NOT NULL DEFAULT now(),
    expires_at  TIMESTAMP,
    CONSTRAINT uq_user_sub_test UNIQUE (user_id, sub_test_id)
);
CREATE INDEX IF NOT EXISTS idx_user_sub_test_access_expires ON user_sub_test_access (expires_at);
CREATE INDEX IF NOT EXISTS idx_user_sub_test_access_user    ON user_sub_test_access (user_id);

-- 4. Платёж может быть привязан к подтесту (NULL = покупка всего теста, как раньше)
ALTER TABLE payments
    ADD COLUMN IF NOT EXISTS sub_test_id BIGINT REFERENCES sub_tests (id);
```

---

## 2. Сущности

- **`SubTest`**: `+ BigDecimal price` (`@Builder.Default = BigDecimal.ZERO`, `nullable=false`), `+ LocalDateTime freeFrom`, `+ LocalDateTime freeUntil`.
- **`Test`**: `+ LocalDateTime freeFrom`, `+ LocalDateTime freeUntil`. `price` остаётся — теперь это **bundle-цена** (открывает весь тест разом).
- **`UserSubTestAccess`** — новая сущность, копия `UserTestAccess`, но с `subTest` вместо `test`.
- **`Payment`**: `+ @ManyToOne(fetch = LAZY) @JoinColumn(name = "sub_test_id") SubTest subTest` (nullable). `test` оставить (нужен для отчётов и совместимости; при покупке подтеста `test = subTest.getTest()`).

---

## 3. Ядро: разрешение доступа

Единый helper (напр. `AccessResolver` / метод в `TestService`), **заменяет обе текущие проверки**.

```java
/** Окно бесплатности активно, если оно задано и now попадает внутрь. */
static boolean isFreeNow(LocalDateTime freeFrom, LocalDateTime freeUntil, LocalDateTime now) {
    if (freeFrom == null && freeUntil == null) return false;
    boolean afterStart = (freeFrom == null)  || !now.isBefore(freeFrom);
    boolean beforeEnd  = (freeUntil == null) || now.isBefore(freeUntil);
    return afterStart && beforeEnd;
}
```

| Что задал админ | freeFrom | freeUntil | Поведение |
|---|---|---|---|
| «Бесплатен до 08.09» | `null` | `2026-09-08T00:00` | бесплатно, пока `now < 08.09` |
| «Платен до 08.09» (бесплатен с 08.09) | `2026-09-08T00:00` | `null` | бесплатно с `08.09` и далее |
| «Бесплатен 01.09–08.09» | `2026-09-01` | `2026-09-08` | бесплатно только в промежутке |
| нет окна | `null` | `null` | обычная логика платности |

```java
boolean hasSubTestAccess(Long userId, SubTest st, LocalDateTime now) {
    if (!st.getIsPaid()) return true;
    Long testId = st.getTest().getId();
    if (isFreeNow(st.getTest().getFreeFrom(), st.getTest().getFreeUntil(), now)) return true;
    if (isFreeNow(st.getFreeFrom(),           st.getFreeUntil(),           now)) return true;
    if (userTestAccessRepo.existsActiveAccess(userId, testId, now))       return true; // bundle / legacy / ручная выдача теста
    if (userSubTestAccessRepo.existsActiveAccess(userId, st.getId(), now)) return true; // покупка/выдача подтеста
    return false;
}
```

Точки замены:
- `TestService.getTestDetail` → `boolean subTestAccess = hasSubTestAccess(userId, st, now);`
- `TestSessionService.startSession` → guard: `if (!hasSubTestAccess(userId, subTest, now)) throw new AppException("Нет доступа. Пожалуйста, приобретите подтест.", "Мүмкүнчүлүк жок. Подтестти сатып алыңыз.");`

**Валидация консистентности** (в pricing-эндпоинтах): `isPaid == true` ⇒ `price > 0`. Иначе `AppException` («Платный подтест должен иметь цену больше 0.»).

---

## 4. Оплата

### `POST /api/payments/init` — теперь принимает `testId` **или** `subTestId` (ровно один)

```
POST /api/payments/init?subTestId=3      // покупка одного подтеста
POST /api/payments/init?testId=2         // покупка всего теста (bundle) — без изменений
```
- оба или ни одного → `400` («Укажите testId или subTestId.»).
- `subTestId`:
  - подтест не найден / `active=false` → `400`.
  - `!isPaid` или `price <= 0` → `400` («Этот подтест не продаётся.»).
  - уже есть доступ (`hasSubTestAccess`) → `400` («Уже куплено.»).
  - `amount = subTest.getPrice()`, `payment.subTest = subTest`, `payment.test = subTest.getTest()`.
- `testId`: как сейчас (`amount = test.getPrice()`, `payment.subTest = null`), guard — `existsActiveAccess(userId, testId, now)`.

`InitPaymentResponse.nameEn` — для подтеста: `truncate(test.title + " — " + subTest.title, 50)`.

### `processWebhook` → `grantAccess(Payment payment)`

```java
if (payment.getSubTest() != null) {
    UserSubTestAccess a = userSubTestAccessRepo
        .findByUserIdAndSubTestId(payment.getUser().getId(), payment.getSubTest().getId())
        .orElseGet(() -> UserSubTestAccess.builder()
            .user(payment.getUser()).subTest(payment.getSubTest()).build());
    a.setGrantedAt(LocalDateTime.now());
    a.setExpiresAt(null); // покупка = бессрочно
    userSubTestAccessRepo.save(a);
} else {
    grantTestAccess(payment.getUser(), payment.getTest()); // как сейчас
}
```

### DTO истории/отчётов
- `PaymentHistoryEntry`: `+ Long subTestId`, `+ String subTestTitle` (локализовано; `null` для bundle-покупки).
- `AdminPaymentResponse`: `+ Long subTestId`, `+ String subTestTitle` (`null` для bundle).
- `AdminPaymentService` — при маппинге подставлять поля подтеста.
- **Отчёт по оплатам** (`PaymentReportResponse`): группировка `byTest` остаётся (у платежа всегда есть `test_id`). Добавить `bySubTest: [{ subTestId, subTestTitle, count, revenue }]` — **опционально**, если несложно.

---

## 5. Админ: управление ценами

### 5.1 Цена в CRUD подтеста

- `CreateSubTestRequest`: `+ @NotNull @DecimalMin(value = "0", inclusive = true) BigDecimal price` (по умолчанию `0`).
- `AdminTestResponse.AdminSubTestResponse` (и любой другой admin-serialization подтеста, напр. ответ `POST/PUT /api/admin/tests/{id}/sub-tests`): `+ BigDecimal price`, `+ LocalDateTime freeFrom`, `+ LocalDateTime freeUntil`.
- `AdminTestService.createSubTest / updateSubTest` — писать `price`. Валидация `isPaid ⇒ price > 0`.

### 5.2 Массовая простановка цен — заменить `PUT /api/admin/tests/{testId}/pricing`

Текущее тело `{ price, paidSubTestIds }` **удаляется** (фронт наш, обновим в той же волне).

```
PUT /api/admin/tests/{testId}/pricing
{
  "price": 1000.00,                                   // bundle-цена всего теста; 0 допустимо
  "subTests": [
    { "subTestId": 3, "isPaid": true,  "price": 300.00 },
    { "subTestId": 5, "isPaid": true,  "price": 500.00 },
    { "subTestId": 7, "isPaid": false, "price": 0 }
  ]
}
```
- Полная перезапись: подтест, **не** попавший в `subTests`, → `isPaid = false`, `price = 0`.
- Валидация: каждый `subTestId` принадлежит `testId` (иначе `400`); `isPaid == true ⇒ price > 0` (иначе `400` с указанием подтеста); `price >= 0`.
- Ответ — `AdminTestResponse` (с новыми полями).

### 5.3 Расписание платности — новые эндпоинты

```
PATCH /api/admin/tests/{testId}/schedule
PATCH /api/admin/sub-tests/{subTestId}/schedule
Body: { "freeFrom": "2026-09-01T00:00:00" | null, "freeUntil": "2026-09-08T00:00:00" | null }
```
- Оба поля nullable. `{ "freeFrom": null, "freeUntil": null }` — очистить окно.
- Валидация: если оба заданы — `freeUntil > freeFrom` (иначе `400`).
- Даты — `LocalDateTime` без зоны (сервер живёт в Asia/Bishkek, как и всё остальное API).
- Ответ: обновлённый `AdminTestResponse` / admin-DTO подтеста.

---

## 6. Админ: ручная выдача доступа — расширить на подтесты

`GrantAccessRequest`: `+ Long subTestId` (nullable). Правило: **ровно один** из `testId` / `subTestId` (Bean Validation через `@AssertTrue` метод или проверка в сервисе → `AppException`).

```
POST   /api/admin/access        { "userId": 5, "testId": 2, ...срок }      // как сейчас — доступ ко всему тесту
POST   /api/admin/access        { "userId": 5, "subTestId": 3, ...срок }   // НОВОЕ — доступ к одному подтесту
DELETE /api/admin/access?userId=5&subTestId=3
GET    /api/admin/access?subTestId=3        // список выдач по подтесту
GET    /api/admin/access?userId=5           // теперь включает и подтест-выдачи (см. ниже)
```

- `AdminAccessService`:
  - ветка `subTestId` → upsert/rev/list через `UserSubTestAccessRepository`, `resolveExpiry(...)` переиспользовать as-is.
  - `listByUser(userId)` → вернуть **объединённый** список: строки `UserTestAccess` + строки `UserSubTestAccess`, отсортировано по `granted_at desc`.
  - `GET` без `userId`/`testId`/`subTestId` → `400` (как сейчас).
- `AccessGrantResponse`: `+ Long subTestId` (nullable), `+ String subTestTitle` (nullable). Для строк уровня теста — оба `null`. Поле `status` (PERMANENT|ACTIVE|EXPIRED) считается так же.

`UserSubTestAccessRepository` — зеркало `UserTestAccessRepository`:
`existsByUserIdAndSubTestId`, `findByUserIdAndSubTestId`, `existsActiveAccess(userId, subTestId, now)` (JPQL с `expiresAt IS NULL OR expiresAt > :now`), `findByUserIdOrderByGrantedAtDesc`, `findBySubTestIdOrderByGrantedAtDesc`, `deleteByUserIdAndSubTestId`, `findActiveUserIdsBySubTestId(subTestId, now)`.

---

## 7. Мобильные / публичные DTO

- **`SubTestResponse`** (`/api/tests/{id}` detail): `+ BigDecimal price`, `+ Boolean effectiveFree` (результат `isFreeNow(test)||isFreeNow(subTest)`), `+ LocalDateTime freeUntil` (ближайшая дата окончания бесплатности, если применимо — для «бесплатно ещё N дней»). `hasAccess` остаётся и теперь учитывает всю новую логику (`hasSubTestAccess`).
- **`TestDetailResponse` / `TestListResponse`**: `+ LocalDateTime freeUntil` (опционально — для бейджа «Акция»). Не обязательно.
- Поле `isPaid` у подтеста в ответах не убирать.

⚠️ Flutter-клиент нужно будет доработать (кнопка «Купить подтест», обработка `effectiveFree`, покупка по `subTestId`) — это вне этой задачи, но зафиксируйте в PR-описании.

---

## 8. Совместимость и крайние случаи

1. **Bundle-доступ открывает всё.** `UserTestAccess` (покупка теста, ручная выдача теста, legacy-строки) → `hasSubTestAccess` возвращает `true` для всех подтестов теста. Не ломать.
2. **Бэкфилл цен** (миграция) не даёт «разлочить» текущие платные подтесты. Проверить: подтест с `isPaid=true`, у которого до миграции не было явной цены, после миграции имеет `price = tests.price` и остаётся платным.
3. `isPaid=true & price=0` — недопустимая конфигурация: pricing-эндпоинты отклоняют (`400`). Резолвер при этом (если такое всё же попало в БД) трактует подтест как **платный/закрытый** (не бесплатный) — т.е. никаких неявных «price=0 ⇒ бесплатно».
4. `initPayment(testId=...)` для bundle остаётся рабочим даже если у теста есть платные подтесты с индивидуальными ценами — bundle-цена независима.
5. Пуш «новый подтест» (`findActiveUserIdsByTestId`) не трогаем.
6. Часовые пояса: все новые `LocalDateTime` — без зоны, Asia/Bishkek, как и остальное API.

---

## 9. Тесты (JUnit)

- `isFreeNow` — таблица кейсов из раздела 3 (обе границы null / только from / только until / промежуток / вне промежутка).
- `hasSubTestAccess` — бесплатный подтест; окно теста; окно подтеста; активный `UserTestAccess`; активный `UserSubTestAccess`; истёкший доступ; ничего.
- `PaymentService` — `initPayment(subTestId)` создаёт `Payment` с `subTest` и корректной `amount`; вебхук создаёт `UserSubTestAccess` с `expiresAt = null`; повторная покупка при активном доступе → `400`.
- `AdminTestService.updateTestPricing` — полная перезапись; отклонение `isPaid && price<=0`; отклонение чужого `subTestId`.
- `AdminAccessService` — grant/revoke/list по `subTestId`; `listByUser` объединяет оба типа; `400` при `testId` и `subTestId` одновременно.

---

## 10. Итоговый список эндпоинтов (дельта)

| Метод | Путь | Изменение |
|---|---|---|
| POST | `/api/payments/init` | `?testId` **или** `?subTestId` |
| PUT | `/api/admin/tests/{testId}/pricing` | новое тело `{ price, subTests:[{subTestId,isPaid,price}] }` |
| PATCH | `/api/admin/tests/{testId}/schedule` | **новый** — `{ freeFrom, freeUntil }` |
| PATCH | `/api/admin/sub-tests/{subTestId}/schedule` | **новый** — `{ freeFrom, freeUntil }` |
| POST/PUT | `/api/admin/tests/{testId}/sub-tests`, `/api/admin/sub-tests/{id}` | тело `+ price` |
| POST | `/api/admin/access` | тело `+ subTestId` (взаимоисключающе с `testId`) |
| GET/DELETE | `/api/admin/access` | `+ ?subTestId=`; `listByUser` объединяет типы |
| — | admin/feed DTO подтеста | `+ price, freeFrom, freeUntil, effectiveFree` |
| — | `AdminPaymentResponse`, `PaymentHistoryEntry` | `+ subTestId, subTestTitle` |

После реализации — обновить Swagger-описания и приложить обновлённый фрагмент контракта, чтобы синхронизировать фронт-админку.
