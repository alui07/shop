package dev.shop.api.web;

import java.io.IOException;

/** Thrown from the request stream once a body passes the limit while it is being read. */
class BodyTooLargeException extends IOException {

    BodyTooLargeException() {
        super("Request body is too large");
    }
}
