package com.iitm.beacon.common.error;

/**
 * Thrown when an action that must email someone couldn't send the email,
 * and so wasn't carried out (UC-REJECT-TESTIMONIAL: a testimonial is only
 * rejected once its submitter has been told). The message is a fixed text
 * of the application's own, shown to the client as is; the mail failure
 * itself is deliberately not attached as the cause — its text can name the
 * recipient. Mapped to HTTP 503 by {@link GlobalExceptionHandler}.
 */
public class NotificationNotSentException extends RuntimeException {

    public NotificationNotSentException(String message) {
        super(message);
    }
}
