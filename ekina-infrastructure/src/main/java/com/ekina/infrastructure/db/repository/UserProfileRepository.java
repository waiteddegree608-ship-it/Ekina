package com.ekina.infrastructure.db.repository;

import com.ekina.infrastructure.db.entity.UserProfileEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Spring Data JPA Repository for UserProfileEntity
 */
@Repository
public interface UserProfileRepository extends JpaRepository<UserProfileEntity, String> {
}
