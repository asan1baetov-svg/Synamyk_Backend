package synamyk.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import synamyk.config.AnthropicConfig;

import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class ClaudeAiService {

    private static final String MODEL = "claude-sonnet-5";
    private static final String API_VERSION = "2023-06-01";

    private static final String SYSTEM_RU = """
            Ты — репетитор, который разбирает ошибку ученика в тесте.
            Данные о вопросе и ответах верны — не подвергай их сомнению и не проверяй их правильность.
            Пиши только сам разбор: ничего не пиши от своего лица, не извиняйся,
            не упоминай «разбор ответов», «данные» или «я ошибся». Обращайся к ученику на «ты».
            Ответ: 2–4 предложения простым языком — почему верный вариант верный
            и в чём была ошибка в выбранном варианте. Без вступлений и заключений.""";

    private static final String SYSTEM_KY = """
            Сен — окуучунун тесттеги катасын түшүндүргөн мугалимсиң.
            Суроо жана жооптор жөнүндө маалымат туура — аны шектенбе, текшербе.
            Өзүң жөнүндө эч нерсе жазба, кечирим сураба, «жоопторду талдоо» же «мен жаңылдым» деп жазба.
            Окуучуга «сен» деп кайрыл. Жооп: 2–4 сүйлөм жөнөкөй тил менен —
            туура вариант эмне үчүн туура жана тандалган вариантта эмне ката болгон. Кириш сөзсүз.""";

    private final AnthropicConfig config;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    /**
     * Generates an explanation for a wrong answer using Claude API.
     * Returns a neutral localized fallback when the API is unavailable — never an error string.
     */
    public String explainWrongAnswer(
            String questionText,
            List<String> options,
            String userWrong,
            String correctAnswer,
            String lang
    ) {
        boolean ky = "KY".equalsIgnoreCase(lang);
        String prompt = buildPrompt(questionText, options, userWrong, correctAnswer, ky);

        try {
            Map<String, Object> requestBody = Map.of(
                    "model", MODEL,
                    "max_tokens", 1024,
                    "temperature", 0.3,
                    "system", ky ? SYSTEM_KY : SYSTEM_RU,
                    "messages", List.of(
                            Map.of("role", "user", "content", prompt)
                    )
            );

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("x-api-key", config.getApiKey());
            headers.set("anthropic-version", API_VERSION);

            HttpEntity<String> entity = new HttpEntity<>(objectMapper.writeValueAsString(requestBody), headers);

            ResponseEntity<Map> response = restTemplate.exchange(
                    config.getBaseUrl() + "/v1/messages",
                    HttpMethod.POST,
                    entity,
                    Map.class
            );

            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                List<Map<String, Object>> content = (List<Map<String, Object>>) response.getBody().get("content");
                if (content != null) {
                    String text = content.stream()
                            .filter(b -> "text".equals(b.get("type")))
                            .map(b -> (String) b.get("text"))
                            .filter(t -> t != null && !t.isBlank())
                            .findFirst()
                            .orElse(null);
                    if (text != null) return text.trim();
                }
            }

            log.warn("Claude API returned no usable content: status={}", response.getStatusCode());
            return fallback(ky);

        } catch (Exception e) {
            log.error("Error calling Claude API: {}", e.getMessage());
            return fallback(ky);
        }
    }

    private String fallback(boolean ky) {
        return ky
                ? "Түшүндүрмө учурда жеткиликсиз. Кийинчерээк кайра аракет кыл."
                : "Объяснение сейчас недоступно. Попробуй позже.";
    }

    private String buildPrompt(String questionText, List<String> options, String userWrong, String correctAnswer, boolean ky) {
        boolean noChoice = userWrong == null || userWrong.isBlank() || "—".equals(userWrong.trim());
        StringBuilder sb = new StringBuilder();

        if (ky) {
            sb.append("Суроо: ").append(questionText).append("\n\n");
            sb.append("Жооп варианттары:\n");
            for (String opt : options) sb.append("- ").append(opt).append("\n");
            sb.append("\nОкуучу тандаган (туура эмес): ")
              .append(noChoice ? "эч бир вариант тандалган жок" : userWrong).append("\n");
            sb.append("Туура жооп: ").append(correctAnswer.isBlank() ? "белгиленген жок" : correctAnswer).append("\n");
        } else {
            sb.append("Вопрос: ").append(questionText).append("\n\n");
            sb.append("Варианты ответов:\n");
            for (String opt : options) sb.append("- ").append(opt).append("\n");
            sb.append("\nВыбор ученика (неверный): ")
              .append(noChoice ? "вариант не выбран" : userWrong).append("\n");
            sb.append("Правильный ответ: ").append(correctAnswer.isBlank() ? "не указан" : correctAnswer).append("\n");
        }

        return sb.toString();
    }
}
