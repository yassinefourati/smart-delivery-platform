package com.smartdelivery.user.service;

import com.smartdelivery.user.domain.Role;
import com.smartdelivery.user.domain.RoleName;
import com.smartdelivery.user.domain.User;
import com.smartdelivery.user.dto.RegisterUserRequest;
import com.smartdelivery.user.dto.UpdateUserRequest;
import com.smartdelivery.user.exception.EmailAlreadyExistsException;
import com.smartdelivery.user.exception.UserNotFoundException;
import com.smartdelivery.user.repository.RoleRepository;
import com.smartdelivery.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private RoleRepository roleRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    private UserService service() {
        return new UserService(userRepository, roleRepository, passwordEncoder);
    }

    @Test
    void registerHashesPasswordAssignsCustomerRoleAndSaves() {
        UserService userService = service();
        var request = new RegisterUserRequest("new@example.com", "password123", "Jane", "Doe", "555-0100");

        when(userRepository.existsByEmail(request.email())).thenReturn(false);
        when(passwordEncoder.encode(request.password())).thenReturn("hashed-password");
        Role customerRole = new Role(RoleName.CUSTOMER);
        when(roleRepository.findByName(RoleName.CUSTOMER)).thenReturn(Optional.of(customerRole));
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        User saved = userService.register(request);

        assertThat(saved.getEmail()).isEqualTo("new@example.com");
        assertThat(saved.getPasswordHash()).isEqualTo("hashed-password");
        assertThat(saved.getRoles()).containsExactly(customerRole);

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        assertThat(captor.getValue().getPasswordHash()).isEqualTo("hashed-password");
    }

    @Test
    void registerRejectsDuplicateEmailWithoutHashingOrSaving() {
        UserService userService = service();
        var request = new RegisterUserRequest("existing@example.com", "password123", "Jane", "Doe", null);
        when(userRepository.existsByEmail(request.email())).thenReturn(true);

        assertThatThrownBy(() -> userService.register(request))
                .isInstanceOf(EmailAlreadyExistsException.class);

        verify(passwordEncoder, never()).encode(anyString());
        verify(userRepository, never()).save(any());
    }

    @Test
    void getByIdThrowsWhenUserDoesNotExist() {
        UserService userService = service();
        UUID id = UUID.randomUUID();
        when(userRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.getById(id))
                .isInstanceOf(UserNotFoundException.class);
    }

    @Test
    void updateModifiesMutableFieldsOnly() {
        UserService userService = service();
        UUID id = UUID.randomUUID();
        User existing = new User("existing@example.com", "hash", "Old", "Name", "000");
        ReflectionTestUtils.setField(existing, "id", id);
        when(userRepository.findById(id)).thenReturn(Optional.of(existing));

        User updated = userService.update(id, new UpdateUserRequest("New", "Name", "111"));

        assertThat(updated.getFirstName()).isEqualTo("New");
        assertThat(updated.getPhoneNumber()).isEqualTo("111");
        assertThat(updated.getEmail()).isEqualTo("existing@example.com");
    }
}
