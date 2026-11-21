package dev.shop.api.notification;

import dev.shop.api.notification.OrderEmails.Claim;
import dev.shop.api.order.ConfirmationDetails;
import dev.shop.api.order.OrderPaid;
import dev.shop.api.order.OrderSummaries;
import dev.shop.api.web.ListenerFailedException;
import dev.shop.api.web.RetryLaterException;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;

/**
 * Sends the confirmation once per order. The send happens outside any transaction; a claim row says who is sending.
 * At least once: a crash between the send and the claim's completion can send a second copy a minute later.
 */
@Component
class OrderConfirmationMailer {

    private static final Logger LOGGER = LoggerFactory.getLogger(OrderConfirmationMailer.class);
    private static final int MAX_ADDRESS_LENGTH = 254;

    private final OrderSummaries summaries;
    private final OrderEmails emails;
    private final JavaMailSender mailSender;
    private final String from;
    private final String publicOrigin;

    OrderConfirmationMailer(
            OrderSummaries summaries,
            OrderEmails emails,
            JavaMailSender mailSender,
            @Value("${shop.mail.from}") String from,
            @Value("${shop.auth.public-origin}") String publicOrigin) {
        this.summaries = summaries;
        this.emails = emails;
        this.mailSender = mailSender;
        this.from = from;
        this.publicOrigin = publicOrigin;
    }

    @ApplicationModuleListener(id = "notification.order-confirmation", propagation = Propagation.NOT_SUPPORTED)
    void on(OrderPaid event) {
        Optional<ConfirmationDetails> details = summaries.confirmation(event.orderId());
        if (details.isEmpty() || !isSendable(details.get().email())) {
            LOGGER.info("No confirmation email for order {}: no usable address", event.orderId());
            return;
        }
        Claim claim = emails.claim(event.orderId());
        if (claim == Claim.IN_PROGRESS) {
            throw new RetryLaterException("Confirmation for order " + event.orderId() + " is being sent");
        }
        if (claim == Claim.ALREADY_SENT) {
            LOGGER.info("Confirmation for order {} was already sent", event.orderId());
            return;
        }
        send(details.get());
    }

    private void send(ConfirmationDetails details) {
        try {
            mailSender.send(message(details));
        } catch (RuntimeException e) {
            emails.release(details.orderId());
            throw new ListenerFailedException("Confirmation for order " + details.orderId() + " failed with "
                    + e.getClass().getSimpleName());
        }
        emails.markSent(details.orderId());
    }

    private SimpleMailMessage message(ConfirmationDetails details) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(details.email());
        message.setSubject("Your order " + shortId(details.orderId()) + " is confirmed");
        message.setText("Thank you for your order.\n\n"
                + details.lines().stream()
                        .map(line -> line.quantity() + " x " + line.name() + " at "
                                + money(line.unitPriceMinor(), details.currency()))
                        .collect(Collectors.joining("\n"))
                + "\n\nTotal: " + money(details.totalMinor(), details.currency())
                + "\n\nYour order: " + publicOrigin + "/orders/" + details.orderId() + "\n");
        return message;
    }

    /** One address, no line breaks, within the length limit; anything else is not worth risking a header injection. */
    private static boolean isSendable(String email) {
        if (email.length() > MAX_ADDRESS_LENGTH || email.indexOf('\r') >= 0 || email.indexOf('\n') >= 0) {
            return false;
        }
        try {
            new InternetAddress(email, true);
            return true;
        } catch (AddressException e) {
            return false;
        }
    }

    private static String shortId(UUID orderId) {
        return orderId.toString().substring(0, 8);
    }

    private static String money(long minor, String currency) {
        return "%d.%02d %s".formatted(minor / 100, minor % 100, currency);
    }
}
