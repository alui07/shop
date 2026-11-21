package dev.shop.api.web;

/** An event listener failed. It deliberately has no cause: the cause of an I/O failure can hold an address or token. */
public final class ListenerFailedException extends RuntimeException {

    public ListenerFailedException(String safeMessage) {
        super(safeMessage, null, false, false);
    }
}
