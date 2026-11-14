package com.stocktracker.connectors;

import com.fasterxml.jackson.databind.JsonNode;
import com.stocktracker.dto.ApiDto;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Settings → Connectors and the panel feed (Connectors roadmap §1). Secret field values are
 * write-only: responses carry a last-four hint, never the value.
 */
@RestController
@RequestMapping("/api/connectors")
public class ConnectorController {

    private final ConnectorService service;

    public ConnectorController(ConnectorService service) {
        this.service = service;
    }

    public record FieldView(String key, String label, String help, boolean secret, boolean optional,
                            String placeholder, boolean set, String hint) { }

    public record ToolView(String name, String description) { }

    public record ConnectorView(String id, String name, String category, String licence, String description,
                                String attribution, String homepage, List<FieldView> fields, List<String> limits,
                                List<ToolView> tools, boolean keyless, boolean linked, boolean enabled, String status,
                                Instant linkedAt, Instant lastTestAt, Boolean lastTestOk, String lastTestMessage,
                                Instant lastCallAt, Instant lastErrorAt, String lastError) { }

    public record LinkRequest(Map<String, String> values) { }

    public record ToggleRequest(Boolean enabled) { }

    @GetMapping
    public List<ConnectorView> list() {
        return service.all().stream().map(this::view).toList();
    }

    @GetMapping("/{id}")
    public ConnectorView one(@PathVariable String id) {
        return view(service.get(id));
    }

    @PutMapping("/{id}/credentials")
    public ConnectorView link(@PathVariable String id, @RequestBody LinkRequest req) {
        service.link(id, req == null ? Map.of() : req.values());
        return view(service.get(id));
    }

    @DeleteMapping("/{id}/credentials")
    public ConnectorView unlink(@PathVariable String id) {
        service.unlink(id);
        return view(service.get(id));
    }

    @PutMapping("/{id}")
    public ConnectorView toggle(@PathVariable String id, @RequestBody ToggleRequest req) {
        if (req == null || req.enabled() == null) throw new IllegalArgumentException("enabled is required");
        service.setEnabled(id, req.enabled());
        return view(service.get(id));
    }

    @PostMapping("/{id}/test")
    public ConnectorView test(@PathVariable String id) {
        service.test(id);
        return view(service.get(id));
    }

    @PostMapping("/{id}/tools/{tool}")
    public ConnectorService.Result call(@PathVariable String id, @PathVariable String tool,
                                        @RequestBody(required = false) JsonNode args) {
        return service.call(id, tool, args);
    }

    @ExceptionHandler(ConnectorException.class)
    public ResponseEntity<ApiDto.ErrorResponse> connectorError(ConnectorException e) {
        HttpStatus status = switch (e.kind()) {
            case NOT_LINKED, DISABLED -> HttpStatus.CONFLICT;
            case BAD_REQUEST -> HttpStatus.BAD_REQUEST;
            case RATE_LIMITED -> HttpStatus.TOO_MANY_REQUESTS;
            case AUTH, UPSTREAM -> HttpStatus.BAD_GATEWAY;
        };
        return ResponseEntity.status(status).body(new ApiDto.ErrorResponse(e.getMessage(), status.value()));
    }

    private ConnectorView view(Connector c) {
        ConnectorSpec s = c.spec();
        ConnectorStore.Account a = service.account(s.id());
        List<FieldView> fields = s.fields().stream().map(f -> {
            String hint = service.hint(s.id(), f).orElse(null);
            return new FieldView(f.key(), f.label(), f.help(), f.secret(), f.optional(), f.placeholder(), hint != null, hint);
        }).toList();
        List<String> limits = s.limits().stream()
                .map(l -> l.permits() + " / " + RateLimiter.human(l.window())).toList();
        List<ToolView> tools = c.tools().stream().map(t -> new ToolView(t.name(), t.description())).toList();
        return new ConnectorView(s.id(), s.name(), s.category().name(), s.licence().name(), s.description(),
                s.attribution(), s.homepage(), fields, limits, tools, s.keyless(), service.isLinked(c), a.enabled(),
                service.status(c).name(), a.linkedAt(), a.lastTestAt(), a.lastTestOk(), a.lastTestMessage(),
                a.lastCallAt(), a.lastErrorAt(), a.lastError());
    }
}
