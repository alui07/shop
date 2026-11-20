package dev.shop.api.order;

import dev.shop.api.cart.CartCheckout;
import dev.shop.api.cart.CartSnapshot;
import dev.shop.api.catalog.pricing.Money;
import dev.shop.api.catalog.pricing.PricedProduct;
import dev.shop.api.catalog.pricing.ProductPricing;
import dev.shop.api.customer.CurrentUser;
import dev.shop.api.inventory.reservations.InsufficientStockException;
import dev.shop.api.inventory.reservations.ReservationLine;
import dev.shop.api.inventory.reservations.StockReservations;
import dev.shop.api.order.IdempotencyStore.StoredKey;
import dev.shop.api.web.LockFailures;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.dao.DataAccessException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * One READ COMMITTED transaction. Locks are taken in a fixed order, so concurrent checkouts, expiry batches and admin
 * writes cannot deadlock: the idempotency row, the cart row, the product rows (share, ascending id), then the stock
 * rows (ascending product id). Any refusal rolls everything back, so a failed checkout leaves no key behind.
 */
@Service
@PreAuthorize("hasRole('CUSTOMER')")
class CheckoutService {

    private final IdempotencyStore idempotency;
    private final CartCheckout carts;
    private final ProductPricing pricing;
    private final StockReservations reservations;
    private final OrderRepository orders;
    private final CheckoutProperties properties;
    private final Clock clock;

    CheckoutService(
            IdempotencyStore idempotency,
            CartCheckout carts,
            ProductPricing pricing,
            StockReservations reservations,
            OrderRepository orders,
            CheckoutProperties properties,
            Clock clock) {
        this.idempotency = idempotency;
        this.carts = carts;
        this.pricing = pricing;
        this.reservations = reservations;
        this.orders = orders;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    CheckoutResult checkout(CurrentUser user, UUID key, CheckoutInput input) {
        String subject = user.subject();
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        String requestHash = requestHash(input);
        if (!claimKey(subject, key, requestHash, now)) {
            return new CheckoutResult(replay(subject, key, requestHash), true);
        }
        CartSnapshot cart = carts.lockForCheckout(subject)
                .filter(snapshot -> !snapshot.lines().isEmpty() && snapshot.version() == input.cartVersion())
                .orElseThrow(OrderProblem::cartChanged);
        refuseWhenCustomerHoldsTooMuch(subject, cart, now);
        Map<Long, PricedProduct> products = lockProducts(cart);
        long total = totalOf(cart, products);
        refuseInactiveOrChangedPrice(products, total, input.expectedTotalMinor());

        UUID orderId = UUID.randomUUID();
        Order order = placeOrder(orderId, user, cart, products, total, now);
        reserve(orderId, cart, products);
        carts.clear(cart.cartId());
        idempotency.complete(subject, key, orderId);
        return new CheckoutResult(order, false);
    }

    /** A second request with the same key waits on the first; if that outlasts lock_timeout, it is still in progress. */
    private boolean claimKey(String subject, UUID key, String requestHash, Instant now) {
        try {
            return idempotency.claim(subject, key, requestHash, utc(now), utc(now.plus(properties.idempotencyTtl())));
        } catch (DataAccessException failure) {
            if (LockFailures.isLockTimeout(failure)) {
                throw OrderProblem.idempotencyInProgress();
            }
            throw failure;
        }
    }

    private Order replay(String subject, UUID key, String requestHash) {
        StoredKey stored = idempotency.find(subject, key).orElseThrow();
        if (!stored.requestHash().equals(requestHash)) {
            throw OrderProblem.idempotencyKeyReused();
        }
        OrderRepository.OrderRow row =
                orders.findOwned(stored.orderId(), subject).orElseThrow();
        return new Order(
                row.id(),
                row.status().presentedAt(row.expiresAt(), clock.instant()),
                row.createdAt(),
                row.expiresAt(),
                orders.lines(row.id()),
                row.total());
    }

    private void refuseWhenCustomerHoldsTooMuch(String subject, CartSnapshot cart, Instant now) {
        OffsetDateTime at = utc(now);
        if (orders.countPending(subject, at) >= properties.maxPendingOrders()) {
            throw OrderProblem.tooManyPendingOrders();
        }
        Map<Long, Integer> held = orders.heldByProduct(subject, at, productIds(cart));
        List<Long> overLimit = cart.lines().stream()
                .filter(line ->
                        held.getOrDefault(line.productId(), 0) + line.quantity() > properties.maxReservedPerProduct())
                .map(CartSnapshot.Line::productId)
                .toList();
        if (!overLimit.isEmpty()) {
            throw OrderProblem.reservationLimit(slugsOf(overLimit));
        }
    }

    private Map<Long, PricedProduct> lockProducts(CartSnapshot cart) {
        return pricing.lockForCheckout(productIds(cart)).stream()
                .collect(Collectors.toMap(PricedProduct::id, Function.identity()));
    }

    private static long totalOf(CartSnapshot cart, Map<Long, PricedProduct> products) {
        return cart.lines().stream()
                .mapToLong(line -> products.get(line.productId()).price().amountMinor() * line.quantity())
                .sum();
    }

    private void refuseInactiveOrChangedPrice(Map<Long, PricedProduct> products, long total, long expectedTotal) {
        List<String> inactive = products.values().stream()
                .filter(product -> !product.active())
                .map(PricedProduct::slug)
                .sorted()
                .toList();
        if (!inactive.isEmpty()) {
            throw OrderProblem.outOfStock(inactive);
        }
        if (total != expectedTotal) {
            throw OrderProblem.priceChanged();
        }
    }

    private void reserve(UUID orderId, CartSnapshot cart, Map<Long, PricedProduct> products) {
        List<ReservationLine> lines = cart.lines().stream()
                .map(line -> new ReservationLine(line.productId(), line.quantity()))
                .toList();
        try {
            reservations.reserve(orderId, lines);
        } catch (InsufficientStockException shortage) {
            List<String> slugs = shortage.shortProductIds().stream()
                    .map(id -> products.get(id).slug())
                    .toList();
            throw OrderProblem.outOfStock(slugs);
        }
    }

    private Order placeOrder(
            UUID orderId,
            CurrentUser user,
            CartSnapshot cart,
            Map<Long, PricedProduct> products,
            long total,
            Instant now) {
        Instant expiresAt = now.plus(properties.reservationTtl());
        Money orderTotal = Money.euros(total);
        orders.insert(orderId, user.subject(), user.verifiedEmail(), orderTotal, utc(now), utc(expiresAt));
        List<OrderLine> lines = new ArrayList<>();
        for (CartSnapshot.Line line : cart.lines()) {
            PricedProduct product = products.get(line.productId());
            orders.insertLine(orderId, lines.size() + 1, product, line.quantity());
            lines.add(new OrderLine(
                    product.slug(),
                    product.name(),
                    product.price(),
                    line.quantity(),
                    Money.euros(product.price().amountMinor() * line.quantity())));
        }
        return new Order(orderId, OrderStatus.PENDING_PAYMENT, now, expiresAt, lines, orderTotal);
    }

    private List<String> slugsOf(Collection<Long> productIds) {
        return pricing.findByIds(productIds).stream().map(PricedProduct::slug).toList();
    }

    private static List<Long> productIds(CartSnapshot cart) {
        return cart.lines().stream().map(CartSnapshot.Line::productId).toList();
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    private static String requestHash(CheckoutInput input) {
        String canonical = "POST /api/checkout\n" + input.cartVersion() + "\n" + input.expectedTotalMinor();
        return digest(canonical, "SHA-256");
    }

    static String digest(String text, String algorithm) {
        try {
            byte[] digest = MessageDigest.getInstance(algorithm).digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException unknown) {
            throw new IllegalArgumentException("Unknown digest algorithm", unknown);
        }
    }
}
