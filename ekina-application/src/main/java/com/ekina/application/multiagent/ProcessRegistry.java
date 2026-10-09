package com.ekina.application.multiagent;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks live worker CLI processes so cancelStuck can kill process trees.
 */
@Component
public class ProcessRegistry {

    private final Map<String, Process> byTaskId = new ConcurrentHashMap<>();

    public void register(String taskId, Process process) {
        if (taskId == null || process == null) return;
        Process prev = byTaskId.put(taskId, process);
        if (prev != null && prev != process && prev.isAlive()) {
            OpenClawWorker.killProcessTree(prev);
        }
    }

    public void unregister(String taskId) {
        if (taskId == null) return;
        byTaskId.remove(taskId);
    }

    public boolean kill(String taskId) {
        if (taskId == null) return false;
        Process p = byTaskId.remove(taskId);
        if (p == null) return false;
        OpenClawWorker.killProcessTree(p);
        return true;
    }

    public int killAll(Iterable<String> taskIds) {
        int n = 0;
        for (String id : taskIds) {
            if (kill(id)) n++;
        }
        return n;
    }

    public int size() {
        return byTaskId.size();
    }
}
