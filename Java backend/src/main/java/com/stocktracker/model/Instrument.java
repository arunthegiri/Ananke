package com.stocktracker.model;

import com.stocktracker.instrument.AssetClass;
import com.stocktracker.instrument.SessionCalendar;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** Maps to {@code instruments} (V9) — one row per instrument of any asset class. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "instruments")
public class Instrument {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 40)
    private String key;

    @Enumerated(EnumType.STRING)
    @Column(name = "asset_class", nullable = false, length = 20)
    private AssetClass assetClass;

    @Column(nullable = false, length = 40)
    private String symbol;

    @Column(length = 200)
    private String name;

    @Column(length = 16)
    private String exchange;

    @Column(nullable = false, length = 8)
    @Builder.Default
    private String currency = "USD";

    @Column(nullable = false, precision = 18, scale = 6)
    @Builder.Default
    private BigDecimal multiplier = BigDecimal.ONE;

    @Column(name = "tick_size", precision = 18, scale = 8)
    private BigDecimal tickSize;

    @Enumerated(EnumType.STRING)
    @Column(name = "session_calendar", nullable = false, length = 16)
    private SessionCalendar sessionCalendar;

    @Column(name = "underlying_id")
    private Long underlyingId;

    @Column(name = "option_expiry")
    private LocalDate optionExpiry;

    @Column(name = "option_right", length = 1)
    private String optionRight;

    @Column(name = "option_strike", precision = 18, scale = 3)
    private BigDecimal optionStrike;

    @Column(name = "future_root", length = 8)
    private String futureRoot;

    @Column(name = "future_contract_month", length = 7)
    private String futureContractMonth;

    @Column(name = "future_last_trade")
    private LocalDate futureLastTrade;

    @Column(name = "crypto_base", length = 10)
    private String cryptoBase;

    @Column(name = "crypto_quote", length = 6)
    private String cryptoQuote;

    @Column(nullable = false)
    @Builder.Default
    private boolean active = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();
}
