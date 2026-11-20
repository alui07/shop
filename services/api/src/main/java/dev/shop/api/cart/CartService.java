package dev.shop.api.cart;

import dev.shop.api.cart.CartRepository.CartRow;
import dev.shop.api.cart.CartRepository.LineRow;
import dev.shop.api.catalog.pricing.Money;
import dev.shop.api.catalog.pricing.PricedProduct;
import dev.shop.api.catalog.pricing.ProductPricing;
import dev.shop.api.inventory.StockLevel;
import dev.shop.api.inventory.StockManagement;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Every write takes the cart row's lock first and changes the version under it, so concurrent edits queue, the line
 * limit holds, and checkout's version check sees every change.
 */
@Service
@PreAuthorize("hasRole('CUSTOMER')")
class CartService {

    private final CartRepository repository;
    private final ProductPricing pricing;
    private final StockManagement stock;
    private final Clock clock;

    CartService(CartRepository repository, ProductPricing pricing, StockManagement stock, Clock clock) {
        this.repository = repository;
        this.pricing = pricing;
        this.stock = stock;
        this.clock = clock;
    }

    /** One snapshot, so the prices and the availability shown belong together. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    Cart get(String subject) {
        return repository
                .find(subject)
                .map(cart -> view(cart.version(), repository.lines(cart.id())))
                .orElseGet(() -> view(0, List.of()));
    }

    @Transactional
    Cart putLine(String subject, String slug, int quantity) {
        PricedProduct product =
                pricing.findBySlug(slug).filter(PricedProduct::active).orElseThrow(CartProblem::productNotFound);
        OffsetDateTime now = OffsetDateTime.now(clock);
        CartRow cart = repository.lockOrCreate(subject, now);
        List<LineRow> lines = repository.lines(cart.id());
        Optional<LineRow> current =
                lines.stream().filter(line -> line.productId() == product.id()).findFirst();
        if (current.isEmpty() && lines.size() >= CartRules.MAX_LINES) {
            throw CartProblem.cartFull();
        }
        if (current.isPresent() && current.get().quantity() == quantity) {
            return view(cart.version(), lines);
        }
        repository.upsertLine(cart.id(), product.id(), quantity, now);
        repository.bumpVersion(cart.id(), now);
        return view(cart.version() + 1, repository.lines(cart.id()));
    }

    /** Idempotent and never creates a cart; a product that no longer exists or was never in the cart changes nothing. */
    @Transactional
    Cart removeLine(String subject, String slug) {
        Optional<CartRow> locked = repository.lock(subject);
        if (locked.isEmpty()) {
            return view(0, List.of());
        }
        CartRow cart = locked.get();
        boolean removed = pricing.findBySlug(slug)
                .map(product -> repository.deleteLine(cart.id(), product.id()))
                .orElse(false);
        if (!removed) {
            return view(cart.version(), repository.lines(cart.id()));
        }
        repository.bumpVersion(cart.id(), OffsetDateTime.now(clock));
        return view(cart.version() + 1, repository.lines(cart.id()));
    }

    private Cart view(long version, List<LineRow> lines) {
        List<Long> productIds = lines.stream().map(LineRow::productId).toList();
        Map<Long, PricedProduct> products = pricing.findByIds(productIds).stream()
                .collect(Collectors.toMap(PricedProduct::id, Function.identity()));
        Map<Long, StockLevel> levels = stock.levelsOf(productIds);
        List<CartLine> cartLines = lines.stream()
                .map(line -> cartLine(line, products.get(line.productId()), levels.get(line.productId())))
                .toList();
        int itemCount = cartLines.stream().mapToInt(CartLine::quantity).sum();
        long total = cartLines.stream()
                .mapToLong(line -> line.lineTotal().amountMinor())
                .sum();
        return new Cart(version, cartLines, itemCount, Money.euros(total));
    }

    private static CartLine cartLine(LineRow line, PricedProduct product, StockLevel level) {
        boolean available = product.active() && level.onHand() - level.reserved() >= line.quantity();
        return new CartLine(
                product.slug(),
                product.name(),
                product.price(),
                line.quantity(),
                Money.euros(product.price().amountMinor() * line.quantity()),
                available);
    }
}
