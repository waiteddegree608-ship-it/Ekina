package com.ekina.domain.user;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Domain entity representing a platform user (e.g., WeChat user, Web client).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class User {
    private String id; // Unique ID (e.g., WeChat OpenID, system user ID)
    private String nickname;
    private LocalDateTime createdAt;
}
