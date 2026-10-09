package com.ekina.application.task;

import com.ekina.domain.agent.LlmService;
import com.ekina.domain.agent.WebSearchService;
import com.ekina.domain.repository.TodoTaskDomainRepository;
import com.ekina.domain.repository.UserProfileDomainRepository;
import com.ekina.domain.task.TodoTask;
import com.ekina.domain.task.TtsService;
import com.ekina.domain.user.UserProfile;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.FileOutputStream;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

/**
 * Orchestrator managing the generation of proactive morning alarms and audio briefings.
 * Integrates Weather Search, Todo Tasks, LLM script generation, and TTS synthesis.
 */
@Service
public class MorningBriefingOrchestrator {

    @Autowired
    private UserProfileDomainRepository userProfileRepository;

    @Autowired
    private TodoTaskDomainRepository todoTaskRepository;

    @Autowired
    private WebSearchService webSearchService;

    @Autowired
    private LlmService llmService;

    @Autowired
    private TtsService ttsService;

    /**
     * Runs the morning briefing generation pipeline for a user.
     * Generates a text summary and synthesizes an MP3 file briefing.
     *
     * @param userId The target user
     * @return Path to the synthesized MP3 file, or empty if generation failed
     */
    public String generateMorningBriefing(String userId) {
        System.out.println("Generating morning briefing for user: " + userId);

        // 1. Fetch User Profile
        Optional<UserProfile> profileOpt = userProfileRepository.findByUserId(userId);
        UserProfile profile = profileOpt.orElseGet(() -> UserProfile.builder()
                .userId(userId)
                .location("Shanghai") // Default location
                .habits("Not specified")
                .build());

        String location = profile.getLocation();
        
        // 2. Fetch Weather via Search
        String weatherInfo = webSearchService.search(location + " weather today");

        // 3. Fetch Today's Pending Todos
        List<TodoTask> todayTasks = todoTaskRepository.findByUserIdAndStatus(userId, TodoTask.TodoStatus.PENDING);
        StringBuilder tasksSummary = new StringBuilder();
        if (todayTasks.isEmpty()) {
            tasksSummary.append("No pending tasks scheduled for today.");
        } else {
            for (TodoTask task : todayTasks) {
                tasksSummary.append("- ").append(task.getContent()).append(" (Scheduled at ")
                        .append(task.getTargetTime().format(DateTimeFormatter.ofPattern("HH:mm"))).append(")\n");
            }
        }

        // 4. Formulate LLM briefing prompt
        String dateStr = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy年MM月dd日"));
        String prompt = "You are Ekina, a premium personal AI coordinator. Write a warm, professional morning briefing script in Chinese for today: " + dateStr + ".\n"
                + "Context:\n"
                + "- Location: " + location + "\n"
                + "- Today's Weather Report: " + weatherInfo + "\n"
                + "- User's Scheduled Tasks Today:\n" + tasksSummary.toString() + "\n"
                + "\n"
                + "Requirements:\n"
                + "- Address the user politely (e.g. '早上好').\n"
                + "- Give a brief weather summary and friendly suggestions (clothing, umbrella).\n"
                + "- Read out today's agenda/reminders clearly.\n"
                + "- Maintain a high-end, caring tone.\n"
                + "- Keep the script concise (around 150-250 characters) so it reads naturally when spoken aloud. Output ONLY the briefing text.";

        try {
            // Generate script
            String briefingText = llmService.chat(prompt);
            System.out.println("Morning Briefing Script:\n" + briefingText);

            // 5. Call TTS service to generate MP3 audio
            byte[] audioBytes = ttsService.textToSpeech(briefingText);
            if (audioBytes == null || audioBytes.length == 0) {
                System.err.println("Speech synthesis returned empty bytes.");
                return "";
            }

            // Create target folder
            File dir = new File("briefings");
            if (!dir.exists()) {
                dir.mkdirs();
            }

            String outputPath = "briefings/" + userId + "_morning_brief.mp3";
            try (FileOutputStream fos = new FileOutputStream(outputPath)) {
                fos.write(audioBytes);
            }
            System.out.println("Success: Synthesized morning briefing MP3 saved to " + outputPath);
            return outputPath;

        } catch (Exception e) {
            System.err.println("Failed to generate morning briefing: " + e.getMessage());
            return "";
        }
    }
}
