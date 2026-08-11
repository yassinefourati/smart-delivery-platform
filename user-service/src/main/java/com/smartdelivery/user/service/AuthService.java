package com.smartdelivery.user.service;

import com.smartdelivery.user.domain.Role;
import com.smartdelivery.user.domain.User;
import com.smartdelivery.user.dto.LoginRequest;
import com.smartdelivery.user.dto.LoginResponse;
import com.smartdelivery.user.exception.InvalidCredentialsException;
import com.smartdelivery.user.repository.UserRepository;
import com.smartdelivery.user.security.JwtService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.stream.Collectors;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AuthService(UserRepository userRepository, PasswordEncoder passwordEncoder, JwtService jwtService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    @Transactional(readOnly = true)
    public LoginResponse login(LoginRequest request) {
        User user = userRepository.findByEmail(request.email())
                .filter(User::isActive)
                .orElseThrow(InvalidCredentialsException::new);

        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new InvalidCredentialsException();
        }

        String token = jwtService.generateToken(user);
        var roles = user.getRoles().stream().map(Role::getName).map(Enum::name).collect(Collectors.toSet());
        return new LoginResponse(token, jwtService.getExpirationSeconds(), user.getId(), roles);
    }
}
