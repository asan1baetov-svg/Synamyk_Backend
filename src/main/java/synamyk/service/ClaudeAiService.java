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

    private final AnthropicConfig config;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    /**
     * Generates an explanation for a wrong answer using Claude API.
     *
     * @param questionText   the question text
     * @param options        all answer options
     * @param userWrong      text of the user's wrong answer
     * @param correctAnswer  text of the correct answer
     * @return AI-generated explanation
     */
    public String explainWrongAnswer(
            String questionText,
            List<String> options,
            String userWrong,
            String correctAnswer,
            String lang
    ) {
        String prompt = buildPrompt(questionText, options, userWrong, correctAnswer, lang);

        try {
            Map<String, Object> requestBody = Map.of(
                    "model", MODEL,
                    "max_tokens", 1024,
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
                if (content != null && !content.isEmpty()) {
                    return (String) content.get(0).get("text");
                }
            }

            return "Не удалось получить объяснение.";

        } catch (Exception e) {
            log.error("Error calling Claude API: {}", e.getMessage());
            return "Не удалось получить объяснение: " + e.getMessage();
        }
    }

    private String buildPrompt(String questionText, List<String> options, String userWrong, String correctAnswer, String lang) {
        boolean ky = "KY".equalsIgnoreCase(lang);
        StringBuilder sb = new StringBuilder();

        if (ky) {
            sb.append("Сен экзаменге даярдануу үчүн жардамчысың. Колдонуучуга \"сен\" деп кайрыл.\n\n");
            sb.append("Суроо: ").append(questionText).append("\n\n");
            sb.append("Жооп варианттары:\n");
            for (String opt : options) sb.append("- ").append(opt).append("\n");
            sb.append("\nСенин жообуң (туура эмес): ").append(userWrong).append("\n");
            sb.append("Туура жооп: ").append(correctAnswer).append("\n\n");
            sb.append("Кыргыз тилинде, \"сен\" деп кайрылып, 2-4 сүйлөм менен түшүндүр: ");
            sb.append("туура жооп эмне үчүн туура, сен эмне жерде жаңылдың.");
        } else {
            sb.append("Ты помощник для подготовки к экзаменам. Обращайся к пользователю на \"ты\".\n\n");
            sb.append("Вопрос: ").append(questionText).append("\n\n");
            sb.append("Варианты ответов:\n");
            for (String opt : options) sb.append("- ").append(opt).append("\n");
            sb.append("\nТвой ответ (неправильный): ").append(userWrong).append("\n");
            sb.append("Правильный ответ: ").append(correctAnswer).append("\n\n");
            sb.append("Объясни на русском языке, обращаясь на \"ты\", 2-4 предложения: ");
            sb.append("почему правильный ответ верный и где именно ты ошибся.");
        }

        return sb.toString();
    }
}