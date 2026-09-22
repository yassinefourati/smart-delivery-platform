package com.smartdelivery.user.config;

import com.smartdelivery.user.domain.Role;
import com.smartdelivery.user.domain.RoleName;
import com.smartdelivery.user.domain.User;
import com.smartdelivery.user.repository.RoleRepository;
import com.smartdelivery.user.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Creates one ADMIN account at startup, from configuration, so a freshly-started stack has
 * somebody who can administer it (Phase 18).
 *
 * Registration only ever grants CUSTOMER -- deliberately, since the alternative is an API
 * that hands out privilege on request -- so without this there is no way to reach any
 * ADMIN endpoint on a brand-new database, which makes the end-to-end smoke test (and a
 * developer's first five minutes with `docker compose up`) impossible.
 *
 * <h2>Why this is safe to have, and how it stays safe</h2>
 * It does nothing at all unless {@code bootstrap.admin.email} and
 * {@code bootstrap.admin.password} are both set, so the only way to get a bootstrap admin
 * is to ask for one explicitly, per environment. It is idempotent: an account with that
 * email is left exactly as it is, so restarting the stack does not reset a password
 * somebody has since changed. And it says what it did, at WARN, because a service that
 * quietly creates an administrator is a service nobody audits.
 *
 * docker-compose.yml sets both properties, with credentials that are local-Compose-only
 * like every other credential in that file. A real deployment provisions administrators
 * out of band and leaves these unset -- see docs/security.md.
 *
 * <h2>Why a TransactionTemplate</h2>
 * The ADMIN {@link Role} has to still be managed when the new {@link User} references it:
 * {@code User.roles} cascades PERSIST, and a role loaded in one transaction and attached
 * to a new user in another is detached by then, which Hibernate rightly refuses. A
 * {@code @Transactional} method would be the obvious way to hold both in one persistence
 * context -- but only if it were called across a bean boundary, and the first version of
 * this class called it on itself, so the annotation did nothing and startup failed on
 * exactly that detached-entity error. An explicit template cannot be defeated that way.
 * (The same trap cost this codebase a broken saga; see OrderSagaEventHandler.)
 */
@Component
@ConditionalOnProperty(prefix = "bootstrap.admin", name = {"email", "password"})
public class BootstrapAdminInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BootstrapAdminInitializer.class);

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;
    private final BootstrapAdminProperties properties;
    private final TransactionTemplate transactionTemplate;

    public BootstrapAdminInitializer(UserRepository userRepository,
                                     RoleRepository roleRepository,
                                     PasswordEncoder passwordEncoder,
                                     BootstrapAdminProperties properties,
                                     PlatformTransactionManager transactionManager) {
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.passwordEncoder = passwordEncoder;
        this.properties = properties;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Override
    public void run(ApplicationArguments args) {
        if (isBlank(properties.email()) || isBlank(properties.password())) {
            log.warn("bootstrap.admin is configured but blank; no admin account created");
            return;
        }
        transactionTemplate.executeWithoutResult(status -> createIfMissing());
    }

    private void createIfMissing() {
        if (userRepository.findByEmail(properties.email()).isPresent()) {
            log.info("Bootstrap admin {} already exists; leaving it untouched", properties.email());
            return;
        }

        Role adminRole = roleRepository.findByName(RoleName.ADMIN)
                .orElseThrow(() -> new IllegalStateException("ADMIN role missing; check the roles migration"));
        User admin = new User(properties.email(), passwordEncoder.encode(properties.password()),
                properties.firstName(), properties.lastName(), null);
        admin.addRole(adminRole);
        userRepository.save(admin);

        log.warn("""
                CREATED BOOTSTRAP ADMIN ACCOUNT '{}' from configuration. This exists so a \
                fresh stack has somebody who can administer it, and it is only created \
                because bootstrap.admin.email and bootstrap.admin.password are both set. \
                Leave them unset in any environment where administrators are provisioned \
                properly.""", properties.email());
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
