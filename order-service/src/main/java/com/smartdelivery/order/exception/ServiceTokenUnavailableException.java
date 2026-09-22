package com.smartdelivery.order.exception;

/**
 * user-service could not be reached, or refused this service's credentials, when
 * order-service tried to obtain the {@code SERVICE} token its saga calls need
 * (see {@link com.smartdelivery.order.client.ServiceTokenProvider}).
 *
 * A distinct type rather than a bare {@code RestClientException} for one specific
 * reason: it is raised from inside the {@code inventory-service} and
 * {@code payment-service} Resilience4j instances, but it says nothing whatsoever about
 * those services' health. Both configure it as an ignored exception for exactly the same
 * reason {@link InsufficientStockException} is ignored -- see docs/resilience.md.
 */
public class ServiceTokenUnavailableException extends RuntimeException {

    public ServiceTokenUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
