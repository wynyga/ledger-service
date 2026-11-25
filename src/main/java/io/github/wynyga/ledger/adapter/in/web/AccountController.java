package io.github.wynyga.ledger.adapter.in.web;

import io.github.wynyga.ledger.adapter.in.web.Dtos.AccountResponse;
import io.github.wynyga.ledger.adapter.in.web.Dtos.EntryResponse;
import io.github.wynyga.ledger.adapter.in.web.Dtos.OpenAccountRequest;
import io.github.wynyga.ledger.application.AccountService;
import io.github.wynyga.ledger.domain.Account;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "Accounts", description = "Open accounts, read balances and statements")
@RequestMapping("/api/v1/accounts")
class AccountController {
    private final AccountService accounts;

    AccountController(AccountService accounts) {
        this.accounts = accounts;
    }

    @PostMapping
    ResponseEntity<AccountResponse> open(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody OpenAccountRequest req) {
        Account a = accounts.open(Callers.from(jwt), req.currency());
        return ResponseEntity.created(URI.create("/api/v1/accounts/" + a.id())).body(AccountResponse.of(a));
    }

    @GetMapping("/{id}")
    AccountResponse get(@AuthenticationPrincipal Jwt jwt, @Parameter(example = "REPLACE-with-id-from-POST-/api/v1/accounts") @PathVariable UUID id) {
        return AccountResponse.of(accounts.get(Callers.from(jwt), id));
    }

    @GetMapping("/{id}/entries")
    List<EntryResponse> entries(@AuthenticationPrincipal Jwt jwt, @Parameter(example = "REPLACE-with-id-from-POST-/api/v1/accounts") @PathVariable UUID id,
            @RequestParam(defaultValue = "50") int limit) {
        return accounts.statement(Callers.from(jwt), id, limit).stream().map(EntryResponse::of).toList();
    }
}
