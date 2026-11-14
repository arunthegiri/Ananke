package com.stocktracker.controller;

import com.stocktracker.dto.ApiDto;
import com.stocktracker.instrument.AssetClass;
import com.stocktracker.service.InstrumentService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Locale;

/**
 * Instrument registry API (multi-asset P1, issue #8).
 *
 * <p>Keys travel as a query parameter, never a path segment: crypto keys contain
 * '/', continuous futures '!', indexes '^' — none of which survive a path.
 */
@RestController
@RequestMapping("/api/instruments")
public class InstrumentController {

    private final InstrumentService instrumentService;

    public InstrumentController(InstrumentService instrumentService) {
        this.instrumentService = instrumentService;
    }

    /** GET /api/instruments?assetClass=FUTURE — list registered instruments. */
    @GetMapping
    public ApiDto.PagedResponse<ApiDto.InstrumentResponse> list(
            @RequestParam(required = false) String assetClass) {
        AssetClass filter = null;
        if (assetClass != null && !assetClass.isBlank()) {
            try {
                filter = AssetClass.valueOf(assetClass.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Unknown assetClass: " + assetClass);
            }
        }
        return new ApiDto.PagedResponse<>(instrumentService.list(filter));
    }

    /** GET /api/instruments/lookup?key=BTC/USD — one registered instrument, 404 if absent. */
    @GetMapping("/lookup")
    public ApiDto.InstrumentResponse lookup(@RequestParam String key) {
        return instrumentService.get(key);
    }

    /** POST /api/instruments {"key":"ESZ26@CME"} — register (idempotent). */
    @PostMapping
    public ResponseEntity<ApiDto.InstrumentResponse> register(
            @Valid @RequestBody ApiDto.InstrumentRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(instrumentService.register(request.getKey()));
    }
}
