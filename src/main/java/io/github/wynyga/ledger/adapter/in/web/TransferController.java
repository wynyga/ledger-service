package io.github.wynyga.ledger.adapter.in.web;

import io.github.wynyga.ledger.adapter.in.web.Dtos.DepositRequest;
import io.github.wynyga.ledger.adapter.in.web.Dtos.ReconciliationResponse;
import io.github.wynyga.ledger.adapter.in.web.Dtos.TransferRequest;
import io.github.wynyga.ledger.adapter.in.web.Dtos.TransferResponse;
import io.github.wynyga.ledger.application.ReconciliationService;
import io.github.wynyga.ledger.application.TransferService;
import io.github.wynyga.ledger.application.TransferService.PostResult;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
class TransferController {
    static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    static final String REPLAYED = "Idempotent-Replayed";

    private final TransferService transfers;
    private final ReconciliationService reconciliation;

    TransferController(TransferService transfers, ReconciliationService reconciliation) {
        this.transfers = transfers;
        this.reconciliation = reconciliation;
    }

    @PostMapping("/deposits")
    ResponseEntity<TransferResponse> deposit(@RequestHeader(IDEMPOTENCY_KEY) String key,
            @Valid @RequestBody DepositRequest req) {
        return respond(transfers.deposit(key, req.accountId(), req.amount()));
    }

    @PostMapping("/transfers")
    ResponseEntity<TransferResponse> transfer(@RequestHeader(IDEMPOTENCY_KEY) String key,
            @Valid @RequestBody TransferRequest req) {
        return respond(transfers.transfer(key, req.fromAccountId(), req.toAccountId(), req.amount(), req.currency()));
    }

    @GetMapping("/transfers/{id}")
    TransferResponse get(@PathVariable UUID id) {
        return TransferResponse.of(transfers.get(id));
    }

    @GetMapping("/reconciliation")
    ReconciliationResponse reconcile() {
        return ReconciliationResponse.of(reconciliation.run());
    }

    /** 201 the first time; 200 with {@code Idempotent-Replayed: true} when the key was seen before. */
    private static ResponseEntity<TransferResponse> respond(PostResult r) {
        TransferResponse body = TransferResponse.of(r.transfer());
        if (r.replayed()) {
            return ResponseEntity.ok().header(REPLAYED, "true").body(body);
        }
        return ResponseEntity.created(URI.create("/api/v1/transfers/" + body.id())).body(body);
    }
}
