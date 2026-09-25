package com.sunbreathingcode.authservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.sunbreathingcode.authservice.dto.LoginRequest;
import com.sunbreathingcode.authservice.dto.LoginResponse;
import com.sunbreathingcode.authservice.entity.User;
import com.sunbreathingcode.authservice.exception.InvalidCredentialsException;
import com.sunbreathingcode.authservice.repository.UserRepository;
import com.sunbreathingcode.authservice.security.JwtService;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
class UserServiceLoginTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private JwtService jwtService;

    private UserService userService;

    @BeforeEach
    void setUp() {
        userService = new UserService(userRepository, passwordEncoder, jwtService);
    }

    // With a matching email and correct password, login() should return the access
    // token JwtService produced for that user — the whole point of the endpoint.
    @Test
    void login_returnsAccessToken_whenCredentialsAreValid() {
        User user = new User();
        user.setId(1L);
        user.setEmail("meet@sunbreathingcode.com");
        user.setPasswordHash("hashed-password");

        LoginRequest request = new LoginRequest();
        request.setEmail("meet@sunbreathingcode.com");
        request.setPassword("plain-password");

        when(userRepository.findByEmail("meet@sunbreathingcode.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("plain-password", "hashed-password")).thenReturn(true);
        when(jwtService.generateAccessToken(user)).thenReturn("signed-jwt-token");

        LoginResponse response = userService.login(request);

        assertThat(response.getAccessToken()).isEqualTo("signed-jwt-token");
    }

    // If no user exists for the given email, login() must fail with the same
    // generic exception used for a wrong password — this prevents attackers from
    // telling "unknown email" apart from "wrong password" via the error type.
    @Test
    void login_throwsInvalidCredentials_whenEmailNotFound() {
        LoginRequest request = new LoginRequest();
        request.setEmail("unknown@sunbreathingcode.com");
        request.setPassword("any-password");

        when(userRepository.findByEmail("unknown@sunbreathingcode.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.login(request))
                .isInstanceOf(InvalidCredentialsException.class);
    }

    // If the user exists but the supplied password doesn't match the stored hash,
    // login() must fail the same way as an unknown email — no separate error path.
    @Test
    void login_throwsInvalidCredentials_whenPasswordDoesNotMatch() {
        User user = new User();
        user.setId(1L);
        user.setEmail("meet@sunbreathingcode.com");
        user.setPasswordHash("hashed-password");

        LoginRequest request = new LoginRequest();
        request.setEmail("meet@sunbreathingcode.com");
        request.setPassword("wrong-password");

        when(userRepository.findByEmail("meet@sunbreathingcode.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("wrong-password", "hashed-password")).thenReturn(false);

        assertThatThrownBy(() -> userService.login(request))
                .isInstanceOf(InvalidCredentialsException.class);
    }
}
