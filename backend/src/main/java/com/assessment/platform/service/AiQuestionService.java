package com.assessment.platform.service;

import com.assessment.platform.dto.request.AiQuestionRequest;
import com.assessment.platform.dto.response.AiQuestionResponse;
import com.assessment.platform.exception.BadRequestException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class AiQuestionService {

    private static final int MAX_QUESTIONS = 15;

    private final ObjectMapper objectMapper;

    @Value("${app.ai.gemini.base-url:https://generativelanguage.googleapis.com/v1beta}")
    private String geminiBaseUrl;

    @Value("${app.ai.gemini.api-key:}")
    private String geminiApiKey;

    @Value("${app.ai.gemini.model:gemini-3.7-flash}")
    private String geminiModel;

    public AiQuestionResponse generateQuestions(AiQuestionRequest request) {
        if (geminiApiKey == null || geminiApiKey.isBlank()) {
            throw new BadRequestException(
                    "Gemini API key is not configured."
            );
        }

        int count = Math.min(request.getQuestionCount(), MAX_QUESTIONS);
        String prompt = buildPrompt(request, count);

        String baseUrl = geminiBaseUrl.endsWith("/")
                ? geminiBaseUrl.substring(0, geminiBaseUrl.length() - 1)
                : geminiBaseUrl;

        String apiUrl = baseUrl
                + "/models/"
                + geminiModel
                + ":generateContent";

        Map<String, Object> questionSchema = Map.of(
                "type", "object",
                "properties", Map.of(
                        "question", Map.of(
                                "type", "string"
                        ),
                        "options", Map.of(
                                "type", "array",
                                "items", Map.of(
                                        "type", "string"
                                )
                        ),
                        "correctIndex", Map.of(
                                "type", "integer"
                        )
                ),
                "required", List.of(
                        "question",
                        "options",
                        "correctIndex"
                )
        );

        Map<String, Object> responseSchema = Map.of(
                "type", "object",
                "properties", Map.of(
                        "questions", Map.of(
                                "type", "array",
                                "items", questionSchema
                        )
                ),
                "required", List.of("questions")
        );

        Map<String, Object> requestBody = Map.of(
                "contents", List.of(
                        Map.of(
                                "role", "user",
                                "parts", List.of(
                                        Map.of(
                                                "text", prompt
                                        )
                                )
                        )
                ),
                "generationConfig", Map.of(
                        "responseFormat", Map.of(
                                "text", Map.of(
                                        "mimeType", "application/json",
                                        "schema", responseSchema
                                )
                        )
                )
        );

        RestTemplate restTemplate = new RestTemplate();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("x-goog-api-key", geminiApiKey);

        HttpEntity<Map<String, Object>> entity =
                new HttpEntity<>(requestBody, headers);

        log.info(
                "Calling Gemini model {} for {} assessment question(s)",
                geminiModel,
                count
        );

        ResponseEntity<String> response;

        try {
            response = restTemplate.postForEntity(
                    apiUrl,
                    entity,
                    String.class
            );
        } catch (RestClientResponseException ex) {
            String errorBody = ex.getResponseBodyAsString();

            log.error(
                    "Gemini API returned HTTP {}. Response: {}",
                    ex.getStatusCode().value(),
                    errorBody
            );

            throw new BadRequestException(
                    "Gemini AI request failed with HTTP "
                            + ex.getStatusCode().value()
            );
        } catch (RestClientException ex) {
            log.error(
                    "Failed to connect to Gemini API",
                    ex
            );

            throw new BadRequestException(
                    "AI service unavailable. Unable to connect to Gemini."
            );
        }

        if (response.getBody() == null
                || response.getBody().isBlank()) {
            throw new BadRequestException(
                    "Empty response received from Gemini"
            );
        }

        String text = extractGeminiText(response.getBody());

        if (text == null || text.isBlank()) {
            throw new BadRequestException(
                    "Gemini returned no generated question content"
            );
        }

        log.debug("Gemini AI response: {}", text);

        AiQuestionResponse parsed = parseQuestions(text);

        validateQuestions(parsed);

        if (parsed.getQuestions().size() > MAX_QUESTIONS) {
            parsed.setQuestions(
                    parsed.getQuestions().subList(
                            0,
                            MAX_QUESTIONS
                    )
            );
        }

        log.info(
                "Generated {} question(s) successfully using Gemini",
                parsed.getQuestions().size()
        );

        return parsed;
    }

    private String extractGeminiText(String responseBody) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);

            JsonNode candidates = root.path("candidates");

            if (!candidates.isArray()
                    || candidates.isEmpty()) {

                JsonNode promptFeedback =
                        root.path("promptFeedback");

                if (!promptFeedback.isMissingNode()
                        && !promptFeedback.isNull()) {
                    log.warn(
                            "Gemini returned no candidates. Prompt feedback: {}",
                            promptFeedback
                    );
                }

                throw new BadRequestException(
                        "Gemini returned no response candidates"
                );
            }

            JsonNode parts = candidates
                    .get(0)
                    .path("content")
                    .path("parts");

            if (!parts.isArray() || parts.isEmpty()) {
                throw new BadRequestException(
                        "Gemini response contained no text parts"
                );
            }

            StringBuilder result = new StringBuilder();

            for (JsonNode part : parts) {
                JsonNode textNode = part.get("text");

                if (textNode != null
                        && !textNode.isNull()) {
                    result.append(textNode.asText());
                }
            }

            return result.toString().trim();

        } catch (BadRequestException ex) {
            throw ex;
        } catch (Exception ex) {
            log.error(
                    "Failed to parse Gemini response",
                    ex
            );

            throw new BadRequestException(
                    "Invalid response received from Gemini"
            );
        }
    }

    private String buildPrompt(
            AiQuestionRequest request,
            int count
    ) {
        return "Generate exactly "
                + count
                + " multiple-choice technical assessment questions for a "
                + request.getRole()
                + " candidate. "
                + "Tech stack: "
                + request.getTechStack()
                + ". "
                + "Level/Progress: "
                + request.getProgress()
                + ". "
                + "Each question must have exactly 4 answer options. "
                + "correctIndex must be an integer from 0 to 3 identifying "
                + "the correct option. "
                + "Questions must be technically accurate, non-duplicate, "
                + "clear, and appropriate for the requested level. "
                + "Return only the requested structured JSON data.";
    }

    private AiQuestionResponse parseQuestions(String text) {
        try {
            String json = extractJson(text);

            log.debug(
                    "Extracted question JSON: {}",
                    json
            );

            return objectMapper.readValue(
                    json,
                    AiQuestionResponse.class
            );

        } catch (Exception ex) {
            log.warn(
                    "Direct JSON parse failed. Attempting recovery.",
                    ex
            );

            AiQuestionResponse recovered =
                    recoverQuestionsFromText(text);

            if (recovered != null
                    && recovered.getQuestions() != null
                    && !recovered.getQuestions().isEmpty()) {

                log.info(
                        "Recovered {} question(s) from Gemini response",
                        recovered.getQuestions().size()
                );

                return recovered;
            }

            log.error(
                    "Failed to parse Gemini response as question JSON",
                    ex
            );

            throw new BadRequestException(
                    "Failed to parse AI response as JSON"
            );
        }
    }

    private AiQuestionResponse recoverQuestionsFromText(
            String text
    ) {
        if (text == null || text.isBlank()) {
            return null;
        }

        String candidate =
                decodeHtmlEntities(extractJson(text));

        int questionsIndex =
                candidate.indexOf("\"questions\"");

        if (questionsIndex < 0) {
            return null;
        }

        int arrayStart =
                candidate.indexOf('[', questionsIndex);

        if (arrayStart < 0) {
            return null;
        }

        List<AiQuestionResponse.AiQuestionItem> questions =
                new ArrayList<>();

        boolean inString = false;
        boolean escape = false;

        int depth = 0;
        int objectStart = -1;

        for (int i = arrayStart + 1;
             i < candidate.length();
             i++) {

            char c = candidate.charAt(i);

            if (c == '\\' && !escape) {
                escape = true;
                continue;
            }

            if (c == '"' && !escape) {
                inString = !inString;
            }

            if (escape) {
                escape = false;
            }

            if (inString) {
                continue;
            }

            if (c == '{') {

                if (depth == 0) {
                    objectStart = i;
                }

                depth++;

            } else if (c == '}') {

                depth--;

                if (depth == 0
                        && objectStart >= 0) {

                    String objectJson =
                            candidate.substring(
                                    objectStart,
                                    i + 1
                            );

                    try {
                        AiQuestionResponse.AiQuestionItem item =
                                objectMapper.readValue(
                                        objectJson,
                                        AiQuestionResponse
                                                .AiQuestionItem.class
                                );

                        if (item != null
                                && item.getQuestion() != null
                                && item.getOptions() != null
                                && item.getCorrectIndex() != null) {

                            questions.add(
                                    sanitizeQuestionItem(item)
                            );
                        }

                    } catch (Exception ignored) {
                        // Continue looking for another valid object.
                    }

                    objectStart = -1;
                }
            }
        }

        if (!questions.isEmpty()) {
            return AiQuestionResponse.builder()
                    .questions(questions)
                    .build();
        }

        return null;
    }

    private AiQuestionResponse.AiQuestionItem
    sanitizeQuestionItem(
            AiQuestionResponse.AiQuestionItem item
    ) {
        if (item == null) {
            return null;
        }

        item.setQuestion(
                normalizeText(item.getQuestion())
        );

        if (item.getOptions() != null) {

            List<String> normalizedOptions =
                    new ArrayList<>();

            for (String option : item.getOptions()) {
                normalizedOptions.add(
                        normalizeText(option)
                );
            }

            item.setOptions(normalizedOptions);
        }

        return item;
    }

    private String normalizeText(String value) {
        if (value == null) {
            return null;
        }

        return value
                .replaceAll(
                        "\\\\u003cbr\\\\u003e",
                        " "
                )
                .replaceAll(
                        "(?i)<br\\s*/?>",
                        " "
                )
                .replaceAll(
                        "\\s+",
                        " "
                )
                .trim();
    }

    private String decodeHtmlEntities(String text) {
        if (text == null) {
            return null;
        }

        return text
                .replace("\\u003c", "<")
                .replace("\\u003e", ">")
                .replace("\\u0026", "&")
                .replace("\\u0022", "\"")
                .replaceAll(
                        "(?i)<br\\s*/?>",
                        " "
                )
                .trim();
    }

    private void validateQuestions(
            AiQuestionResponse response
    ) {
        if (response == null
                || response.getQuestions() == null
                || response.getQuestions().isEmpty()) {

            throw new BadRequestException(
                    "No questions generated"
            );
        }

        for (AiQuestionResponse.AiQuestionItem item
                : response.getQuestions()) {

            if (item.getQuestion() == null
                    || item.getQuestion().isBlank()) {

                throw new BadRequestException(
                        "Question text is empty"
                );
            }

            if (item.getOptions() == null
                    || item.getOptions().size() != 4) {

                throw new BadRequestException(
                        "Each generated question must have exactly 4 options"
                );
            }

            if (item.getCorrectIndex() == null
                    || item.getCorrectIndex() < 0
                    || item.getCorrectIndex()
                    >= item.getOptions().size()) {

                throw new BadRequestException(
                        "Invalid correctIndex for question"
                );
            }
        }
    }

    private String extractJson(String text) {
        if (text == null) {
            return "{}";
        }

        String trimmed = text.trim();

        if (trimmed.startsWith("```json")) {
            trimmed = trimmed.substring(7);

        } else if (trimmed.startsWith("```")) {
            trimmed = trimmed.substring(3);
        }

        if (trimmed.endsWith("```")) {
            trimmed = trimmed.substring(
                    0,
                    trimmed.length() - 3
            );
        }

        trimmed = trimmed.trim();

        if (isValidJson(trimmed)) {
            return trimmed;
        }

        List<String> candidates =
                extractJsonCandidates(trimmed);

        if (!candidates.isEmpty()) {

            String best =
                    selectBestJsonCandidate(candidates);

            if (best != null) {
                return best;
            }
        }

        return trimmed;
    }

    private String selectBestJsonCandidate(
            List<String> candidates
    ) {
        String bestCandidate = null;
        int bestScore = -1;

        for (String candidate : candidates) {

            if (!isValidJson(candidate)) {
                continue;
            }

            int score =
                    scoreJsonCandidate(candidate);

            if (score > bestScore) {
                bestScore = score;
                bestCandidate = candidate;
            }
        }

        return bestCandidate;
    }

    private int scoreJsonCandidate(
            String candidate
    ) {
        try {
            JsonNode node =
                    objectMapper.readTree(candidate);

            JsonNode questionsNode =
                    node.path("questions");

            int questionCount =
                    questionsNode.isArray()
                            ? questionsNode.size()
                            : 0;

            int score =
                    questionCount * 10
                            + Math.min(
                                    candidate.length(),
                                    100
                            );

            if (questionCount > 0) {

                for (JsonNode questionNode
                        : questionsNode) {

                    String question =
                            questionNode
                                    .path("question")
                                    .asText("");

                    if (!question.isBlank()
                            && !question.equalsIgnoreCase(
                            "string"
                    )) {
                        score += 5;
                    }
                }
            }

            return score;

        } catch (Exception ex) {
            return 0;
        }
    }

    private boolean isValidJson(
            String candidate
    ) {
        try {
            objectMapper.readTree(candidate);
            return true;

        } catch (Exception ex) {
            return false;
        }
    }

    private List<String> extractJsonCandidates(
            String text
    ) {
        List<String> candidates =
                new ArrayList<>();

        boolean inString = false;
        boolean escape = false;

        int depth = 0;
        int start = -1;

        for (int i = 0;
             i < text.length();
             i++) {

            char c = text.charAt(i);

            if (c == '\\' && !escape) {
                escape = true;
                continue;
            }

            if (c == '"' && !escape) {
                inString = !inString;
            }

            escape = false;

            if (inString) {
                continue;
            }

            if (c == '{') {

                if (depth == 0) {
                    start = i;
                }

                depth++;

            } else if (c == '}') {

                depth--;

                if (depth == 0
                        && start >= 0) {

                    candidates.add(
                            text.substring(
                                    start,
                                    i + 1
                            )
                    );

                    start = -1;
                }
            }
        }

        return candidates;
    }
}
