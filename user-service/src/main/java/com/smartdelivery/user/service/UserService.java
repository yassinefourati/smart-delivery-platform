package com.smartdelivery.user.service;

import com.smartdelivery.user.domain.Role;
import com.smartdelivery.user.domain.RoleName;
import com.smartdelivery.user.domain.User;
import com.smartdelivery.user.dto.RegisterUserRequest;
import com.smartdelivery.user.dto.UpdateUserRequest;
import com.smartdelivery.user.exception.CannotRevokeOwnAdminException;
import com.smartdelivery.user.exception.EmailAlreadyExistsException;
import com.smartdelivery.user.exception.UserEmailNotFoundException;
import com.smartdelivery.user.exception.UserNotFoundException;
import com.smartdelivery.user.repository.RoleRepository;
import com.smartdelivery.user.repository.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class UserService {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;

    public UserService(UserRepository userRepository, RoleRepository roleRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public User register(RegisterUserRequest request) {
        if (userRepository.existsByEmail(request.email())) {
            throw new EmailAlreadyExistsException(request.email());
        }

        User user = new User(
                request.email(),
                passwordEncoder.encode(request.password()),
                request.firstName(),
                request.lastName(),
                request.phoneNumber());

        Role customerRole = roleRepository.findByName(RoleName.CUSTOMER)
                .orElseThrow(() -> new IllegalStateException("CUSTOMER role is not seeded"));
        user.addRole(customerRole);

        return userRepository.save(user);
    }

    @Transactional(readOnly = true)
    public User getById(UUID id) {
        return userRepository.findById(id).orElseThrow(() -> new UserNotFoundException(id));
    }

    @Transactional
    public User update(UUID id, UpdateUserRequest request) {
        User user = getById(id);
        user.setFirstName(request.firstName());
        user.setLastName(request.lastName());
        user.setPhoneNumber(request.phoneNumber());
        return user;
    }

    @Transactional(readOnly = true)
    public User getByEmail(String email) {
        return userRepository.findByEmail(email.trim()).orElseThrow(() -> new UserEmailNotFoundException(email));
    }

    /**
     * Idempotent: granting a role the user already holds changes nothing. The new role is in
     * the user's token from their next sign-in, not before -- roles travel in the JWT.
     */
    @Transactional
    public User grantRole(UUID id, RoleName roleName) {
        User user = getById(id);
        Role role = roleRepository.findByName(roleName)
                .orElseThrow(() -> new IllegalStateException(roleName + " role is not seeded"));
        user.addRole(role);
        return user;
    }

    /**
     * Idempotent, like {@link #grantRole}. A revoked role keeps working until the user's
     * current token expires, because resource servers trust the roles in the token.
     */
    @Transactional
    public User revokeRole(UUID id, RoleName roleName, UUID actingUserId) {
        if (roleName == RoleName.ADMIN && id.equals(actingUserId)) {
            throw new CannotRevokeOwnAdminException();
        }
        User user = getById(id);
        user.removeRole(roleName);
        return user;
    }
}
