package dev.shop.api.payment;

import dev.shop.api.order.OrderPayments;
import dev.shop.api.order.PaymentAmount;
import dev.shop.api.order.PaymentDecision;
import dev.shop.api.order.RefundRequested;
import dev.shop.api.payment.PaymentRepository.PaymentRow;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * One verified event, in one transaction. Lock order: the order, then the payment. The event id is recorded first, so
 * a repeated event changes nothing; an event no payment matches rolls that record back so the provider retries.
 */
@Component
class WebhookProcessor {

    private static final Logger LOGGER = LoggerFactory.getLogger(WebhookProcessor.class);

    private final PaymentRepository payments;
    private final OrderPayments orders;
    private final UnmatchedWebhooks unmatched;
    private final PaymentProperties properties;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    WebhookProcessor(
            PaymentRepository payments,
            OrderPayments orders,
            UnmatchedWebhooks unmatched,
            PaymentProperties properties,
            ApplicationEventPublisher events,
            Clock clock) {
        this.payments = payments;
        this.orders = orders;
        this.unmatched = unmatched;
        this.properties = properties;
        this.events = events;
        this.clock = clock;
    }

    @Transactional
    void process(WebhookEvent event) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        if (!payments.insertWebhookEvent(event.id(), event.type().wireName(), now)) {
            LOGGER.info("Webhook event already processed");
            return;
        }
        WebhookEventData data = event.data();
        PaymentRow found = locate(event, data);
        orders.lock(found.orderId());
        PaymentRow payment = payments.lockById(found.id()).orElseThrow();
        if (payment.providerPaymentId() == null) {
            payments.adoptProviderPaymentId(payment.id(), data.providerPaymentId());
        } else if (!payment.providerPaymentId().equals(data.providerPaymentId())) {
            refundSeparateCapture(event, payment, now);
            return;
        }
        if (event.type() == WebhookEventType.PAYMENT_SUCCEEDED) {
            succeeded(payment, data, now);
        } else {
            failed(payment, now);
        }
    }

    private PaymentRow locate(WebhookEvent event, WebhookEventData data) {
        return payments.findByProviderPaymentId(data.providerPaymentId())
                .or(() -> payments.findById(data.merchantReference()))
                .orElseThrow(() -> {
                    unmatched.record(event.id(), data.providerPaymentId());
                    return WebhookProblem.unknownPayment();
                });
    }

    /** The provider took money for the merchant reference a second time: the order stays as it is and the money goes back. */
    private void refundSeparateCapture(WebhookEvent event, PaymentRow payment, OffsetDateTime now) {
        if (event.type() == WebhookEventType.PAYMENT_FAILED) {
            LOGGER.info("Ignoring a failure for a separate capture");
            return;
        }
        WebhookEventData data = event.data();
        UUID refundId = UUID.randomUUID();
        payments.insertRefundPending(
                refundId,
                payment.orderId(),
                properties.provider(),
                data.providerPaymentId(),
                data.amountMinor(),
                data.currency(),
                now);
        events.publishEvent(new RefundRequested(UUID.randomUUID(), payment.orderId(), refundId));
    }

    private void succeeded(PaymentRow payment, WebhookEventData data, OffsetDateTime now) {
        if (PaymentStateMachine.next(payment.status(), PaymentEvent.SUCCESS).isEmpty()) {
            LOGGER.info("Success for a payment that is already settled");
            return;
        }
        payments.setReceived(payment.id(), data.amountMinor(), data.currency());
        payments.updateStatus(payment.id(), PaymentStatus.SUCCEEDED, now);
        PaymentDecision decision = orders.paymentSucceeded(
                payment.orderId(), payment.id(), new PaymentAmount(data.amountMinor(), data.currency()));
        if (decision == PaymentDecision.REFUND) {
            payments.updateStatus(payment.id(), PaymentStatus.REFUND_PENDING, now);
        }
    }

    private void failed(PaymentRow payment, OffsetDateTime now) {
        if (PaymentStateMachine.next(payment.status(), PaymentEvent.FAILURE).isEmpty()) {
            LOGGER.info("Failure for a payment that is already settled");
            return;
        }
        payments.updateStatus(payment.id(), PaymentStatus.FAILED, now);
        orders.paymentFailed(payment.orderId(), payment.id());
    }
}
