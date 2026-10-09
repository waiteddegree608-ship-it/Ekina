package com.ekina.domain.task;

/**
 * Domain interface for Text-to-Speech (TTS) synthesis.
 * Implemented at the infrastructure layer using SiliconFlow's Fish Speech or similar audio models.
 */
public interface TtsService {

    /**
     * Converts a text string into MP3 audio bytes.
     *
     * @param text Content to read aloud
     * @return Byte array containing the MP3 file bytes
     */
    byte[] textToSpeech(String text);
}
