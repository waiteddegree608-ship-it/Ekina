package com.ekina.domain.repository;

import com.ekina.domain.user.UserProfile;
import java.util.Optional;

public interface UserProfileDomainRepository {
    UserProfile save(UserProfile profile);
    Optional<UserProfile> findByUserId(String userId);
}
