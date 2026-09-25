package com.sunbreathingcode.authservice.service;

import com.sunbreathingcode.authservice.dto.RegisterRequest;
import com.sunbreathingcode.authservice.dto.RegisterResponse;
import com.sunbreathingcode.authservice.entity.User;
import com.sunbreathingcode.authservice.exception.EmailAlreadyExistsException;
import com.sunbreathingcode.authservice.repository.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public UserService(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

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
}
