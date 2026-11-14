package com.stocktracker.controller;

import com.stocktracker.dto.ApiDto;
import com.stocktracker.service.BarService;
import com.stocktracker.service.OptionChainService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.LocalDate;

/**
 * Multi-asset market data (P2, issue #8). Keys are query parameters — see
 * InstrumentController for why they never ride in the path.
 */
@RestController
public class MarketDataController {

    private final BarService bars;
    private final OptionChainService chains;

    public MarketDataController(BarService bars, OptionChainService chains) {
        this.bars = bars;
        this.chains = chains;
    }

    /** GET /api/bars?key=BTC/USD&from=…&to=… — 1-minute bars for any instrument. */
    @GetMapping("/api/bars")
    public ApiDto.BarsResponse bars(@RequestParam String key,
                                    @RequestParam Instant from,
                                    @RequestParam Instant to) {
        return bars.getRange(key, from, to);
    }

    /** GET /api/options/expirations?underlying=AAPL */
    @GetMapping("/api/options/expirations")
    public ApiDto.OptionExpirationsResponse expirations(@RequestParam String underlying) {
        return chains.expirations(underlying);
    }

    /** GET /api/options/chain?underlying=AAPL&expiry=2026-12-18 */
    @GetMapping("/api/options/chain")
    public ApiDto.OptionChainResponse chain(
            @RequestParam String underlying,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate expiry) {
        return chains.chain(underlying, expiry);
    }
}
