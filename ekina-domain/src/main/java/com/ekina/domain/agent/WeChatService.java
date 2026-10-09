package com.ekina.domain.agent;

/**
 * Domain interface for WeChat Bot operations.
 * Allows the application layer to dispatch notifications and summaries.
 */
public interface WeChatService {

    /**
     * Sends a text message to a specific WeChat user or group.
     *
     * @param userId  The target OpenID or group ID
     * @param content Message text content
     */
    void sendMessage(String userId, String content);

    /**
     * Sends an audio file (e.g. synthesized morning briefing) to a WeChat user.
     *
     * @param userId     The target OpenID or group ID
     * @param audioBytes Byte array containing the MP3/SILK audio format
     */
    void sendAudio(String userId, byte[] audioBytes);
}
