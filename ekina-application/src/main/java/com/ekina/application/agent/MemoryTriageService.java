package com.ekina.application.agent;

import com.ekina.domain.agent.LlmService;
import com.ekina.domain.rag.KnowledgeChunk;
import com.ekina.domain.repository.KnowledgeChunkDomainRepository;
import com.ekina.domain.repository.TodoTaskDomainRepository;
import com.ekina.domain.repository.UserMemoryDomainRepository;
import com.ekina.domain.repository.UserProfileDomainRepository;
import com.ekina.domain.task.TodoTask;
import com.ekina.domain.user.UserMemory;
import com.ekina.domain.user.UserProfile;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Information Triaging Service.
 * Analyzes conversational turns and updates profiles, vectors, tasks, and knowledge bases asynchronously.
 */
@Service
public class MemoryTriageService {

    @Autowired
    private LlmService llmService;

    @Autowired
    private UserProfileDomainRepository userProfileRepository;

    @Autowired
    private UserMemoryDomainRepository userMemoryRepository;

    @Autowired
    private TodoTaskDomainRepository todoTaskRepository;

    @Autowired
    private KnowledgeChunkDomainRepository knowledgeChunkRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Triages a chat exchange asynchronously using the LLM and parses the results into database records.
     */
    @Async
    public void triageAndStore(String userId, String userMessage, String assistantResponse) {
        String currentTimeStr = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        
        String prompt = "Analyze the following user-assistant exchange. Determine if any new information was shared that should be stored:\n"
                + "1. User profile facts: age, gender, job, location (home city/region), static habits (e.g. sleep time, food preferences, games played), birthday (in MM-dd format, e.g. '05-26', or yyyy-MM-dd if year is known).\n"
                + "2. Unstructured personal memories: interesting facts, preferences, or relationship details mentioned by the user.\n"
                + "3. Schedule/Todo tasks: explicit action items, deadlines, appointments mentioned by the user that need alarms/reminders.\n"
                + "4. Public general knowledge: useful facts, rules, coding tutorials, or general knowledge shared that is NOT specific to the user.\n"
                + "\n"
                + "Format your output as a single valid JSON object (enclosed in ```json ... ``` or directly as raw text) with the exact structure below. Do not output any thinking process or extra text:\n"
                + "{\n"
                + "  \"profile\": {\n"
                + "    \"age\": 25,\n"
                + "    \"gender\": \"Male/Female\",\n"
                + "    \"job\": \"Engineer\",\n"
                + "    \"location\": \"Shanghai\",\n"
                + "    \"habits\": \"Summarized habits...\",\n"
                + "    \"birthday\": \"MM-dd\"\n"
                + "  },\n"
                + "  \"memories\": [\"User likes spicy food\", \"User plays basketball on weekends\"],\n"
                + "  \"todos\": [\n"
                + "    {\n"
                + "      \"content\": \"Trigger task description\",\n"
                + "      \"targetTime\": \"YYYY-MM-DDTHH:mm:ss\"\n"
                + "    }\n"
                + "  ],\n"
                + "  \"publicKnowledge\": [\n"
                + "    {\n"
                + "      \"domainTag\": \"coding\",\n"
                + "      \"title\": \"Standard Git Command\",\n"
                + "      \"content\": \"Git commit is used to...\"\n"
                + "    }\n"
                + "  ]\n"
                + "}\n"
                + "\n"
                + "If no information is found for a field, set it to null or omit it.\n"
                + "IMPORTANT: Current local time is " + currentTimeStr + ". Use this to resolve relative times like 'tomorrow at 8 PM' or 'next Monday' to exact YYYY-MM-DDTHH:mm:ss timestamps.\n"
                + "\n"
                + "Exchange to analyze:\n"
                + "User: " + userMessage + "\n"
                + "Assistant: " + assistantResponse;

        try {
            String jsonOutput = llmService.chat(prompt);
            parseAndSaveTriageResult(userId, jsonOutput);
        } catch (Exception e) {
            System.err.println("Memory triage pipeline failed for user " + userId + ": " + e.getMessage());
        }
    }

    /**
     * Parses the JSON classification payload and executes database updates.
     */
    private void parseAndSaveTriageResult(String userId, String jsonResult) {
        try {
            // Clean markdown json ticks if present
            String cleanJson = jsonResult.trim();
            if (cleanJson.startsWith("```json")) {
                cleanJson = cleanJson.substring(7);
            }
            if (cleanJson.endsWith("```")) {
                cleanJson = cleanJson.substring(0, cleanJson.length() - 3);
            }
            cleanJson = cleanJson.trim();

            JsonNode root = objectMapper.readTree(cleanJson);

            // 1. Process User Profile Update
            if (root.has("profile") && !root.get("profile").isNull()) {
                JsonNode profNode = root.get("profile");
                Optional<UserProfile> existingOpt = userProfileRepository.findByUserId(userId);
                UserProfile profile = existingOpt.orElseGet(() -> UserProfile.builder()
                        .userId(userId)
                        .updatedAt(LocalDateTime.now())
                        .build());

                if (profNode.has("age") && !profNode.get("age").isNull()) profile.setAge(profNode.get("age").asInt());
                if (profNode.has("gender") && !profNode.get("gender").isNull()) profile.setGender(profNode.get("gender").asText());
                if (profNode.has("job") && !profNode.get("job").isNull()) profile.setJob(profNode.get("job").asText());
                if (profNode.has("location") && !profNode.get("location").isNull()) profile.setLocation(profNode.get("location").asText());
                if (profNode.has("birthday") && !profNode.get("birthday").isNull()) profile.setBirthday(profNode.get("birthday").asText());
                
                if (profNode.has("habits") && !profNode.get("habits").isNull()) {
                    String newHabits = profNode.get("habits").asText();
                    if (profile.getHabits() != null && !profile.getHabits().isEmpty()) {
                        profile.setHabits(profile.getHabits() + "; " + newHabits);
                    } else {
                        profile.setHabits(newHabits);
                    }
                }
                profile.setUpdatedAt(LocalDateTime.now());
                userProfileRepository.save(profile);
            }

            // 2. Process Personal Memories (Save to vector db)
            if (root.has("memories") && root.get("memories").isArray()) {
                for (JsonNode memNode : root.get("memories")) {
                    String content = memNode.asText();
                    if (content == null || content.trim().isEmpty()) continue;
                    
                    // Generate vector embedding via SiliconFlow
                    float[] embedding = llmService.embed(content);
                    UserMemory memory = UserMemory.builder()
                            .id(UUID.randomUUID())
                            .userId(userId)
                            .content(content)
                            .embedding(embedding)
                            .createdAt(LocalDateTime.now())
                            .build();
                    userMemoryRepository.save(memory);
                }
            }

            // 3. Process Todos/Scheduler tasks
            if (root.has("todos") && root.get("todos").isArray()) {
                for (JsonNode todoNode : root.get("todos")) {
                    String content = todoNode.has("content") ? todoNode.get("content").asText() : "";
                    String targetTimeStr = todoNode.has("targetTime") ? todoNode.get("targetTime").asText() : "";
                    if (content.isEmpty() || targetTimeStr.isEmpty()) continue;

                    try {
                        LocalDateTime targetTime = LocalDateTime.parse(targetTimeStr, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
                        TodoTask task = TodoTask.builder()
                                .userId(userId)
                                .content(content)
                                .targetTime(targetTime)
                                .status(TodoTask.TodoStatus.PENDING)
                                .createdAt(LocalDateTime.now())
                                .build();
                        todoTaskRepository.save(task);
                    } catch (Exception te) {
                        System.err.println("Failed to parse todo task time string '" + targetTimeStr + "': " + te.getMessage());
                    }
                }
            }

            // 4. Process Public Knowledge RAG ingestion
            if (root.has("publicKnowledge") && root.get("publicKnowledge").isArray()) {
                for (JsonNode knNode : root.get("publicKnowledge")) {
                    String domainTag = knNode.has("domainTag") ? knNode.get("domainTag").asText() : "general";
                    String title = knNode.has("title") ? knNode.get("title").asText() : "General Fact";
                    String content = knNode.has("content") ? knNode.get("content").asText() : "";
                    if (content.isEmpty()) continue;

                    float[] embedding = llmService.embed(content);
                    KnowledgeChunk chunk = KnowledgeChunk.builder()
                            .id(UUID.randomUUID())
                            .domainTag(domainTag)
                            .title(title)
                            .content(content)
                            .embedding(embedding)
                            .createdAt(LocalDateTime.now())
                            .build();
                    knowledgeChunkRepository.save(chunk);
                }
            }

        } catch (Exception e) {
            System.err.println("Failed to parse triage JSON output: " + e.getMessage() + "\nRaw output: " + jsonResult);
        }
    }

    /**
     * Identifies and removes memories or profile fields learned from the deleted message.
     */
    public void untriageAndRemove(String userId, String deletedMessageContent) {
        if (deletedMessageContent == null || deletedMessageContent.trim().isEmpty()) {
            return;
        }

        // 1. Untriage User Memory Facts
        List<UserMemory> allMemories = userMemoryRepository.findAllByUserId(userId);
        if (!allMemories.isEmpty()) {
            StringBuilder memoriesListStr = new StringBuilder();
            for (UserMemory mem : allMemories) {
                memoriesListStr.append("- ID: ").append(mem.getId().toString())
                        .append(" | Fact: ").append(mem.getContent()).append("\n");
            }

            String prompt = "The user has deleted a chat message: \"" + deletedMessageContent + "\".\n"
                    + "Because this message was deleted, we must remove any user memories (stored facts) that were directly learned from it or are now invalid.\n"
                    + "Here is the list of currently stored user memories:\n"
                    + memoriesListStr.toString()
                    + "\n"
                    + "Please identify which memories (specify their UUIDs) should be deleted. Output the list of UUIDs to delete as a JSON array of strings (e.g. [\"uuid-1\", \"uuid-2\"]). If none of the memories match or should be deleted, return an empty array []. Do not include any thinking process or extra text, just the JSON array.";

            try {
                String jsonOutput = llmService.chat(prompt);
                String cleanJson = jsonOutput.trim();
                if (cleanJson.startsWith("```json")) {
                    cleanJson = cleanJson.substring(7);
                }
                if (cleanJson.endsWith("```")) {
                    cleanJson = cleanJson.substring(0, cleanJson.length() - 3);
                }
                cleanJson = cleanJson.trim();

                ObjectMapper mapper = new ObjectMapper();
                JsonNode root = mapper.readTree(cleanJson);
                if (root.isArray()) {
                    for (JsonNode node : root) {
                        String uuidStr = node.asText();
                        try {
                            UUID uuid = UUID.fromString(uuidStr);
                            userMemoryRepository.deleteById(uuid);
                            System.out.println("-> Deleted memory " + uuid + " due to chat message deletion: " + deletedMessageContent);
                        } catch (Exception ex) {
                            System.err.println("Invalid UUID returned by LLM: " + uuidStr);
                        }
                    }
                }
            } catch (Exception e) {
                System.err.println("Failed to execute memory untriage for deleted message: " + e.getMessage());
            }
        }

        // 2. Untriage User Profile Fields
        Optional<UserProfile> profileOpt = userProfileRepository.findByUserId(userId);
        if (profileOpt.isPresent()) {
            UserProfile profile = profileOpt.get();
            String profilePrompt = "The user has deleted the chat message: \"" + deletedMessageContent + "\".\n"
                    + "Here is their current profile:\n"
                    + "- Age: " + profile.getAge() + "\n"
                    + "- Gender: " + profile.getGender() + "\n"
                    + "- Job: " + profile.getJob() + "\n"
                    + "- Location: " + profile.getLocation() + "\n"
                    + "- Birthday: " + profile.getBirthday() + "\n"
                    + "- Habits: " + profile.getHabits() + "\n"
                    + "\n"
                    + "Please determine if any profile field was established solely by the deleted message and should be cleared/reset. "
                    + "Format your output as a JSON object indicating which fields to clear, e.g.:\n"
                    + "{\n"
                    + "  \"clearAge\": true,\n"
                    + "  \"clearGender\": false,\n"
                    + "  \"clearJob\": false,\n"
                    + "  \"clearLocation\": false,\n"
                    + "  \"clearBirthday\": false,\n"
                    + "  \"clearHabits\": false\n"
                    + "}\n"
                    + "Only set fields to true if they should be cleared. Do not output any explanation, just the JSON.";

            try {
                String profJson = llmService.chat(profilePrompt);
                String cleanJson = profJson.trim();
                if (cleanJson.startsWith("```json")) {
                    cleanJson = cleanJson.substring(7);
                }
                if (cleanJson.endsWith("```")) {
                    cleanJson = cleanJson.substring(0, cleanJson.length() - 3);
                }
                cleanJson = cleanJson.trim();

                JsonNode profNode = objectMapper.readTree(cleanJson);
                boolean changed = false;
                if (profNode.has("clearAge") && profNode.get("clearAge").asBoolean()) { profile.setAge(null); changed = true; }
                if (profNode.has("clearGender") && profNode.get("clearGender").asBoolean()) { profile.setGender(null); changed = true; }
                if (profNode.has("clearJob") && profNode.get("clearJob").asBoolean()) { profile.setJob(null); changed = true; }
                if (profNode.has("clearLocation") && profNode.get("clearLocation").asBoolean()) { profile.setLocation(null); changed = true; }
                if (profNode.has("clearBirthday") && profNode.get("clearBirthday").asBoolean()) { profile.setBirthday(null); changed = true; }
                if (profNode.has("clearHabits") && profNode.get("clearHabits").asBoolean()) { profile.setHabits(null); changed = true; }

                if (changed) {
                    profile.setUpdatedAt(LocalDateTime.now());
                    userProfileRepository.save(profile);
                    System.out.println("-> Cleared profile fields due to message deletion: " + deletedMessageContent);
                }
            } catch (Exception ex) {
                System.err.println("Failed to untriage profile details: " + ex.getMessage());
            }
        }
    }
}
