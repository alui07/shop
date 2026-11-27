package dev.shop.api.cart;

import dev.shop.api.web.Problems;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

final class CartProblem extends ErrorResponseException {

    private CartProblem(HttpStatus status, ProblemDetail problem) {
        super(status, problem, null);
    }

    static CartProblem productNotFound() {
        return new CartProblem(
                HttpStatus.NOT_FOUND, ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "Product not found"));
    }

    static CartProblem cartFull() {
        return new CartProblem(
                HttpStatus.CONFLICT,
                Problems.problem(
                        HttpStatus.CONFLICT,
                        Problems.CART_FULL,
                        "The cart already has the most lines it can hold",
                        List.of()));
    }

    static CartProblem lineQuantityLimit(int quantityInCart) {
        ProblemDetail problem = Problems.problem(
                HttpStatus.CONFLICT,
                Problems.LINE_QUANTITY_LIMIT,
                "The line would hold more than the most allowed",
                List.of());
        problem.setProperty("quantityInCart", quantityInCart);
        problem.setProperty("maxQuantity", CartRules.MAX_QUANTITY);
        return new CartProblem(HttpStatus.CONFLICT, problem);
    }
}
