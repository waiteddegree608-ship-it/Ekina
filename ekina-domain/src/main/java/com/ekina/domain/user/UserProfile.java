package com.ekina.domain.user;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Domain entity representing a user's profile, containing personal properties and static habits.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserProfile {
    private String userId; // Foreign Key referencing User
    private Integer age;
    private String gender;
    private String job;
    private String location; // Home location (e.g. City name) used for weather and timezone contexts
    private String habits;   // Text summarizing the user's habits (diet, interest, sleep patterns)
    private String birthday; // User's birthday (e.g., MM-dd or yyyy-MM-dd)
    private LocalDateTime updatedAt;
}
