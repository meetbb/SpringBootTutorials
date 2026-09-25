package com.sunbreathingcode.authservice.dto;

import com.sunbreathingcode.authservice.entity.Role;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class MeResponse {

    private Long id;
    private String email;
    private Role role;
    private LocalDateTime createdAt;
}
