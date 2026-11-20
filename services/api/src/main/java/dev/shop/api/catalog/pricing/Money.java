package dev.shop.api.catalog.pricing;

public record Money(long amountMinor, String currency) {

    public static final String EUR = "EUR";

    public static Money euros(long amountMinor) {
        return new Money(amountMinor, EUR);
    }
}
