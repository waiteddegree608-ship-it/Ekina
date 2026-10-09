package com.ekina.infrastructure.db.entity;

import com.ekina.domain.user.User;
import com.ekina.domain.user.UserProfile;
import com.ekina.domain.user.UserMemory;
import com.ekina.domain.task.TodoTask;
import com.ekina.domain.rag.KnowledgeChunk;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Unit test to verify exact mapping correctness between Domain model entities and JPA Entities.
 */
public class EntityMappingTest {

    @Test
    public void testUserEntityMapping() {
        User domain = User.builder()
                .id("u1")
                .nickname("Nick")
                .createdAt(LocalDateTime.now())
                .build();

        UserEntity entity = UserEntity.fromDomain(domain);
        assertNotNull(entity);
        assertEquals(domain.getId(), entity.getId());
        assertEquals(domain.getNickname(), entity.getNickname());

        User mappedBack = entity.toDomain();
        assertNotNull(mappedBack);
        assertEquals(domain.getId(), mappedBack.getId());
        assertEquals(domain.getNickname(), mappedBack.getNickname());
    }

    @Test
    public void testUserProfileEntityMapping() {
        UserProfile domain = UserProfile.builder()
                .userId("u1")
                .age(25)
                .gender("M")
                .job("Developer")
                .location("Shanghai")
                .habits("Likes tea")
                .updatedAt(LocalDateTime.now())
                .build();

        UserProfileEntity entity = UserProfileEntity.fromDomain(domain);
        assertNotNull(entity);
        assertEquals(domain.getUserId(), entity.getUserId());
        assertEquals(domain.getAge(), entity.getAge());
        assertEquals(domain.getJob(), entity.getJob());
        assertEquals(domain.getLocation(), entity.getLocation());
        assertEquals(domain.getHabits(), entity.getHabits());

        UserProfile mappedBack = entity.toDomain();
        assertNotNull(mappedBack);
        assertEquals(domain.getUserId(), mappedBack.getUserId());
        assertEquals(domain.getJob(), mappedBack.getJob());
    }

    @Test
    public void testUserMemoryEntityMapping() {
        float[] vector = {0.1f, -0.2f, 0.9f};
        UserMemory domain = UserMemory.builder()
                .id(UUID.randomUUID())
                .userId("u1")
                .content("Test Memory Content")
                .embedding(vector)
                .createdAt(LocalDateTime.now())
                .build();

        UserMemoryEntity entity = UserMemoryEntity.fromDomain(domain);
        assertNotNull(entity);
        assertEquals(domain.getId(), entity.getId());
        assertEquals(domain.getUserId(), entity.getUserId());
        assertEquals(domain.getContent(), entity.getContent());
        assertArrayEquals(domain.getEmbedding(), entity.getEmbedding());

        UserMemory mappedBack = entity.toDomain();
        assertNotNull(mappedBack);
        assertEquals(domain.getId(), mappedBack.getId());
        assertArrayEquals(domain.getEmbedding(), mappedBack.getEmbedding());
    }

    @Test
    public void testTodoTaskEntityMapping() {
        TodoTask domain = TodoTask.builder()
                .id(100L)
                .userId("u1")
                .content("Clean my room")
                .targetTime(LocalDateTime.now().plusDays(1))
                .status(TodoTask.TodoStatus.PENDING)
                .createdAt(LocalDateTime.now())
                .build();

        TodoTaskEntity entity = TodoTaskEntity.fromDomain(domain);
        assertNotNull(entity);
        assertEquals(domain.getId(), entity.getId());
        assertEquals(domain.getUserId(), entity.getUserId());
        assertEquals(domain.getContent(), entity.getContent());
        assertEquals(domain.getStatus(), entity.getStatus());

        TodoTask mappedBack = entity.toDomain();
        assertNotNull(mappedBack);
        assertEquals(domain.getId(), mappedBack.getId());
        assertEquals(domain.getStatus(), mappedBack.getStatus());
    }

    @Test
    public void testKnowledgeChunkEntityMapping() {
        float[] vector = {0.5f, 0.5f, 0.5f};
        KnowledgeChunk domain = KnowledgeChunk.builder()
                .id(UUID.randomUUID())
                .domainTag("coding")
                .title("Git Rebase Guide")
                .content("Always rebase before merging")
                .embedding(vector)
                .createdAt(LocalDateTime.now())
                .build();

        KnowledgeChunkEntity entity = KnowledgeChunkEntity.fromDomain(domain);
        assertNotNull(entity);
        assertEquals(domain.getId(), entity.getId());
        assertEquals(domain.getDomainTag(), entity.getDomainTag());
        assertEquals(domain.getTitle(), entity.getTitle());
        assertEquals(domain.getContent(), entity.getContent());
        assertArrayEquals(domain.getEmbedding(), entity.getEmbedding());

        KnowledgeChunk mappedBack = entity.toDomain();
        assertNotNull(mappedBack);
        assertEquals(domain.getId(), mappedBack.getId());
        assertArrayEquals(domain.getEmbedding(), mappedBack.getEmbedding());
    }
}
