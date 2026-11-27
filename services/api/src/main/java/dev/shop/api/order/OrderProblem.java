package dev.shop.api.order;

import dev.shop.api.web.Problems;
import dev.shop.api.web.Problems.FieldProblem;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/** The refusals checkout and the order endpoints give. None echoes request input. */
final class OrderProblem extends ErrorResponseException {

    private OrderProblem(HttpStatus status, ProblemDetail problem) {
        super(status, problem, null);
    }

    static OrderProblem orderNotFound() {
        return new OrderProblem(
                HttpStatus.NOT_FOUND, ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "Order not found"));
    }

    static OrderProblem cartChanged() {
        return conflict(Problems.CART_CHANGED, "The cart is empty or was changed", List.of());
    }

    static OrderProblem priceChanged() {
        return conflict(Problems.PRICE_CHANGED, "The total is not what the customer saw", List.of());
    }

    static OrderProblem outOfStock(List<String> productSlugs) {
        return conflict(
                Problems.OUT_OF_STOCK,
                "Some products are not available in the wanted quantity",
                fieldsFor(productSlugs, "is not available in the wanted quantity"));
    }

    static OrderProblem tooManyPendingOrders() {
        return conflict(Problems.TOO_MANY_PENDING_ORDERS, "Too many orders are awaiting payment", List.of());
    }

    static OrderProblem reservationLimit(List<String> productSlugs) {
        return conflict(
                Problems.RESERVATION_LIMIT,
                "Unpaid orders already hold too much of some products",
                fieldsFor(productSlugs, "would exceed what unpaid orders may hold"));
    }

    static OrderProblem orderState() {
        return conflict(Problems.ORDER_STATE, "The order is not awaiting payment", List.of());
    }

    private static OrderProblem conflict(String type, String detail, List<FieldProblem> errors) {
        return new OrderProblem(HttpStatus.CONFLICT, Problems.problem(HttpStatus.CONFLICT, type, detail, errors));
    }

    private static List<FieldProblem> fieldsFor(List<String> productSlugs, String message) {
        return productSlugs.stream()
                .map(slug -> new FieldProblem(slug, message))
                .toList();
    }
}
