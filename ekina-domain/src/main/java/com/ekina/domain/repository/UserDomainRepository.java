package com.ekina.domain.repository;

import com.ekina.domain.user.User;
import java.util.Optional;

public interface UserDomainRepository {
    User save(User user);
    Optional<User> findById(String id);
}
