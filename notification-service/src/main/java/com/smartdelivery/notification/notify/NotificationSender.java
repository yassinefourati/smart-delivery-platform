package com.smartdelivery.notification.notify;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The mock boundary for this service, in the same spirit as payment-service's
 * {@code MockPaymentProvider}: a real deployment would replace this with a call to an
 * email/SMS/push provider. Here it just logs -- which is also why a duplicate delivery
 * of the same Kafka event (at-least-once, see docs/kafka-events.md) is an acceptable,
 * undeduplicated outcome for this service specifically: a duplicated log line has none
 * of the correctness consequences a duplicated charge or a duplicate shipment would
 * have. Every other consumer in this platform still must (and does) tolerate
 * duplicates the way docs/saga.md describes; this one just doesn't need machinery to
 * prevent them, because nothing here is harmed by one.
 */
@Component
public class NotificationSender {

    private static final Logger log = LoggerFactory.getLogger(NotificationSender.class);

    public void send(String message) {
        log.info("[NOTIFICATION] {}", message);
    }
}
