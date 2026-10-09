package com.ekina.infrastructure.db.adapter;

import com.ekina.domain.repository.UserDomainRepository;
import com.ekina.domain.user.User;
import com.ekina.infrastructure.db.entity.UserEntity;
import com.ekina.infrastructure.db.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * JPA Adapter implementing the UserDomainRepository interface.
 */
@Repository
public class UserDomainRepositoryImpl implements UserDomainRepository {

    @Autowired
    private UserRepository userRepository;

    @Override
    public User save(User user) {
        UserEntity entity = UserEntity.fromDomain(user);
        return userRepository.save(entity).toDomain();
    }

    @Override
    public Optional<User> findById(String id) {
        return userRepository.findById(id).map(UserEntity::toDomain);
    }
}
