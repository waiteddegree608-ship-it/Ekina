package com.ekina.infrastructure.db.repository;

import com.ekina.infrastructure.db.entity.WeChatOutboundMsgEntity;
import com.ekina.domain.agent.WeChatOutboundMsg;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface WeChatOutboundMsgRepositoryJPA extends JpaRepository<WeChatOutboundMsgEntity, Long> {

    @Query("SELECT e FROM WeChatOutboundMsgEntity e WHERE e.status = :status AND e.nextSendTime <= :now ORDER BY e.priority DESC, e.createdAt ASC")
    List<WeChatOutboundMsgEntity> findByStatusAndNextSendTimeBefore(
            @Param("status") WeChatOutboundMsg.WeChatMsgStatus status,
            @Param("now") LocalDateTime now
    );
}
