package io.github.wynyga.ledger.adapter.in.web;

import io.github.wynyga.ledger.application.HoldService;
import io.github.wynyga.ledger.application.HoldService.HoldResult;
import io.github.wynyga.ledger.domain.Hold;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "Holds", description = "Reserve money, then capture all or part of it, or release it")
@RequestMapping("/api/v1/holds")
class HoldController {

    /** @param expiresInSeconds optional; 1 second to 30 days, default 7 days */
    record AuthorizeRequest(@Schema(example = "REPLACE-with-id-from-POST-/api/v1/accounts", description = "Your own account (the payer)") @NotNull UUID fromAccountId,
            @Schema(example = "REPLACE-with-the-shop-account-id", description = "The receiver, who will capture") @NotNull UUID toAccountId,
            @Schema(example = "50000") @Positive long amount,
            @Schema(example = "IDR") @NotBlank String currency,
            @Schema(example = "3600", description = "Optional; default 7 days") @Positive Long expiresInSeconds) {
    }

    /** @param amount optional; default is the full held amount */
    record CaptureRequest(@Schema(example = "40000", description = "Optional; default is the full held amount") @Positive Long amount) {
    }

    record HoldResponse(UUID id, String status, UUID fromAccountId, UUID toAccountId, long amount, String currency,
            Instant createdAt, Instant expiresAt, Instant settledAt, Long capturedAmount, UUID captureTransferId) {
        static HoldResponse of(Hold h) {
            return new HoldResponse(h.id(), h.status().name(), h.payerAccountId(), h.receiverAccountId(), h.amount(),
                    h.currency(), h.createdAt(), h.expiresAt(), h.settledAt(), h.capturedAmount(),
                    h.captureTransferId());
        }
    }

    private final HoldService holds;

    HoldController(HoldService holds) {
        this.holds = holds;
    }

    @PostMapping
    ResponseEntity<HoldResponse> authorize(@AuthenticationPrincipal Jwt jwt,
            @Parameter(example = "hold-1", description = TransferController.KEY_HELP)
            @RequestHeader(TransferController.IDEMPOTENCY_KEY) String key, @Valid @RequestBody AuthorizeRequest req) {
        Duration ttl = req.expiresInSeconds() == null ? null : Duration.ofSeconds(req.expiresInSeconds());
        HoldResult r = holds.authorize(Callers.from(jwt), key, req.fromAccountId(), req.toAccountId(), req.amount(),
                req.currency(), ttl);
        HoldResponse body = HoldResponse.of(r.hold());
        if (r.replayed()) {
            return ResponseEntity.ok().header(TransferController.REPLAYED, "true").body(body);
        }
        return ResponseEntity.created(URI.create("/api/v1/holds/" + body.id())).body(body);
    }

    @GetMapping("/{id}")
    HoldResponse get(@AuthenticationPrincipal Jwt jwt, @Parameter(example = "REPLACE-with-id-from-POST-/api/v1/holds") @PathVariable UUID id) {
        return HoldResponse.of(holds.get(Callers.from(jwt), id));
    }

    @PostMapping("/{id}/capture")
    ResponseEntity<HoldResponse> capture(@AuthenticationPrincipal Jwt jwt,
            @Parameter(example = "REPLACE-with-id-from-POST-/api/v1/holds") @PathVariable UUID id,
            @Valid @RequestBody(required = false) CaptureRequest req) {
        return settled(holds.capture(Callers.from(jwt), id, req == null ? null : req.amount()));
    }

    @PostMapping("/{id}/release")
    ResponseEntity<HoldResponse> release(@AuthenticationPrincipal Jwt jwt,
            @Parameter(example = "REPLACE-with-id-from-POST-/api/v1/holds") @PathVariable UUID id) {
        return settled(holds.release(Callers.from(jwt), id));
    }

    private static ResponseEntity<HoldResponse> settled(HoldResult r) {
        var ok = ResponseEntity.ok();
        if (r.replayed()) {
            ok.header(TransferController.REPLAYED, "true");
        }
        return ok.body(HoldResponse.of(r.hold()));
    }
}
