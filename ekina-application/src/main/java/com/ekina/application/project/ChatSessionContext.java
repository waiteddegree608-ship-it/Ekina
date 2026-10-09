package com.ekina.application.project;

/**
 * Request-scoped thread/project for chat + memory (set by ChatController).
 */
public final class ChatSessionContext {

    public static final String DAILY = "daily";

    private static final ThreadLocal<String> THREAD_ID = new ThreadLocal<>();
    private static final ThreadLocal<String> PROJECT_ID = new ThreadLocal<>();
    private static final ThreadLocal<String> CONTACT_ID = new ThreadLocal<>();

    private ChatSessionContext() {}

    public static void set(String projectId, String threadId) {
        PROJECT_ID.set(normalizeProject(projectId));
        THREAD_ID.set(normalizeThread(threadId));
    }

    public static void setContactId(String contactId) {
        if (contactId == null || contactId.isBlank()) {
            CONTACT_ID.remove();
        } else {
            CONTACT_ID.set(contactId.trim().toLowerCase());
        }
    }

    public static void clear() {
        PROJECT_ID.remove();
        THREAD_ID.remove();
        CONTACT_ID.remove();
    }

    public static String threadId() {
        String t = THREAD_ID.get();
        return t == null || t.isBlank() ? DAILY : t;
    }

    public static String projectId() {
        String p = PROJECT_ID.get();
        return p == null || p.isBlank() ? DAILY : p;
    }

    /** IM contact id: ekina | agent (default ekina). */
    public static String contactId() {
        String c = CONTACT_ID.get();
        return c == null || c.isBlank() ? "ekina" : c;
    }

    public static String normalizeThread(String threadId) {
        if (threadId == null || threadId.isBlank() || "null".equalsIgnoreCase(threadId)) {
            return DAILY;
        }
        return threadId.trim().replaceAll("[^a-zA-Z0-9._\\-\\u4e00-\\u9fff]", "_");
    }

    public static String normalizeProject(String projectId) {
        return normalizeThread(projectId);
    }

    public static String memoryKey(String userId) {
        String uid = (userId == null || userId.isBlank()) ? "user-1" : userId;
        return uid + ":" + threadId();
    }
}
