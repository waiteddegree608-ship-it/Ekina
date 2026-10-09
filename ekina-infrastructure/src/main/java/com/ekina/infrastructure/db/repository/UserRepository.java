package com.ekina.infrastructure.db.repository;

import com.ekina.infrastructure.db.entity.UserEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Spring Data JPA Repository for UserEntity
 */
@Repository
public interface UserRepository extends JpaRepository<UserEntity, String> {
}
