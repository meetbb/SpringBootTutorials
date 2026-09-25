package com.sunbreathingcode.authservice.controller;

import com.sunbreathingcode.authservice.dto.LoginRequest;
import com.sunbreathingcode.authservice.dto.LoginResponse;
import com.sunbreathingcode.authservice.dto.MeResponse;
import com.sunbreathingcode.authservice.dto.RegisterRequest;
import com.sunbreathingcode.authservice.dto.RegisterResponse;
import com.sunbreathingcode.authservice.service.UserService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/auth")
public class AuthController {

    private final UserService userService;

    public AuthController(UserService userService) {
        this.userService = userService;
    }

    // Entry point for creating a new account. Validates the request body, then
    // hands off to UserService to hash the password and persist the user.
    @PostMapping("/register")
    public ResponseEntity<RegisterResponse> register(@Valid @RequestBody RegisterRequest request) {
        RegisterResponse response = userService.register(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    // Entry point for authenticating an existing user. Delegates credential
    // checking and token issuance to UserService, returning just the access token.
    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        LoginResponse response = userService.login(request);
        return ResponseEntity.ok(response);
    }

    // Protected endpoint that proves the whole auth chain works. The Authentication
    // object here was populated by JwtAuthFilter, not looked up manually.
    @GetMapping("/me")
    public ResponseEntity<MeResponse> me(Authentication authentication) {
        MeResponse response = userService.getCurrentUser(authentication.getName());
        return ResponseEntity.ok(response);
    }
}
