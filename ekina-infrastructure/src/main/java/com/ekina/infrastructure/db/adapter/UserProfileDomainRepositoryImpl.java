package com.ekina.infrastructure.db.adapter;

import com.ekina.domain.repository.UserProfileDomainRepository;
import com.ekina.domain.user.UserProfile;
import com.ekina.infrastructure.db.entity.UserProfileEntity;
import com.ekina.infrastructure.db.repository.UserProfileRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * JPA Adapter implementing UserProfileDomainRepository.
 */
@Repository
public class UserProfileDomainRepositoryImpl implements UserProfileDomainRepository {

    @Autowired
    private UserProfileRepository userProfileRepository;

    @Override
    public UserProfile save(UserProfile profile) {
        UserProfileEntity entity = UserProfileEntity.fromDomain(profile);
        return userProfileRepository.save(entity).toDomain();
    }

    @Override
    public Optional<UserProfile> findByUserId(String userId) {
        return userProfileRepository.findById(userId).map(UserProfileEntity::toDomain);
    }
}
