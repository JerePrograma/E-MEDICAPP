package ar.emedicapp.purchase;

import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api")
public class SubstitutionController {
    private final SubstitutionService service;

    public SubstitutionController(SubstitutionService service) {
        this.service = service;
    }

    @PostMapping("/orders/{id}/substitutions")
    public Map<String, Object> propose(
            @PathVariable UUID id, @RequestBody SubstitutionService.Proposal body) {
        return service.propose(id, body);
    }

    @PostMapping("/purchases/{id}/substitutions/{proposal}")
    public Map<String, Object> respond(
            @PathVariable UUID id,
            @PathVariable UUID proposal,
            @RequestBody SubstitutionService.Response body) {
        return service.respond(id, proposal, body);
    }
}
