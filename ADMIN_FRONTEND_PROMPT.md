# Synamyk Admin Panel — техническое задание для Claude Code

> Это ТЗ на веб-админку для образовательной платформы Synamyk (подготовка к ОРТ, Кыргызстан).
> Backend уже готов — Spring Boot REST API. Твоя задача: построить фронтенд.
> **Приоритет №1 — блок «Тесты → Подтесты → Вопросы» с математическим редактором формул.**

---

## 0. Стек и общие правила

**Стек (используй именно это, если не сказано иначе):**
- React 18 + TypeScript + Vite
- React Router v6
- TanStack Query (react-query) — весь серверный стейт, кэш, инвалидация
- React Hook Form + Zod — все формы и валидация
- Tailwind CSS + shadcn/ui — вёрстка и компоненты
- axios — http-клиент с интерсепторами
- **MathLive** (`mathlive`) — WYSIWYG-ввод формул
- **KaTeX** (`katex`) — рендер формул в режиме просмотра
- `sonner` или shadcn `toast` — уведомления
- `date-fns` — даты
- `recharts` — графики в отчётах

**Язык интерфейса:** русский. Контент в БД двуязычный (RU + KY) — см. раздел 4.6.

**Структура проекта:**
```
src/
  api/           # axios instance + типизированные функции по доменам
  types/         # TS-типы, зеркалящие DTO бэкенда
  hooks/         # useTests, useQuestions, ... (обёртки над react-query)
  components/
    ui/          # shadcn
    math/        # MathField, MathText, MathToolbar  ← ключевой модуль
    common/      # DataTable, PageHeader, ConfirmDialog, EmptyState, BilingualInput
  pages/
    auth/ dashboard/ tests/ users/ payments/ access/ reports/ notifications/ news/ videos/ games/ rating/
  lib/           # utils, formatters, constants
```

---

## 1. API: база

**Base URL:** брать из `VITE_API_URL` (dev: `http://localhost:8080`, prod: продовый домен). Все пути ниже — относительно него.

**CORS** на бэке открыт для всех origin, credentials разрешены. Проблем быть не должно.

**Swagger** доступен на `/swagger-ui.html` — используй как справочник, но **контракты ниже точнее**, они сняты прямо из кода.

### 1.1 Авторизация

```
POST /api/auth/login
{ "phone": "996700000000", "password": "..." }
→ 200
{
  "token": "eyJhbGci...",
  "refreshToken": "eyJhbGci...",
  "userId": 1,
  "phone": "996700000000",
  "role": "ADMIN"
}
```

- Телефон в формате `996XXXXXXXXX` (без `+`, без пробелов). На форме логина сделай маску `+996 (___) __-__-__`, но на бэк отправляй голые цифры с `996`.
- **После логина проверь `role === "ADMIN"`.** Если нет — не пускать в панель, показать «Доступ только для администраторов» и разлогинить.
- `token` класть в `Authorization: Bearer <token>` для всех запросов.
- Хранить токены в `localStorage`.

```
POST /api/auth/refresh
{ "refreshToken": "..." }
→ 200  (тот же AuthResponse)
```

**Интерсептор axios:**
- Response 401 → попытаться один раз обновить токен через `/api/auth/refresh`, повторить исходный запрос. Если refresh тоже 401 → почистить localStorage, редирект на `/login`.
- Не зацикливайся: флаг `isRefreshing` + очередь ожидающих запросов.
- Response 403 → тост «Недостаточно прав», не разлогинивать.

```
POST /api/auth/logout
{ "refreshToken": "..." }        → { "success": true, "message": "..." }
```

### 1.2 Формат ошибок

Бэк отдаёт **три разных формы ошибок** — обработай все:

```jsonc
// 1. Бизнес-ошибка (AppException) и общий RuntimeException — HTTP 400
{ "success": false, "message": "Тест не найден." }

// 2. Ошибка валидации (@Valid не прошёл) — HTTP 400, ПЛОСКИЙ объект поле→сообщение
{ "title": "must not be blank", "options": "size must be between 2 and 6" }

// 3. Auth — HTTP 401 / 403, тело может быть пустым
```

Напиши единый `extractErrorMessage(error)`:
```ts
// если data.message есть → вернуть его
// иначе если data — объект строк → склеить "поле: сообщение" через перенос
// иначе → "Не удалось выполнить запрос"
```
И **прокидывай ошибки валидации в поля формы** (`setError` из react-hook-form) — не только в тост.

### 1.3 Пагинация

Везде, где список большой, бэк возвращает стандартную обёртку Spring `Page`:

```json
{
  "content": [ /* элементы */ ],
  "totalElements": 137,
  "totalPages": 7,
  "number": 0,
  "size": 20,
  "first": true,
  "last": false,
  "numberOfElements": 20,
  "empty": false
}
```
Заведи дженерик `Page<T>` и общий компонент `DataTable` с серверной пагинацией. Параметры всегда `?page=0&size=20` (page 0-based).

### 1.4 Даты

**Все даты — `LocalDateTime` без таймзоны и без `Z`:** `"2026-08-27T16:05:11.482"`.
Сервер живёт в **Asia/Bishkek (UTC+6)**.

⚠️ **Ловушка:** `new Date("2026-08-27T16:05:11.482")` в JS распарсит это как **локальное время браузера**, а не бишкекское. Если админ сидит в другой зоне — время съедет.
**Решение:** парсить явно как бишкекское и форматировать в бишкекском:
```ts
// lib/datetime.ts
export const parseServerDate = (s: string) => new Date(s + '+06:00');
export const formatDT = (s?: string | null) =>
  s ? format(parseServerDate(s), 'dd.MM.yyyy HH:mm') : '—';
```
Обратно на сервер (например `scheduledAt`, `expiresAt`) отправлять **без суффикса зоны**: `"2026-09-01T10:00:00"`.

Фильтры `dateFrom` / `dateTo` — формат `yyyy-MM-dd` (только дата).

### 1.5 Загрузка изображений

```
POST /api/upload            multipart/form-data
  file: <изображение, image/*, до 10 МБ>
  type: AVATAR | NEWS_COVER | VIDEO_THUMBNAIL | TEST_ICON | QUESTION_IMAGE   (query param)
→ 200
{
  "url": "https://...?X-Amz-Signature=...",   // presigned, живёт 1 ЧАС — только для показа
  "objectKey": "tests/9f1c-....png"           // ← ЭТО сохранять в поля iconUrl/imageUrl/coverImageUrl
}
```

🔴 **Критично:**
1. В поля сущностей (`iconUrl`, `imageUrl`, `coverImageUrl`, `thumbnailUrl`) сохраняй **`objectKey`**, не `url`.
2. В ответах на чтение бэк сам подставляет свежий presigned `url` — используй его для `<img src>`.
3. Presigned-ссылки живут 1 час → **не кэшируй их надолго** в react-query. Ставь `staleTime` для списков с картинками ≤ 30 минут, иначе картинки отвалятся с 403.
4. Бэк пропускает абсолютные внешние URL как есть — можно вставить `https://example.com/pic.jpg` вручную, он не сломается.

Сделай переиспользуемый `<ImageUploader type="TEST_ICON" value={objectKey} previewUrl={url} onChange={...} />` — drag&drop, превью, прогресс, кнопка «Удалить».

---

## 2. 🔴 ПРИОРИТЕТ: Математический ввод (формулы)

Это **самая важная часть задания**. Платформа про подготовку к ОРТ — половина вопросов математические. Нужен ввод дробей, корней, степеней, интегралов и т.д. — как в онлайн-калькуляторах формул.

### 2.1 Как формулы хранятся

Бэкенд хранит текст вопроса/варианта/пояснения как **обычный `TEXT`**. Отдельных полей под формулы нет и не будет.

**Соглашение (вводим его, соблюдать строго):**
> Формулы записываются в **LaTeX** внутри тех же текстовых полей, обрамлённые знаками доллара:
> - `$...$` — инлайн-формула внутри текста
> - `$$...$$` — формула отдельным блоком по центру

Пример того, что реально уходит в поле `text`:
```
Найдите значение выражения $\frac{3}{4} + \sqrt{16}$ при $x > 0$
```

**Правила экранирования:**
- Одиночный `$` как знак валюты писать как `\$`.
- Ничего не «улучшай» и не нормализуй при сохранении — отправляй строку ровно так, как её собрал редактор.

### 2.2 ⚠️ Что нужно проверить ДО начала (блокирующий вопрос)

Мобильное приложение (Flutter) **сейчас рендерит текст вопроса как обычный текст**. Если админ введёт формулу, ученик увидит сырой `$\frac{3}{4}$`.

**Поэтому:**
1. В README проекта админки напиши явное предупреждение об этом.
2. Сообщи владельцу проекта, что мобильному клиенту нужен рендер LaTeX — пакет `flutter_math_fork` (виджет `Math.tex`), с парсингом текста на сегменты по `$...$` / `$$...$$`.
3. Не блокируй разработку админки из-за этого — просто зафиксируй.

### 2.3 Компонент `<MathField>` — редактор

Основан на **MathLive** (`import 'mathlive'` даёт web-component `<math-field>`). Это полноценное WYSIWYG-поле: пользователь видит дробь как дробь, а на выходе получается LaTeX.

**Требования:**

1. **Два режима в одном компоненте:**
   - **Текст + формулы** (по умолчанию для `text` вопроса, `explanation`) — обычное текстовое поле, куда можно **вставлять формулы как «чипы»**. Реализация: contenteditable-обёртка или textarea + кнопка «Вставить формулу», открывающая модалку с `<math-field>`; вставленная формула попадает в текст как `$...$` и рендерится инлайн в превью.
   - **Чистая формула** (удобно для вариантов ответа — там обычно только число/выражение) — сразу `<math-field>` на всё поле, результат оборачивается в `$...$` автоматически.

   Переключатель режима — маленький тумблер «𝑓(x)» рядом с полем. Режим запоминать в `localStorage` per-поле-тип.

2. **Панель кнопок** (обязательный минимум, иконками, с тултипами):

   | Группа | Кнопки | LaTeX |
   |---|---|---|
   | Дроби | обыкновенная, смешанная | `\frac{}{}`, `n\frac{}{}` |
   | Степени/индексы | степень, индекс | `^{}`, `_{}` |
   | Корни | квадратный, n-й степени | `\sqrt{}`, `\sqrt[n]{}` |
   | Операции | × ÷ ± ∓ · | `\times \div \pm \mp \cdot` |
   | Сравнение | ≤ ≥ ≠ ≈ ≡ | `\le \ge \ne \approx \equiv` |
   | Скобки | ( ) [ ] { } \| \| | `\left( \right)` и т.д. |
   | Функции | sin cos tg ctg log ln lg | `\sin \cos \tan \cot \log \ln \lg` |
   | Матан | ∑ ∏ ∫ lim ∞ | `\sum_{}^{} \prod \int_{}^{} \lim_{} \infty` |
   | Геометрия | ° ∠ △ ∥ ⊥ ~ | `^\circ \angle \triangle \parallel \perp \sim` |
   | Греческие | π α β γ θ φ Δ Ω | `\pi \alpha ...` (выпадашкой) |
   | Множества | ∈ ∉ ⊂ ∪ ∩ ∅ ℝ ℕ ℤ | `\in \notin \subset \cup \cap \emptyset \mathbb{R}...` |
   | Стрелки | → ⇒ ⇔ | `\to \Rightarrow \Leftrightarrow` |
   | Матрицы | 2×2, 3×3, система уравнений | `\begin{pmatrix}`, `\begin{cases}` |
   | Прочее | вектор, среднее, модуль | `\vec{}`, `\overline{}`, `\left|\right|` |

   Панель — компактная, сворачиваемая, с группами во вкладках или поповерах. Не вываливай 60 кнопок сразу.

3. **Ввод с клавиатуры должен работать естественно** (MathLive это умеет из коробки, включи):
   - `/` → дробь, `^` → степень, `_` → индекс
   - `sqrt` + Tab → корень
   - `\frac`, `\sqrt`, `\pi` — прямой ввод LaTeX-команд с автодополнением
   - Tab — переход между «дырками» формулы

4. **Виртуальная клавиатура MathLive** — включи `mathVirtualKeyboardPolicy="manual"` и покажи кнопку «клавиатура» (пригодится на планшете).

5. **Живое превью** — под каждым полем строка `Предпросмотр:` с KaTeX-рендером итоговой строки. Обновляется на лету (debounce 150 мс).

6. **Валидация формул:** перед сохранением прогонять весь текст через `katex.renderToString(..., { throwOnError: true })` для каждого `$...$`-фрагмента. Если ошибка — показать её под полем («Ошибка в формуле: …») и **не давать сохранить**.

7. **Доступность/удобство:**
   - Ctrl+Z / Ctrl+Y внутри формулы работают
   - Копирование формулы даёт LaTeX в буфер
   - Вставка LaTeX из буфера распознаётся

### 2.4 Компонент `<MathText>` — рендер

Принимает строку, разбивает на текстовые и формульные сегменты, рендерит через KaTeX (`\displaystyle` для `$$`), текст оставляет как есть.

```tsx
<MathText value="Найдите $\frac{3}{4} + \sqrt{16}$" />
```

Использовать **везде**, где показывается контент из БД: таблица вопросов, предпросмотр теста, превью варианта ответа, отчёты.

**Настройки KaTeX:** `throwOnError: false` (в рендере — не падать, показывать красным), `strict: false`, `trust: false`. Импортируй `katex/dist/katex.min.css`.

### 2.5 Шаблоны и быстрые вставки

Сделай выпадающий список «Шаблоны» с готовыми заготовками (курсор ставится в первую дырку):
- Квадратное уравнение `ax^2+bx+c=0`
- Дискриминант `D=b^2-4ac`
- Теорема Пифагора `a^2+b^2=c^2`
- Система из двух уравнений
- Дробь с переменными
- Процент `\frac{x}{100}\cdot y`
- Прогрессия `a_n = a_1 + (n-1)d`

Плюс: **последние 10 использованных формул** — в `localStorage`, кнопкой «Недавние». Это сильно ускорит набивку однотипных вопросов.

---

## 3. 🔴 ПРИОРИТЕТ: Тесты → Подтесты → Вопросы

Иерархия: **Тест** (например «ОРТ Математика», имеет цену) → **Подтесты** = уровни (например «Уровень A1», может быть платным/бесплатным, имеет длительность) → **Вопросы** (с вариантами ответов и баллами).

### 3.1 Список тестов

```
GET /api/admin/tests?page=0&size=20&search=&subject=&active=
→ Page<AdminTestListResponse>
```
```json
{
  "id": 2,
  "title": "ОРТ — Полный курс (математика)",
  "iconUrl": "https://...presigned...",
  "subject": "Математика",
  "price": 500.00,
  "questionCount": 8,
  "attemptsCount": 143,
  "createdAt": "2026-07-27T08:56:55.588",
  "active": true
}
```

Есть альтернативный режим — **весь список без пагинации, вместе с подтестами**:
```
GET /api/admin/tests?full=true   → AdminTestResponse[]   (не Page!)
```
⚠️ Тип ответа меняется в зависимости от параметра. Типизируй как перегрузку, не смешивай.

```
GET /api/admin/tests/subjects   → ["Математика", "Логика", ...]
```
Использовать для выпадашки фильтра «Предмет» и для автодополнения при создании теста.

**UI:** таблица (иконка, название, предмет, цена, кол-во вопросов, кол-во попыток, статус, дата). Фильтры сверху: поиск, предмет, статус (все/активные/скрытые). Клик по строке → страница теста.

### 3.2 Карточка теста

```
GET /api/admin/tests/{testId}   → AdminTestResponse
```
```json
{
  "id": 2,
  "title": "ОРТ — Полный курс (математика)",
  "titleKy": "ЖРТ — Толук курс (математика)",
  "description": "…",
  "descriptionKy": "…",
  "iconUrl": "https://...presigned...",
  "price": 500.00,
  "active": true,
  "subTests": [
    {
      "id": 2,
      "title": "Вводный уровень",
      "titleKy": "Киришүү деңгээли",
      "levelName": "1-уровень",
      "levelNameKy": "1-деңгээл",
      "levelOrder": 1,
      "isPaid": false,
      "durationMinutes": 20,
      "questionCount": 3,
      "active": true
    }
  ]
}
```
⚠️ В `AdminTestResponse` **нет поля `subject`**, хотя в `CreateTestRequest` оно есть и в списке оно возвращается. При редактировании подтягивай `subject` из строки списка или из `/tests/subjects`; если пусто — оставляй поле пустым, не затирай вслепую.

### 3.3 CRUD теста

```
POST /api/admin/tests            → AdminTestResponse
PUT  /api/admin/tests/{testId}   → AdminTestResponse
```
Тело (одинаковое для обоих):
```json
{
  "title": "ОРТ — Математика",         // required, NotBlank
  "titleKy": "ЖРТ — Математика",
  "description": "Полный курс…",
  "descriptionKy": "…",
  "iconUrl": "tests/9f1c-abc.png",     // objectKey из /api/upload
  "subject": "Математика",
  "price": 500.00                       // required, NotNull. 0 = бесплатный тест
}
```
```
DELETE /api/admin/tests/{testId}   → 204   // мягкое удаление: active=false
```
В UI называть «Скрыть тест», а не «Удалить» — данные остаются. Дать возможность вернуть (через `PUT` — но поля `active` в `CreateTestRequest` **нет**). ⚠️ **Ограничение бэка: включить обратно скрытый тест через API нельзя.** Отметь это в README и не рисуй кнопку «Восстановить», которая не сработает.

### 3.4 Цены и платность подтестов

Две независимые ручки — не путать:

```
PUT /api/admin/tests/{testId}/pricing
{ "price": 500.00, "paidSubTestIds": [3, 5] }
→ AdminTestResponse
```
⚠️ **Полная перезапись:** все подтесты, НЕ попавшие в `paidSubTestIds`, становятся **бесплатными**. Всегда отправляй полный список платных, а не дельту.

```
PATCH /api/admin/sub-tests/{subTestId}/paid?paid=true    → AdminSubTestResponse
```
Точечный тумблер, остальные подтесты не трогает.

**UI:** на странице теста — карточка «Монетизация»: поле цены + список подтестов с чекбоксами «платный». Сохранение → `PUT /pricing`. Тумблер платности прямо в строке подтеста → `PATCH /paid`. Показывай подсказку: *«Одна оплата открывает все платные подтесты этого теста»*.

### 3.5 CRUD подтеста

```
POST /api/admin/tests/{testId}/sub-tests   → AdminSubTestResponse
PUT  /api/admin/sub-tests/{subTestId}      → AdminSubTestResponse
```
```json
{
  "title": "Вводный уровень",        // required
  "titleKy": "Киришүү деңгээли",
  "levelName": "1-уровень",          // required — отображаемое имя уровня
  "levelNameKy": "1-деңгээл",
  "levelOrder": 1,                    // required — порядок сортировки, меньше = выше
  "isPaid": false,
  "durationMinutes": 20               // required — время на прохождение
}
```
```
DELETE /api/admin/sub-tests/{subTestId}   → 204   // active=false
```

**UI:** подтесты — drag-sortable список (перетаскивание меняет `levelOrder` и шлёт `PUT` для затронутых). Показывай: название, уровень, кол-во вопросов, длительность, бейдж «Платный/Бесплатный», статус. Кнопки: «Вопросы», «Редактировать», «Скрыть».

⚠️ При создании подтеста бэк **автоматически шлёт push-уведомление** всем, у кого есть активный доступ к этому тесту («новый подтест»). Предупреди об этом в диалоге создания: *«Пользователям с доступом придёт уведомление»*.

### 3.6 🔴 Вопросы — ядро задачи

```
GET /api/admin/sub-tests/{subTestId}/questions   → AdminQuestionResponse[]
```
(не Page — обычный массив, включая неактивные)
```json
[
  {
    "id": 12,
    "sectionName": "1-часть: Математика",
    "sectionNameKy": "1-бөлүк: Математика",
    "text": "Найдите значение $\\frac{3}{4} + \\sqrt{16}$",
    "textKy": "…",
    "imageUrl": "https://...presigned...",
    "explanation": "Приводим к общему знаменателю…",
    "explanationKy": "…",
    "orderIndex": 0,
    "pointValue": 1,
    "active": true,
    "options": [
      { "id": 45, "label": "А", "text": "$4\\frac{3}{4}$", "textKy": "…", "isCorrect": true,  "orderIndex": 0 },
      { "id": 46, "label": "Б", "text": "$5$",             "textKy": "…", "isCorrect": false, "orderIndex": 1 }
    ]
  }
]
```

```
POST /api/admin/sub-tests/{subTestId}/questions   → AdminQuestionResponse
PUT  /api/admin/questions/{questionId}            → AdminQuestionResponse
DELETE /api/admin/questions/{questionId}          → 204   // active=false
```

Тело (одинаковое для POST и PUT):
```json
{
  "text": "Найдите значение $\\frac{3}{4} + \\sqrt{16}$",   // required NotBlank
  "textKy": "…",
  "sectionName": "1-часть: Математика",
  "sectionNameKy": "…",
  "imageUrl": "questions/ab12-cd.png",       // objectKey
  "explanation": "…",                         // используется ИИ-разбором ошибок
  "explanationKy": "…",
  "orderIndex": 0,                            // 0-based
  "pointValue": 1,                            // баллы за верный ответ
  "options": [                                // required, ОТ 2 ДО 6
    { "label": "А", "text": "$4\\frac{3}{4}$", "textKy": "…", "isCorrect": true,  "orderIndex": 0 },
    { "label": "Б", "text": "$5$",             "textKy": "…", "isCorrect": false, "orderIndex": 1 }
  ]
}
```

🔴 **`PUT` — ПОЛНАЯ ЗАМЕНА вопроса вместе со всеми вариантами.** Старые варианты удаляются, создаются новые (у них будут новые `id`). Всегда отправляй весь объект целиком, не патчи по полю.

**Типы вопросов** (отдельного поля нет, тип выводится из данных):
- ровно **1** вариант с `isCorrect: true` → одиночный выбор (radio у ученика)
- **2+** вариантов с `isCorrect: true` → множественный выбор (checkbox)
- Засчитывается **только точное совпадение** множества выбранного с множеством правильных. Частичных баллов нет.

В редакторе сделай **явный переключатель типа** сверху: «Один правильный ответ» / «Несколько правильных». Он управляет тем, radio или checkbox у вариантов, и валидацией. Это не поле бэка — просто UI поверх `isCorrect`.

#### Требования к редактору вопроса (делай тщательно)

**Layout:** двухколоночный. Слева — форма, справа — **живой предпросмотр вопроса ровно так, как его увидит ученик** (с отрендеренными формулами, картинкой, вариантами).

**Форма:**
1. **Раздел** (`sectionName`) — combobox с автодополнением из уже существующих разделов этого подтеста (собери уникальные из загруженного списка). Позволяет группировать вопросы.
2. **Текст вопроса** — `<MathField mode="text+math">`, многострочный, обязательный.
3. **Изображение** — `<ImageUploader type="QUESTION_IMAGE">`, опционально, с превью и удалением.
4. **Тип ответа** — сегмент-контрол: один / несколько правильных.
5. **Варианты ответов** — список от 2 до 6:
   - `label` — метка. **Автоподстановка А, Б, В, Г, Д, Е** (кириллица! именно так в примерах бэка). Поле редактируемое, но по умолчанию проставляется автоматически и переприсваивается при удалении/перестановке.
   - `text` — `<MathField mode="math">` (по умолчанию, т.к. чаще всего это число/выражение), с возможностью переключиться в текстовый режим.
   - radio/checkbox «правильный» — в зависимости от типа.
   - drag-хендл для перестановки (меняет `orderIndex` и переприсваивает метки).
   - кнопка удаления (недоступна, если вариантов осталось 2).
   - кнопка «Добавить вариант» (недоступна на 6).
6. **Баллы** (`pointValue`) — number, по умолчанию 1, минимум 1.
7. **Порядок** (`orderIndex`) — скрыть из формы, управлять перетаскиванием в списке вопросов.
8. **Пояснение** (`explanation`) — `<MathField mode="text+math">`, свёрнутый блок. Подпись: *«Используется ИИ при разборе ошибок ученика»*.
9. **Языковые вкладки** — см. 4.6.

**Валидация (Zod), блокирует сохранение:**
- `text` не пустой
- 2–6 вариантов, у каждого непустой `text`
- **минимум один** `isCorrect: true`
- при типе «один правильный» — **ровно один** `isCorrect`
- все `label` уникальны и непусты
- `pointValue >= 1`
- **все LaTeX-фрагменты компилируются** (см. 2.3 п.6)

**UX-обязательное:**
- **Автосохранение черновика в `localStorage`** по ключу `draft:question:{subTestId}:{questionId ?? 'new'}`. При открытии — предложить восстановить. Набивка вопроса с формулами долгая, потерять её нельзя.
- **«Сохранить и создать следующий»** — главная кнопка при массовой набивке. Сохраняет, чистит форму, сохраняет выбранный `sectionName` и `pointValue`, ставит фокус в текст вопроса, инкрементит `orderIndex`.
- **«Дублировать вопрос»** в списке — открывает форму с предзаполненными данными (без `id`).
- Предупреждение при уходе со страницы с несохранёнными изменениями (`beforeunload` + блокировка навигации роутера).
- Горячие клавиши: `Ctrl+S` — сохранить, `Ctrl+Enter` — сохранить и следующий.

**Список вопросов подтеста:**
- Карточки/строки с **отрендеренным** текстом (`<MathText>`), номером, разделом, баллами, кол-вом вариантов, зелёной галочкой на правильных, бейджем «скрыт» для `active: false`.
- Drag-sort → пересчёт `orderIndex` → отправка `PUT` только для изменившихся вопросов (batch по одному запросу на вопрос, с индикатором).
- Фильтр по разделу, поиск по тексту (клиентский — список целиком уже загружен).
- Кнопка **«Предпросмотр подтеста»** — модалка, прогоняющая все вопросы как у ученика (без сохранения ответов). Очень помогает вычитывать формулы.
- Счётчик сверху: «Вопросов: 12 · Суммарно баллов: 15 · Время: 20 мин».

---

## 4. Остальные разделы

### 4.1 Пользователи — `/api/admin/users`

```
GET /api/admin/users?page=0&size=20&search=&active=&role=&dateFrom=&dateTo=
→ Page<AdminUserResponse>
```
```json
{
  "id": 2, "fullName": "Айнура Бекова", "phone": "996700100001",
  "email": null, "avatarUrl": "https://...presigned...", "regionName": "Чуйская область",
  "role": "USER", "active": true, "phoneVerified": true,
  "registeredAt": "2026-07-30T12:06:47.234", "totalScore": 128
}
```
```
GET    /api/admin/users/{id}          → AdminUserResponse
PUT    /api/admin/users/{id}          → AdminUserResponse
       { "firstName", "lastName", "email", "phone", "active", "role" }   // role: "USER" | "ADMIN"
DELETE /api/admin/users/{id}          → 204   // деактивация
GET    /api/admin/users/export?...    → CSV-файл (те же фильтры)
```

⚠️ `PUT` принимает `firstName`/`lastName` **раздельно**, а `GET` возвращает склеенный `fullName`. При открытии формы редактирования разбей `fullName` по первому пробелу или (лучше) храни исходные значения из строки списка.

⚠️ **Смена роли на ADMIN даёт полный доступ к панели.** Требуй подтверждения в модалке с явным текстом.

**Экспорт CSV:** ответ — файл, не JSON. Скачивай через `responseType: 'blob'` + создание ссылки. Тот же приём для платежей.

### 4.2 Доступ к тестам — `/api/admin/access`

```
POST /api/admin/access
{
  "userId": 5, "testId": 2,
  "durationDays": 30,        // опционально
  "durationHours": 12,       // опционально, складывается с днями
  "expiresAt": null          // опционально; если задано — durationDays/Hours игнорируются
}
→ AccessGrantResponse
```
Ничего из срока не передано → **бессрочный** доступ.
```json
{
  "id": 7, "userId": 5, "userName": "Айнура Бекова", "userPhone": "996700100001",
  "testId": 2, "testTitle": "ОРТ — Математика",
  "grantedAt": "2026-08-27T16:00:00", "expiresAt": "2026-09-26T16:00:00",
  "status": "ACTIVE"     // PERMANENT | ACTIVE | EXPIRED
}
```
```
DELETE /api/admin/access?userId=5&testId=2        → 200
GET    /api/admin/access?userId=5                 → AccessGrantResponse[]
GET    /api/admin/access?testId=2                 → AccessGrantResponse[]
```
⚠️ `GET` без `userId` и без `testId` → 400. Всегда передавай ровно один.

**UI:** отдельная страница + виджет на карточке пользователя. Форма выдачи: юзер (поиск по телефону/имени), тест, пресеты срока (1 день / 7 / 30 / 90 / бессрочно / своя дата). Бейджи статуса цветом: PERMANENT — синий, ACTIVE — зелёный, EXPIRED — серый.

### 4.3 Платежи — `/api/admin/payments`

```
GET /api/admin/payments?page=0&size=20&search=&status=&dateFrom=&dateTo=
→ Page<AdminPaymentResponse>
```
```json
{
  "id": 14,
  "transactionId": "TXN-8891",
  "user": { "id": 5, "fullName": "Айнура Бекова", "phone": "996700100001", "avatarUrl": "https://..." },
  "amount": 500.00,
  "paymentMethod": "Finik",
  "status": "COMPLETED",              // PENDING | COMPLETED | EXPIRED | CANCELLED
  "date": "2026-08-20T10:17:41.220",
  "earnedPoints": 42,
  "testTitle": "ОРТ — Математика"
}
```
```
GET    /api/admin/payments/{id}                     → AdminPaymentResponse
PATCH  /api/admin/payments/{id}/status?status=COMPLETED  → AdminPaymentResponse
DELETE /api/admin/payments/{id}                     → 204
GET    /api/admin/payments/export?format=csv|excel&...  → файл
```

🔴 **Две опасные ловушки, обязательно отрази в UI:**
1. `DELETE` удаляет запись платежа **безвозвратно**, но **доступ к тесту НЕ отзывается**. В модалке подтверждения напиши это прямым текстом и предложи ссылку на управление доступом.
2. `PATCH /status` меняет только статус записи — **доступ к тесту при этом не выдаётся**. Чтобы реально открыть тест, нужен `POST /api/admin/access`. Покажи подсказку рядом с действием.

`PENDING`-платежей обычно много (брошенные попытки оплаты) — в фильтре по умолчанию ставь `status=COMPLETED`, с возможностью снять.

### 4.4 Отчёты — `/api/admin/reports`

Общий фильтр периода для всех трёх отчётов:
`?period=today|week|month|quarter|year|all` **или** `?from=2026-01-01&to=2026-08-27`.
Явные `from`/`to` приоритетнее `period`. Без параметров — последние 30 дней.
Сделай единый компонент `<PeriodPicker>` с пресетами + произвольным диапазоном, состояние — в URL query.

**Live-мониторинг:**
```
GET /api/admin/reports/active-sessions   → ActiveSessionEntry[]
```
```json
{
  "sessionId": 301, "userId": 5, "userName": "Айнура Бекова", "userPhone": "996700100001",
  "testId": 2, "testTitle": "ОРТ — Математика",
  "subTestId": 3, "subTestTitle": "Продвинутый уровень",
  "startedAt": "2026-08-27T15:58:00", "currentIndex": 4,
  "totalQuestions": 10, "remainingSeconds": 512
}
```
UI: «Сейчас проходят тест» — таблица с прогресс-баром `currentIndex / totalQuestions` и обратным отсчётом. **Автообновление `refetchInterval: 10000`** (websocket'а нет). Тикающий таймер считать на клиенте от `remainingSeconds`, не дёргая сервер.

**По тестам:**
```
GET /api/admin/reports/tests?period=month   → TestReportResponse
```
```json
{
  "from": "2026-07-27T00:00:00", "to": "2026-08-27T16:00:00",
  "totalAttempts": 143, "totalCompleted": 118,
  "rows": [
    {
      "testId": 2, "testTitle": "ОРТ — Математика",
      "subTestId": 3, "subTestTitle": "Продвинутый уровень",
      "attempts": 40, "completed": 31, "distinctUsers": 22,
      "avgPercent": 68,        // средний % завершённых; null если завершений нет
      "completionRate": 78     // completed / attempts * 100
    }
  ]
}
```
UI: таблица с группировкой по тесту, прогресс-бары для процентов, сортировка по колонкам (клиентская).

**По оплатам:**
```
GET /api/admin/reports/payments?period=year   → PaymentReportResponse
```
```json
{
  "from": "...", "to": "...",
  "totalRevenue": 48500.00,
  "completedCount": 97,
  "byStatus": [ { "status": "COMPLETED", "count": 97, "amount": 48500.00 } ],
  "byTest":   [ { "testId": 2, "testTitle": "ОРТ — Математика", "count": 60, "revenue": 30000.00 } ],
  "byMonth":  [ { "month": "2026-07", "count": 21, "revenue": 10500.00 } ]
}
```
UI: KPI-карточки + столбчатый график по месяцам + пирог по тестам + таблица статусов.
⚠️ `byStatus` считается по дате **создания** платежа, а `totalRevenue`/`byTest`/`byMonth` — по дате **оплаты**. Цифры могут не биться на границах периода — это ожидаемо, подпиши источники под графиками.

**Сводка:**
```
GET /api/admin/reports/overview?period=quarter   → OverviewReportResponse
```
```json
{
  "from": "...", "to": "...",
  "registrations": 340, "activeUsers": 210,
  "sessionsStarted": 890, "sessionsCompleted": 705,
  "revenue": 48500.00,
  "byMonth": [
    { "month": "2026-06", "registrations": 120, "sessionsStarted": 300, "sessionsCompleted": 240, "revenue": 15000.00 }
  ]
}
```
Это **главная страница дашборда**. Старый `GET /api/admin/dashboard` — легаси с жёстко зашитыми окнами (24ч/неделя), в графике активности «Онлайн-игра» всегда `0`. **Не используй его**, строй дашборд на `/reports/overview`.

### 4.5 Push-уведомления — `/api/admin/notifications`

```
POST /api/admin/notifications/broadcast     → 202 Accepted
{
  "title": "Новый тест доступен!",          // required
  "body":  "Попробуйте новый тест",          // required
  "titleKy": "…", "bodyKy": "…",             // опционально; если пусто — уйдёт RU
  "audience": "ALL",                          // ALL | USER_IDS | PLATFORM | PURCHASED_TEST | INACTIVE_DAYS
  "audienceRef": null,                        // см. таблицу
  "dataType": "TEST",                         // NONE | TEST | SUB_TEST | GAME | BROADCAST — deep-link
  "dataEntityId": 2,
  "scheduledAt": null                         // ISO без зоны; в будущем → рассылка планируется
}
→ { "broadcastId": 42, "status": "PENDING", "audience": "ALL", "scheduledAt": null, "createdAt": "..." }
```

| audience | что в `audienceRef` |
|---|---|
| `ALL` | не нужен |
| `USER_IDS` | `"12,45,78"` |
| `PLATFORM` | `"ANDROID"` \| `"IOS"` \| `"WEB"` |
| `PURCHASED_TEST` | id теста, `"2"` |
| `INACTIVE_DAYS` | число дней, `"7"` |

⚠️ Для всех аудиторий кроме `ALL` `audienceRef` **обязателен**, иначе 400. Форма должна менять поле ввода под выбранную аудиторию (мультиселект юзеров / селект платформы / селект теста / число дней).

⚠️ Одинаковые запросы **в пределах минуты дедуплицируются** — вернётся тот же `broadcastId`. Не считай это ошибкой, но и не давай спамить кнопкой (дизейбл на время запроса).

```
GET    /api/admin/notifications/broadcast/{id}   → BroadcastDetailResponse
DELETE /api/admin/notifications/broadcast/{id}   → 200   // только status === "SCHEDULED", иначе 400
GET    /api/admin/notifications/broadcast?page=&size=  → Page<BroadcastHistoryEntry>
GET    /api/admin/notifications/status           → PushStatusResponse
```
```json
// BroadcastDetailResponse
{
  "id": 42, "status": "SENT",     // PENDING | SCHEDULED | SENDING | SENT | FAILED | CANCELLED
  "title": "…", "body": "…", "titleKy": "…", "bodyKy": "…",
  "audience": "ALL", "audienceRef": null,
  "dataType": "TEST", "dataEntityId": 2,
  "sentByName": "Admin",
  "recipientCount": 1280, "successCount": 1247, "failureCount": 33,
  "scheduledAt": null, "startedAt": "...", "finishedAt": "...", "createdAt": "..."
}

// PushStatusResponse
{
  "firebaseEnabled": true, "totalTokens": 1421,
  "byPlatform": { "ANDROID": 1180, "IOS": 234, "WEB": 7 },
  "usersWithToken": 1290, "scheduledBroadcasts": 2,
  "lastBroadcastAt": "2026-08-27T16:05:11.482"
}
```

**UI:**
- Отправка асинхронная: после 202 открывай карточку рассылки и **опрашивай `refetchInterval: 2000`, пока `status` ∈ `PENDING|SENDING`**; потом останавливай.
- Счётчики `recipientCount/successCount/failureCount` — `null` до завершения, показывай скелетон.
- Баннер вверху раздела, если `firebaseEnabled === false`: «Push-уведомления не настроены на сервере» — тогда немедленная отправка вернёт 400 (запланировать всё равно можно).
- **Нет эндпоинта редактирования запланированной рассылки.** Дай кнопку «Изменить» = отменить + открыть форму создания с предзаполненными данными.
- Обязательный превью-блок «как это увидит пользователь» — макет пуш-уведомления на телефоне.

### 4.6 Двуязычный контент (RU / KY)

Почти у всего контента есть пара полей: `title`/`titleKy`, `text`/`textKy`, `description`/`descriptionKy`, `explanation`/`explanationKy`, `levelName`/`levelNameKy`, `sectionName`/`sectionNameKy`, `content`/`contentKy`.

**Правило бэка:** если `*Ky` пустой — пользователю с языком KY показывается русский вариант. То есть KY **не обязателен**, но желателен.

**UI-паттерн:** компонент `<BilingualField>` — две вкладки «RU | KY» над одним полем, с индикатором заполненности (точка/галочка на вкладке KY). Русский — обязателен, кыргызский — опционален с подсказкой «Если не заполнить, покажется русский текст». В формах вопросов вкладки должны переключать **все** поля сразу (текст, варианты, пояснение), а не каждое по отдельности.

### 4.7 Новости — `/api/admin/news`

```
GET /api/admin/news?page=&size=&search=&type=&active=&dateFrom=&dateTo=
→ Page<AdminNewsListResponse>
{ "id", "title", "coverImageUrl", "type", "viewCount", "authorName", "publishedAt", "active" }

POST   /api/admin/news          → создать
PUT    /api/admin/news/{id}     → обновить
PATCH  /api/admin/news/{id}/status  → переключить active
DELETE /api/admin/news/{id}     → скрыть
```
Тело create/update:
```json
{
  "title": "…",            // required
  "titleKy": "…",
  "coverImageUrl": "news/ab12.png",   // objectKey, тип загрузки NEWS_COVER
  "content": "…",          // required
  "contentKy": "…",
  "type": "NEWS",          // NEWS | ARTICLE | ANNOUNCEMENT
  "publishedAt": "2026-08-27T10:00:00"   // required
}
```
⚠️ Лента `/api/feed/**` **публичная** (без авторизации) — всё, что публикуешь, видно всем. Отметь это в UI.

### 4.8 Видеоуроки — `/api/admin/videos`

```
GET /api/admin/videos?page=&size=&search=&testId=&active=
→ Page<AdminVideoListResponse>
{ "id", "title", "thumbnailUrl", "subject", "duration", "viewCount", "createdAt", "active", "testId" }

POST / PUT / PATCH {id}/status / DELETE {id}
```
```json
{
  "title": "…",           // required
  "titleKy": "…", "description": "…", "descriptionKy": "…",
  "thumbnailUrl": "thumbnails/xy.png",    // objectKey, тип VIDEO_THUMBNAIL
  "videoUrl": "https://youtube.com/watch?v=…",   // required
  "testId": 2,            // опциональная привязка к тесту
  "orderIndex": 0,
  "durationSeconds": 480
}
```
Сделай парсер YouTube-ссылки: извлекай videoId, показывай превью-плеер и предлагай подставить обложку с `img.youtube.com/vi/{id}/hqdefault.jpg` (бэк пропускает внешние URL как есть).

### 4.9 Игровые тесты (дуэли 1-на-1) — `/api/admin/game-tests`

Отдельная от обычных тестов сущность: быстрые вопросы для PvP-режима, только RU, только одиночный выбор.

```
GET  /api/admin/game-tests            → GameTestResponse[]   (без вопросов)
GET  /api/admin/game-tests/{id}       → GameTestResponse     (с вопросами и флагами correct)
POST /api/admin/game-tests            → GameTestResponse
PUT  /api/admin/game-tests/{id}       → GameTestResponse
DELETE /api/admin/game-tests/{id}     → деактивация
POST /api/admin/game-tests/{id}/questions        → добавить вопрос
DELETE /api/admin/game-tests/questions/{questionId}  → деактивировать вопрос
GET  /api/admin/game-tests/{id}/report           → AdminGameReportResponse
```
```json
// create/update game test
{
  "title": "Математика: Арифметика",   // required
  "description": "…",
  "timeLimitSeconds": 30,               // min 5 — время на ОДИН вопрос
  "questionsPerGame": 10,               // 0 = все вопросы в случайном порядке
  "questions": [ /* опционально, сразу пачкой */ ]
}

// вопрос игрового теста
{
  "text": "Чему равно среднее арифметическое 10, 20, 30?",   // required
  "imageUrl": "…",
  "orderIndex": 0,
  "options": [                       // 2–6, РОВНО ОДИН correct: true
    { "text": "20", "correct": true },
    { "text": "17", "correct": false }
  ]
}
```
🔴 **Отличия от обычных вопросов — не перепутай:**
- поле называется **`correct`**, а не `isCorrect`
- **нет** `label`, `textKy`, `pointValue`, `explanation`
- **ровно один** правильный (не «минимум один»)
- `timeLimitSeconds` — на **один вопрос**, а не на весь тест

Отчёт:
```json
{
  "gameTestId": 1, "gameTestTitle": "…", "totalGames": 42, "totalPlayers": 18,
  "games": [
    { "roomId": 7, "playedAt": "...", "player1Name": "Айнур", "player2Name": "Бекзат",
      "player1Score": 7, "player2Score": 5, "winnerName": "Айнур", "totalQuestions": 10 }
  ]
}
```

### 4.10 Рейтинг — `/api/admin/rating`

```
GET /api/admin/rating?page=&size=&testId=&dateFrom=&dateTo=
→ Page<AdminRatingEntryResponse>
{ "userId", "fullName", "phone", "avatarUrl", "rank", "totalPoints", "pvpWins" }

POST /api/admin/rating/reset   → { "success": true, "message": "…" }
```
🔴 `reset` **обнуляет `earnedPoints` во всех завершённых сессиях — необратимо**. Требуй ввода слова «СБРОСИТЬ» в модалке подтверждения. Кнопку сделай destructive и спрячь под «Опасная зона».

---

## 5. Сквозные требования

**Навигация (sidebar):**
```
Дашборд
Контент
  ├── Тесты            ← главный раздел
  ├── Игровые тесты
  ├── Новости
  └── Видеоуроки
Пользователи
  ├── Список
  └── Доступы к тестам
Финансы
  └── Платежи
Отчёты
  ├── Сводка
  ├── По тестам
  ├── По оплатам
  └── Сейчас проходят      ← бейдж с числом активных сессий
Коммуникации
  └── Push-рассылки
Рейтинг
```

**Обязательные UX-правила:**
1. **Оптимистичные апдейты** для тумблеров (`active`, `isPaid`) с откатом при ошибке.
2. **Инвалидация кэша** после мутаций: сохранил вопрос → инвалидировать список вопросов подтеста + карточку теста (изменился `questionCount`).
3. **Скелетоны**, а не спиннеры, для таблиц и карточек.
4. **Пустые состояния** с призывом к действию («Пока нет вопросов. Добавить первый»).
5. **Подтверждение** для всех деструктивных действий; для необратимых (сброс рейтинга, удаление платежа) — ввод слова-подтверждения.
6. **Состояние фильтров и пагинации — в URL** (`useSearchParams`), чтобы ссылки можно было слать и перезагрузка не сбрасывала.
7. **Тосты** на успех/ошибку каждой мутации, с внятным текстом на русском.
8. `document.title` по разделам.
9. Адаптив: панель рассчитана на десктоп, но не должна ломаться на планшете.
10. Тёмная тема — опционально, но токенами Tailwind сделай сразу, чтобы потом не переписывать.

**Производительность:**
- `staleTime` для справочников (`/tests/subjects`) — 10 минут; для списков с картинками — не больше 30 минут (presigned URL живут час).
- Списки вопросов и подтестов приходят целиком — фильтруй и сортируй на клиенте, не дёргай сервер.
- Виртуализация не нужна: объёмы небольшие.

**Безопасность:**
- Не логируй токены.
- Экранируй пользовательский контент; KaTeX запускай с `trust: false`.
- В `.env` только `VITE_API_URL`, никаких секретов.

---

## 6. Известные ограничения бэка (не баги — отрази в README админки)

1. **Нельзя вернуть скрытый тест/подтест/вопрос/новость/видео** — в `Create*Request` нет поля `active`, а `DELETE` только выключает.
2. **Мобильный клиент пока не рендерит LaTeX** — см. 2.2.
3. **Нет редактирования запланированной рассылки** — только отмена + создание заново.
4. **Нет журнала действий администратора** — кто что менял, не отследить.
5. **Только две роли** (`USER`, `ADMIN`), гранулярных прав нет.
6. **Удаление платежа не отзывает доступ**, смена статуса платежа не выдаёт доступ.
7. **Нет массового импорта вопросов** (CSV/Excel) — только по одному через форму. Поэтому «Сохранить и создать следующий» и черновики так важны.
8. **Нет reorder-эндпоинта** — порядок меняется отправкой полного `PUT` на каждый затронутый вопрос/подтест.

---

## 7. Порядок работы

1. Каркас: Vite + TS + Tailwind + shadcn, axios с интерсепторами, роутинг, layout с sidebar, страница логина, `ProtectedRoute` с проверкой `role === "ADMIN"`.
2. Общие компоненты: `DataTable`, `PageHeader`, `ConfirmDialog`, `EmptyState`, `ImageUploader`, `BilingualField`, `PeriodPicker`.
3. 🔴 **Модуль math**: `MathField`, `MathText`, `MathToolbar`, шаблоны, валидация LaTeX. **Сделай и вылижи его до того, как браться за формы вопросов.**
4. 🔴 Тесты → карточка теста → подтесты → **редактор вопросов** (со всеми требованиями раздела 3.6).
5. Дашборд на `/reports/overview` + Live-мониторинг.
6. Пользователи + Доступы.
7. Платежи + отчёт по оплатам + отчёт по тестам.
8. Push-рассылки.
9. Новости, Видео, Игровые тесты, Рейтинг.

**После каждого пункта** — прогоняй сборку и типы (`tsc --noEmit`), не копи ошибки.
