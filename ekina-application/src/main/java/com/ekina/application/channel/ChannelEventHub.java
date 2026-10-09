package com.ekina.application.channel;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * In-process listeners for new channel messages (e.g. tray notifications).
 */
@Component
public class ChannelEventHub {

    private final List<Consumer<ChannelMessage>> listeners = new CopyOnWriteArrayList<>();

    public void subscribe(Consumer<ChannelMessage> listener) {
        if (listener != null) listeners.add(listener);
    }

    public void publish(ChannelMessage message) {
        for (Consumer<ChannelMessage> l : listeners) {
            try {
                l.accept(message);
            } catch (Exception e) {
                System.err.println("[ChannelEventHub] listener error: " + e.getMessage());
            }
        }
    }
}
