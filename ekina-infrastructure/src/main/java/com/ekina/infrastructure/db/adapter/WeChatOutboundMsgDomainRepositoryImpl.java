package com.ekina.infrastructure.db.adapter;

import com.ekina.domain.agent.WeChatOutboundMsg;
import com.ekina.domain.repository.WeChatOutboundMsgRepository;
import com.ekina.infrastructure.db.entity.WeChatOutboundMsgEntity;
import com.ekina.infrastructure.db.repository.WeChatOutboundMsgRepositoryJPA;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Repository
public class WeChatOutboundMsgDomainRepositoryImpl implements WeChatOutboundMsgRepository {

    @Autowired
    private WeChatOutboundMsgRepositoryJPA weChatOutboundMsgRepositoryJPA;

    @Override
    public WeChatOutboundMsg save(WeChatOutboundMsg msg) {
        WeChatOutboundMsgEntity entity = WeChatOutboundMsgEntity.fromDomain(msg);
        WeChatOutboundMsgEntity saved = weChatOutboundMsgRepositoryJPA.save(entity);
        return saved.toDomain();
    }

    @Override
    public Optional<WeChatOutboundMsg> findById(Long id) {
        return weChatOutboundMsgRepositoryJPA.findById(id).map(WeChatOutboundMsgEntity::toDomain);
    }

    @Override
    public List<WeChatOutboundMsg> findPendingMessages(LocalDateTime now) {
        return weChatOutboundMsgRepositoryJPA.findByStatusAndNextSendTimeBefore(WeChatOutboundMsg.WeChatMsgStatus.PENDING, now)
                .stream()
                .map(WeChatOutboundMsgEntity::toDomain)
                .collect(Collectors.toList());
    }
}
