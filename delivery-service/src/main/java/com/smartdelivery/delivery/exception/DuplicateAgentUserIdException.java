package com.smartdelivery.delivery.exception;

import java.util.UUID;

public class DuplicateAgentUserIdException extends RuntimeException {

    public DuplicateAgentUserIdException(UUID userId) {
        super("A delivery agent is already linked to user " + userId);
    }
}
