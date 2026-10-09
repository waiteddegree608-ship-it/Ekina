package com.ekina.application.task;

import com.ekina.domain.repository.TodoTaskDomainRepository;
import com.ekina.domain.task.TodoTask;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Task Scheduler Service.
 * Periodically polls PostgreSQL via TodoTaskDomainRepository for due tasks
 * and dispatches triggers using a background Virtual Thread.
 */
@Service
public class TodoScheduler {

    @Autowired
    private TodoTaskDomainRepository todoTaskRepository;

    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private volatile boolean running = true;

    @PostConstruct
    public void init() {
        // Start scheduler loop in a background Virtual Thread
        executor.submit(this::schedulerLoop);
    }

    @PreDestroy
    public void destroy() {
        running = false;
        executor.shutdown();
    }

    private void schedulerLoop() {
        System.out.println("Todo task scheduler started in virtual thread.");
        while (running) {
            try {
                pollAndTriggerDueTasks();
                Thread.sleep(10000); // Run check every 10 seconds
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                System.err.println("Error in todo scheduler loop: " + e.getMessage());
            }
        }
    }

    private void pollAndTriggerDueTasks() {
        LocalDateTime now = LocalDateTime.now();
        List<TodoTask> dueTasks = todoTaskRepository.findDueTasks(now);

        if (!dueTasks.isEmpty()) {
            System.out.println("Scheduler detected " + dueTasks.size() + " due tasks at " + now);
            for (TodoTask task : dueTasks) {
                triggerReminder(task);
            }
        }
    }

    private void triggerReminder(TodoTask task) {
        try {
            System.out.println("=== TASK ALARM === [User: " + task.getUserId() + "] Task: " + task.getContent());
            
            // In integration, this will trigger websocket pushes or call WeChat Bot API
            // For now, we print it to console and mark it as REMINDED
            
            task.setStatus(TodoTask.TodoStatus.REMINDED);
            todoTaskRepository.save(task);
        } catch (Exception e) {
            System.err.println("Failed to trigger reminder for task " + task.getId() + ": " + e.getMessage());
        }
    }
}
