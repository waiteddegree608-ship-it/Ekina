package com.ekina.domain.repository;

import com.ekina.domain.user.UserMemory;
import java.util.List;
import java.util.UUID;

public interface UserMemoryDomainRepository {
    UserMemory save(UserMemory memory);
    void deleteById(UUID id);
    List<UserMemory> findNearest(String userId, float[] queryVector, int limit);
    List<UserMemory> findAllByUserId(String userId);
}
