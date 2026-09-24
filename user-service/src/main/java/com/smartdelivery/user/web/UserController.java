package com.smartdelivery.user.web;

import com.smartdelivery.user.domain.RoleName;
import com.smartdelivery.user.dto.RegisterUserRequest;
import com.smartdelivery.user.dto.UpdateUserRequest;
import com.smartdelivery.user.dto.UserMapper;
import com.smartdelivery.user.dto.UserResponse;
import com.smartdelivery.user.service.UserService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/users")
@Tag(name = "Users", description = "Registration and profile management")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @PostMapping
    public ResponseEntity<UserResponse> register(@Valid @RequestBody RegisterUserRequest request) {
        var user = userService.register(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(UserMapper.toResponse(user));
    }

    @GetMapping("/{id}")
    @PreAuthorize("#id.toString() == authentication.name or hasRole('ADMIN')")
    public ResponseEntity<UserResponse> getById(@PathVariable UUID id) {
        return ResponseEntity.ok(UserMapper.toResponse(userService.getById(id)));
    }

    @PutMapping("/{id}")
    @PreAuthorize("#id.toString() == authentication.name or hasRole('ADMIN')")
    public ResponseEntity<UserResponse> update(@PathVariable UUID id, @Valid @RequestBody UpdateUserRequest request) {
        return ResponseEntity.ok(UserMapper.toResponse(userService.update(id, request)));
    }

    /**
     * Finds a user by email so an admin does not have to already know an id. Spring matches
     * the literal {@code /lookup} ahead of {@code /{id}}, so the two never collide.
     */
    @GetMapping("/lookup")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<UserResponse> lookupByEmail(@RequestParam String email) {
        return ResponseEntity.ok(UserMapper.toResponse(userService.getByEmail(email)));
    }

    /**
     * The only way to give a user a role other than CUSTOMER -- for instance DELIVERY_AGENT,
     * which delivery-service requires before an agent can see or complete their deliveries.
     */
    @PutMapping("/{id}/roles/{role}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<UserResponse> grantRole(@PathVariable UUID id, @PathVariable RoleName role) {
        return ResponseEntity.ok(UserMapper.toResponse(userService.grantRole(id, role)));
    }

    @DeleteMapping("/{id}/roles/{role}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<UserResponse> revokeRole(Authentication authentication, @PathVariable UUID id,
                                                   @PathVariable RoleName role) {
        var actingUserId = UUID.fromString(authentication.getName());
        return ResponseEntity.ok(UserMapper.toResponse(userService.revokeRole(id, role, actingUserId)));
    }
}
