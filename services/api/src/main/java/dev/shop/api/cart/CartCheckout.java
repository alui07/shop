package dev.shop.api.cart;

import dev.shop.api.cart.CartRepository.CartRow;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** What the order module may do with a cart: lock it for a checkout, and empty it when the order is placed. */
@Service
@PreAuthorize("hasRole('CUSTOMER')")
public class CartCheckout {

    private final CartRepository repository;
    private final Clock clock;

    CartCheckout(CartRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    /** Empty when the customer has no cart. The row lock also serialises one customer's checkouts. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<CartSnapshot> lockForCheckout(String subject) {
        return repository.lock(subject).map(this::snapshot);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void clear(long cartId) {
        repository.deleteLines(cartId);
        repository.bumpVersion(cartId, OffsetDateTime.now(clock));
    }

    private CartSnapshot snapshot(CartRow cart) {
        List<CartSnapshot.Line> lines = repository.linesByProduct(cart.id()).stream()
                .map(line -> new CartSnapshot.Line(line.productId(), line.quantity()))
                .toList();
        return new CartSnapshot(cart.id(), cart.version(), lines);
    }
}
