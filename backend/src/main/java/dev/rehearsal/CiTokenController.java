package dev.rehearsal;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;

@RestController
@RequestMapping("/api/projects/{id}/ci-token")
class CiTokenController {
    record Issue(@NotNull @Positive Long baselineId) {}

    private final CiTokens tokens;

    CiTokenController(CiTokens tokens) { this.tokens=tokens; }

    @GetMapping
    CiTokens.View settings(Principal principal,@PathVariable long id) {
        return tokens.settings(id,principal.getName());
    }

    @PostMapping
    CiTokens.View issue(Principal principal,@PathVariable long id,@Valid @RequestBody Issue request) {
        return tokens.issue(id,request.baselineId(),principal.getName());
    }

    @DeleteMapping @ResponseStatus(HttpStatus.NO_CONTENT)
    void revoke(Principal principal,@PathVariable long id) {
        tokens.revoke(id,principal.getName());
    }
}
