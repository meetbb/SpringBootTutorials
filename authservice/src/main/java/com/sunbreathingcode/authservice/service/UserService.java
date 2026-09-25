package com.sunbreathingcode.authservice.service;

import com.sunbreathingcode.authservice.dto.LoginRequest;
import com.sunbreathingcode.authservice.dto.LoginResponse;
import com.sunbreathingcode.authservice.dto.MeResponse;
import com.sunbreathingcode.authservice.dto.RegisterRequest;
import com.sunbreathingcode.authservice.dto.RegisterResponse;
import com.sunbreathingcode.authservice.entity.User;
import com.sunbreathingcode.authservice.exception.EmailAlreadyExistsException;
import com.sunbreathingcode.authservice.exception.InvalidCredentialsException;
import com.sunbreathingcode.authservice.exception.UserNotFoundException;
import com.sunbreathingcode.authservice.repository.UserRepository;
import com.sunbreathingcode.authservice.security.JwtService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public UserService(UserRepository userRepository, PasswordEncoder passwordEncoder, JwtService jwtService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    // Creates a new user account. Rejects duplicate emails up front, then stores
    // only the bcrypt hash of the password — plaintext is never persisted.
    public RegisterResponse register(RegisterRequest request) {
        if (userRepository.findByEmail(request.getEmail()).isPresent()) {
            throw new EmailAlreadyExistsException(request.getEmail());
        }

        User user = new User();
        user.setEmail(request.getEmail());
        user.setPasswordHash(passwordEncoder.encode(request.getPassword()));

        User saved = userRepository.save(user);
        return new RegisterResponse(saved.getId(), saved.getEmail());
    }

    // Verifies email + password against stored records and, on success, issues a
    // signed access token. Both failure paths throw the same exception on purpose.
    public LoginResponse login(LoginRequest request) {
        User user = userRepository.findByEmail(request.getEmail())
                .orElseThrow(InvalidCredentialsException::new);

        if (!passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            throw new InvalidCredentialsException();
        }

        String accessToken = jwtService.generateAccessToken(user);
        return new LoginResponse(accessToken);
    }

    // Looks up the user behind an already-authenticated request (email comes from
    // the validated JWT, not client input) and maps it to a safe response DTO.
    public MeResponse getCurrentUser(String email) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new UserNotFoundException(email));

        return new MeResponse(user.getId(), user.getEmail(), user.getRole(), user.getCreatedAt());
    }
}
