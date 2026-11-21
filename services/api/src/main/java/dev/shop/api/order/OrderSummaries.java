package dev.shop.api.order;

import java.util.Optional;
import java.util.UUID;
import org.springframework.modulith.NamedInterface;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read-only order data for the notification module, which must not be able to change an order. */
@Service
@NamedInterface("summaries")
public class OrderSummaries {

    private final OrderRepository orders;

    OrderSummaries(OrderRepository orders) {
        this.orders = orders;
    }

    /** Empty when the order has no verified email address to write to. */
    @Transactional(readOnly = true)
    public Optional<ConfirmationDetails> confirmation(UUID orderId) {
        return orders.email(orderId).map(email -> {
            var lines = orders.lines(orderId);
            return new ConfirmationDetails(
                    orderId,
                    email,
                    lines.stream()
                            .map(line -> new ConfirmationDetails.Line(
                                    line.name(),
                                    line.quantity(),
                                    line.unitPrice().amountMinor()))
                            .toList(),
                    lines.stream()
                            .mapToLong(line -> line.lineTotal().amountMinor())
                            .sum(),
                    lines.getFirst().lineTotal().currency());
        });
    }
}
