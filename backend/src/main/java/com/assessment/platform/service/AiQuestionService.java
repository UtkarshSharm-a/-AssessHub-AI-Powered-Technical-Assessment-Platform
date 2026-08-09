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

    @Value("${app.ai.ollama.base-url:http://localhost:11434}")
    private String ollamaBaseUrl;

    @Value("${app.ai.ollama.model:phi3}")
    private String ollamaModel;

    public AiQuestionResponse generateQuestions(AiQuestionRequest request) {
        int count = Math.min(request.getQuestionCount(), MAX_QUESTIONS);
        String prompt = buildPrompt(request, count);

        String apiUrl = ollamaBaseUrl + "/api/generate";

        // Create request body for Ollama
        Map<String, Object> body = Map.of(
            "model", ollamaModel,
            "prompt", prompt,
            "stream", false,
            "temperature", 0.3,
            "num_predict", 4096
        );

        RestTemplate restTemplate = new RestTemplate();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);

        log.info("Calling Ollama {} API at {}", ollamaModel, apiUrl);
        ResponseEntity<Map> response;
        try {
            response = restTemplate.postForEntity(apiUrl, entity, Map.class);
        } catch (org.springframework.web.client.RestClientException ex) {
            log.error("Failed to call Ollama API at {}. Is Ollama running with {} model?", apiUrl, ollamaModel, ex);
            throw new BadRequestException("AI service unavailable. Ensure Ollama is running at: " + ollamaBaseUrl);
        }

        if (response.getBody() == null || !response.getBody().containsKey("response")) {
            throw new BadRequestException("Invalid response from Ollama: missing 'response' field");
        }

        String text = (String) response.getBody().get("response");
        if (text == null || text.isBlank()) {
            throw new BadRequestException("Empty response from Ollama");
        }

        log.debug("AI Response: {}", text);

        AiQuestionResponse parsed = parseQuestions(text);
        validateQuestions(parsed);

        if (parsed.getQuestions().size() > MAX_QUESTIONS) {
            parsed.setQuestions(
                parsed.getQuestions().subList(0, MAX_QUESTIONS)
            );
        }

        log.info("Generated {} questions successfully", parsed.getQuestions().size());
        return parsed;
    }

    private String buildPrompt(AiQuestionRequest request, int count) {
        return "Generate " + count + " multiple-choice questions for a " + request.getRole() + " candidate. "
                + "Tech stack: " + request.getTechStack() + ". "
                + "Level/Progress: " + request.getProgress() + ". "
                + "Return ONLY valid JSON with this exact structure and nothing else: "
                + "{\"questions\":[{\"question\":\"string\",\"options\":[\"A\",\"B\",\"C\",\"D\"],\"correctIndex\":0}]} "
                + "Do not include markdown, code fences, explanations, or any extra text. Only the JSON object.";
    }

    private AiQuestionResponse parseQuestions(String text) {
        try {
            String json = extractJson(text);
            log.debug("Extracted JSON: {}", json);
            return objectMapper.readValue(json, AiQuestionResponse.class);
        } catch (Exception ex) {
            log.warn("Direct JSON parse failed. Attempting recovery from AI response.", ex);
            AiQuestionResponse recovered = recoverQuestionsFromText(text);
            if (recovered != null && recovered.getQuestions() != null && !recovered.getQuestions().isEmpty()) {
                log.info("Recovered {} question(s) from AI response after best-effort parsing", recovered.getQuestions().size());
                return recovered;
            }
            log.error("Failed to parse JSON after recovery attempts. Raw response: {}", text, ex);
            throw new BadRequestException("Failed to parse AI response as JSON: " + ex.getMessage());
        }
    }

    private AiQuestionResponse recoverQuestionsFromText(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }

        String candidate = decodeHtmlEntities(extractJson(text));
        int questionsIndex = candidate.indexOf("\"questions\"");
        if (questionsIndex < 0) {
            return null;
        }

        int arrayStart = candidate.indexOf('[', questionsIndex);
        if (arrayStart < 0) {
            return null;
        }

        List<AiQuestionResponse.AiQuestionItem> questions = new ArrayList<>();
        boolean inString = false;
        boolean escape = false;
        int depth = 0;
        int objectStart = -1;

        for (int i = arrayStart + 1; i < candidate.length(); i++) {
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
                if (depth == 0 && objectStart >= 0) {
                    String objectJson = candidate.substring(objectStart, i + 1);
                    try {
                        AiQuestionResponse.AiQuestionItem item = objectMapper.readValue(objectJson, AiQuestionResponse.AiQuestionItem.class);
                        if (item != null && item.getQuestion() != null && item.getOptions() != null && item.getCorrectIndex() != null) {
                            questions.add(sanitizeQuestionItem(item));
                        }
                    } catch (Exception ignored) {
                        // ignore invalid candidate objects and continue recovery
                    }
                    objectStart = -1;
                }
            }
        }

        if (!questions.isEmpty()) {
            return AiQuestionResponse.builder().questions(questions).build();
        }

        return null;
    }

    private AiQuestionResponse.AiQuestionItem sanitizeQuestionItem(AiQuestionResponse.AiQuestionItem item) {
        if (item == null) {
            return null;
        }

        item.setQuestion(normalizeText(item.getQuestion()));

        if (item.getOptions() != null) {
            List<String> normalizedOptions = new ArrayList<>();
            for (String option : item.getOptions()) {
                normalizedOptions.add(normalizeText(option));
            }
            item.setOptions(normalizedOptions);
        }

        return item;
    }

    private String normalizeText(String value) {
        if (value == null) {
            return null;
        }

        return value.replaceAll("\\\\u003cbr\\\\u003e", " ")
                .replaceAll("(?i)<br\\s*/?>", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private String decodeHtmlEntities(String text) {
        if (text == null) {
            return null;
        }

        return text.replaceAll("\\\\u003c", "<")
                .replaceAll("\\\\u003e", ">")
                .replaceAll("\\\\u0026", "&")
                .replaceAll("\\\\u0022", "\"")
                .replaceAll("\\u003cbr\\u003e", " ")
                .replaceAll("(?i)<br\\s*/?>", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private void validateQuestions(AiQuestionResponse response) {
        if (response == null || response.getQuestions() == null
                || response.getQuestions().isEmpty()) {
            throw new BadRequestException("No questions generated");
        }

        for (AiQuestionResponse.AiQuestionItem item : response.getQuestions()) {
            if (item.getQuestion() == null || item.getQuestion().isBlank()) {
                throw new BadRequestException("Question text is empty");
            }

            if (item.getOptions() == null || item.getOptions().size() < 2) {
                throw new BadRequestException("Question must have at least 2 options");
            }

            if (item.getCorrectIndex() == null
                    || item.getCorrectIndex() < 0
                    || item.getCorrectIndex() >= item.getOptions().size()) {
                throw new BadRequestException("Invalid correctIndex for question");
            }
        }
    }

    private String extractJson(String text) {
        if (text == null) {
            return "{}";
        }

        String trimmed = text.trim();

        // Remove markdown code fences if present
        if (trimmed.startsWith("```json")) {
            trimmed = trimmed.substring(7);
        } else if (trimmed.startsWith("```")) {
            trimmed = trimmed.substring(3);
        }

        if (trimmed.endsWith("```")) {
            trimmed = trimmed.substring(0, trimmed.length() - 3);
        }

        trimmed = trimmed.trim();

        // If already valid JSON, return it
        if (isValidJson(trimmed)) {
            return trimmed;
        }

        // Search for valid JSON object blocks within the text
        List<String> candidates = extractJsonCandidates(trimmed);
        if (!candidates.isEmpty()) {
            String best = selectBestJsonCandidate(candidates);
            if (best != null) {
                return best;
            }
        }

        return trimmed;
    }

    private String selectBestJsonCandidate(List<String> candidates) {
        String bestCandidate = null;
        int bestScore = -1;

        for (String candidate : candidates) {
            if (!isValidJson(candidate)) {
                continue;
            }

            int score = scoreJsonCandidate(candidate);
            if (score > bestScore) {
                bestScore = score;
                bestCandidate = candidate;
            }
        }

        return bestCandidate;
    }

    private int scoreJsonCandidate(String candidate) {
        try {
            JsonNode node = objectMapper.readTree(candidate);
            JsonNode questionsNode = node.path("questions");
            int questionCount = questionsNode.isArray() ? questionsNode.size() : 0;
            int score = questionCount * 10 + Math.min(candidate.length(), 100);

            if (questionCount > 0) {
                for (JsonNode questionNode : questionsNode) {
                    String text = questionNode.path("question").asText("");
                    if (!text.isBlank() && !text.equalsIgnoreCase("string")) {
                        score += 5;
                    }
                }
            }

            return score;
        } catch (Exception ex) {
            return 0;
        }
    }

    private boolean isValidJson(String candidate) {
        try {
            objectMapper.readTree(candidate);
            return true;
        } catch (Exception ex) {
            return false;
        }
    }

    private List<String> extractJsonCandidates(String text) {
        List<String> candidates = new java.util.ArrayList<>();
        boolean inString = false;
        boolean escape = false;
        int depth = 0;
        int start = -1;

        for (int i = 0; i < text.length(); i++) {
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
                if (depth == 0 && start >= 0) {
                    candidates.add(text.substring(start, i + 1));
                    start = -1;
                }
            }
        }

        return candidates;
    }
}
