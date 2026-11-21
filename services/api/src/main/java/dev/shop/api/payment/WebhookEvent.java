package dev.shop.api.payment;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@Schema(description = "A provider event, signed with HMAC-SHA256 in the Shop-Signature header")
record WebhookEvent(
        @NotNull
        @Size(min = 1, max = WebhookEvent.MAX_ID_LENGTH)
        @Schema(description = "Unique per event; a repeated id has no further effect", example = "evt_0b1c2d3e")
        String id,

        @NotNull WebhookEventType type,

        @Schema(description = "Unix seconds; informational, the signature timestamp is authoritative")
        long created,

        @NotNull @Valid WebhookEventData data) {

    static final int MAX_ID_LENGTH = 100;
}
