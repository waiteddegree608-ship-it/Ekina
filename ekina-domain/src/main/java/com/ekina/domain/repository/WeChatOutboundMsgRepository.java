package com.ekina.domain.repository;

import com.ekina.domain.agent.WeChatOutboundMsg;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface WeChatOutboundMsgRepository {
    WeChatOutboundMsg save(WeChatOutboundMsg msg);
    Optional<WeChatOutboundMsg> findById(Long id);
    List<WeChatOutboundMsg> findPendingMessages(LocalDateTime now);
}
