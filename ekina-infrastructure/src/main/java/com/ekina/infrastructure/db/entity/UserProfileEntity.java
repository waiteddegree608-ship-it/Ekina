package com.ekina.infrastructure.db.entity;

import com.ekina.domain.user.UserProfile;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "user_profiles")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserProfileEntity {

    @Id
    @Column(name = "user_id", length = 64)
    private String userId;

    @Column(name = "age")
    private Integer age;

    @Column(name = "gender", length = 10)
    private String gender;

    @Column(name = "job", length = 128)
    private String job;

    @Column(name = "location", length = 256)
    private String location;

    @Column(name = "habits", columnDefinition = "TEXT")
    private String habits;

    @Column(name = "birthday", length = 20)
    private String birthday;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /**
     * Map to domain UserProfile model.
     */
    public UserProfile toDomain() {
        return UserProfile.builder()
                .userId(this.userId)
                .age(this.age)
                .gender(this.gender)
                .job(this.job)
                .location(this.location)
                .habits(this.habits)
                .birthday(this.birthday)
                .updatedAt(this.updatedAt)
                .build();
    }

    /**
     * Build from domain UserProfile model.
     */
    public static UserProfileEntity fromDomain(UserProfile profile) {
        if (profile == null) {
            return null;
        }
        return UserProfileEntity.builder()
                .userId(profile.getUserId())
                .age(profile.getAge())
                .gender(profile.getGender())
                .job(profile.getJob())
                .location(profile.getLocation())
                .habits(profile.getHabits())
                .birthday(profile.getBirthday())
                .updatedAt(profile.getUpdatedAt() != null ? profile.getUpdatedAt() : LocalDateTime.now())
                .build();
    }
}
