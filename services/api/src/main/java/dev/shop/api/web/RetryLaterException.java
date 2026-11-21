package dev.shop.api.web;

/** An event listener cannot finish now and wants the publication left incomplete for the retry job. */
public final class RetryLaterException extends RuntimeException {

    public RetryLaterException(String message) {
        super(message, null, false, false);
    }
}
