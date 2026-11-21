package dev.shop.api.payment;

import dev.shop.api.customer.CurrentUser;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Starts a payment: no transaction spans the provider call, and the call is safe to repeat for the same payment. */
@Service
class PaymentService {

    private final PaymentOpening opening;
    private final PaymentProvider provider;

    PaymentService(PaymentOpening opening, PaymentProvider provider) {
        this.opening = opening;
        this.provider = provider;
    }

    PaymentStart start(CurrentUser user, UUID orderId) {
        OpenPayment open = opening.open(user.subject(), user.verifiedEmail(), orderId);
        String redirectUrl = open.payment().redirectUrl();
        if (redirectUrl == null) {
            ProviderSession session = provider.createPayment(
                    open.payment().id(),
                    open.order().amount().amountMinor(),
                    open.order().amount().currency(),
                    open.order().returnPath(),
                    open.order().holdExpiresAt());
            opening.attach(open.payment().id(), session);
            redirectUrl = session.redirectUrl();
        }
        return new PaymentStart(redirectUrl);
    }
}
