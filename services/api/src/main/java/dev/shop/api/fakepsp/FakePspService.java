package dev.shop.api.fakepsp;

import dev.shop.api.fakepsp.FakePspRepository.FakeRow;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The fake provider's page: read the payment, complete it once. The token is the only credential, as on a real page. */
@Service
@ConditionalOnProperty(name = "shop.payment.provider", havingValue = "fake")
class FakePspService {

    private final FakePspRepository repository;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    FakePspService(FakePspRepository repository, ApplicationEventPublisher events, Clock clock) {
        this.repository = repository;
        this.events = events;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    FakePspPayment read(String token) {
        return view(repository.findByToken(token).orElseThrow(FakePspProblem::notFound));
    }

    /** Moves a pending payment once, in the same transaction that queues the webhook. */
    @Transactional
    FakePspPayment complete(String token, FakePspOutcome outcome) {
        FakeRow row = repository.lockByToken(token).orElseThrow(FakePspProblem::notFound);
        Instant now = clock.instant();
        if (row.status() != FakePspStatus.PENDING || now.isAfter(row.expiresAt())) {
            throw FakePspProblem.state();
        }
        FakePspStatus status = FakePspStatus.valueOf(outcome.name());
        repository.complete(
                row.providerPaymentId(),
                status,
                "evt_" + UUID.randomUUID(),
                OffsetDateTime.ofInstant(now, ZoneOffset.UTC));
        events.publishEvent(new FakePaymentCompleted(UUID.randomUUID(), row.providerPaymentId()));
        return view(repository.findByToken(token).orElseThrow());
    }

    private static FakePspPayment view(FakeRow row) {
        return new FakePspPayment(
                row.amountMinor(),
                row.currency(),
                row.merchantReference(),
                row.status(),
                row.returnUrl(),
                row.expiresAt());
    }
}
