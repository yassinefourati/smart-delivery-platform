package com.smartdelivery.user.config;

import com.smartdelivery.user.domain.Role;
import com.smartdelivery.user.domain.RoleName;
import com.smartdelivery.user.domain.User;
import com.smartdelivery.user.repository.RoleRepository;
import com.smartdelivery.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The seeded ADMIN account (Phase 18). An account-creating side effect that runs at
 * startup and grants the highest privilege in the platform is worth pinning down
 * precisely: the ways it must decline to act matter more than the way it acts.
 */
@ExtendWith(MockitoExtension.class)
class BootstrapAdminInitializerTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private RoleRepository roleRepository;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private PlatformTransactionManager transactionManager;

    private Role adminRole;

    @BeforeEach
    void setUp() {
        adminRole = new Role(RoleName.ADMIN);
    }

    /**
     * Only the tests that get past the credentials check need this. Stubbing it for all
     * of them would hide the thing {@link #refusesToActOnBlankCredentials} is asserting:
     * that blank credentials do not even open a transaction.
     */
    private void expectATransaction() {
        when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
    }

    @Test
    void createsTheAdminAccountWhenTheEmailIsUnknown() {
        expectATransaction();
        when(userRepository.findByEmail("admin@example.test")).thenReturn(Optional.empty());
        when(roleRepository.findByName(RoleName.ADMIN)).thenReturn(Optional.of(adminRole));
        when(passwordEncoder.encode("s3cret")).thenReturn("hashed");

        initializer("admin@example.test", "s3cret").run(null);

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(saved.capture());
        assertThat(saved.getValue().getEmail()).isEqualTo("admin@example.test");
        // The password is hashed, never stored as given -- the whole point of seeding it
        // through the same encoder registration uses.
        assertThat(saved.getValue().getPasswordHash()).isEqualTo("hashed");
        assertThat(saved.getValue().getRoles()).containsExactly(adminRole);
    }

    /**
     * Restarting the stack must not reset a password somebody has since changed, and must
     * not create a second account. Idempotence here is a security property, not a
     * convenience.
     */
    @Test
    void leavesAnExistingAccountCompletelyAlone() {
        expectATransaction();
        when(userRepository.findByEmail("admin@example.test"))
                .thenReturn(Optional.of(new User("admin@example.test", "already-hashed", "A", "B", null)));

        initializer("admin@example.test", "s3cret").run(null);

        verify(userRepository, never()).save(any());
        verify(passwordEncoder, never()).encode(any());
    }

    /**
     * {@code @ConditionalOnProperty} treats a declared-but-blank property as present, so
     * a deployment that sets BOOTSTRAP_ADMIN_EMAIL to the empty string would otherwise
     * reach this code. It must decline rather than create an account with a blank email.
     */
    @Test
    void refusesToActOnBlankCredentials() {
        BootstrapAdminInitializer blankEmail = initializer("   ", "s3cret");
        BootstrapAdminInitializer blankPassword = initializer("admin@example.test", "");
        BootstrapAdminInitializer nullEmail = initializer(null, "s3cret");

        blankEmail.run(null);
        blankPassword.run(null);
        nullEmail.run(null);

        verify(userRepository, never()).findByEmail(any());
        verify(userRepository, never()).save(any());
        verify(transactionManager, never()).getTransaction(any());
    }

    /** A missing ADMIN role means the roles migration did not run; failing loudly beats seeding a powerless "admin". */
    @Test
    void failsLoudlyIfTheAdminRoleIsMissing() {
        expectATransaction();
        when(userRepository.findByEmail("admin@example.test")).thenReturn(Optional.empty());
        when(roleRepository.findByName(RoleName.ADMIN)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> initializer("admin@example.test", "s3cret").run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ADMIN role missing");
    }

    private BootstrapAdminInitializer initializer(String email, String password) {
        return new BootstrapAdminInitializer(userRepository, roleRepository, passwordEncoder,
                new BootstrapAdminProperties(email, password, "Bootstrap", "Admin"),
                transactionManager);
    }
}
