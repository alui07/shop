package dev.shop.api.catalog;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

class ProductNotFoundException extends ErrorResponseException {

    ProductNotFoundException(String slug) {
        super(
                HttpStatus.NOT_FOUND,
                ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "No product with slug '" + slug + "'"),
                null);
    }
}
