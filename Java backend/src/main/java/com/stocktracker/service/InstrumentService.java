package com.stocktracker.service;

import com.stocktracker.dto.ApiDto;
import com.stocktracker.instrument.AssetClass;
import com.stocktracker.instrument.FuturesSpecs;
import com.stocktracker.instrument.InstrumentKey;
import com.stocktracker.instrument.SessionCalendar;
import com.stocktracker.model.Instrument;
import com.stocktracker.repository.InstrumentRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * Instrument registry (multi-asset P1, issue #8).
 *
 * <p>{@link #register} turns a canonical key into a fully-specified row, deriving
 * everything the key encodes (option expiry/strike, futures month, crypto pair)
 * and looking up what it doesn't (futures multiplier and tick from
 * {@link FuturesSpecs}). It is idempotent: registering an existing key returns
 * the existing row unchanged.
 */
@Slf4j
@Service
@Transactional
public class InstrumentService {

    /** Equity options multiplier (standard OCC contract = 100 shares). */
    private static final BigDecimal OPTION_MULTIPLIER = new BigDecimal("100");
    private static final BigDecimal PENNY = new BigDecimal("0.01");

    /**
     * Option roots whose underlying is a cash index, not a stock. Without this,
     * registering an SPX option would invent an equity called "SPX".
     */
    private static final Map<String, String> INDEX_OPTION_UNDERLYING = Map.of(
            "SPX", "^SPX", "SPXW", "^SPX", "XSP", "^XSP",
            "NDX", "^NDX", "NDXP", "^NDX", "RUT", "^RUT",
            "VIX", "^VIX", "VIXW", "^VIX", "DJX", "^DJX");

    private final InstrumentRepository repo;

    public InstrumentService(InstrumentRepository repo) {
        this.repo = repo;
    }

    @Transactional(readOnly = true)
    public List<ApiDto.InstrumentResponse> list(AssetClass assetClass) {
        List<Instrument> rows = assetClass == null
                ? repo.findAllByOrderByKeyAsc()
                : repo.findAllByAssetClassOrderByKeyAsc(assetClass);
        return rows.stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public ApiDto.InstrumentResponse get(String rawKey) {
        String key = InstrumentKey.parseOrThrow(rawKey).key();
        return repo.findByKey(key).map(this::toResponse)
                .orElseThrow(() -> new NoSuchElementException("Instrument not registered: " + key));
    }

    public ApiDto.InstrumentResponse register(String rawKey) {
        return toResponse(registerEntity(InstrumentKey.parseOrThrow(rawKey)));
    }

    /** Called when a symbol is tracked, so every tracked equity has an instrument row. */
    public void ensureEquity(String symbol) {
        InstrumentKey.parse(symbol).ifPresent(this::registerEntity);
    }

    private Instrument registerEntity(InstrumentKey parsed) {
        return repo.findByKey(parsed.key()).orElseGet(() -> {
            Instrument created = repo.save(build(parsed));
            log.info("Registered instrument {} ({})", created.getKey(), created.getAssetClass());
            return created;
        });
    }

    private Instrument build(InstrumentKey parsed) {
        if (parsed instanceof InstrumentKey.Equity e) {
            return base(e.key(), AssetClass.EQUITY, e.key()).tickSize(PENNY).build();
        }
        if (parsed instanceof InstrumentKey.Index i) {
            return base(i.key(), AssetClass.INDEX, i.symbol()).build();
        }
        if (parsed instanceof InstrumentKey.Option o) {
            String underlyingKey = INDEX_OPTION_UNDERLYING.getOrDefault(o.underlying(), o.underlying());
            Instrument underlying = registerEntity(InstrumentKey.parseOrThrow(underlyingKey));
            return base(o.key(), AssetClass.OPTION, o.underlying())
                    .multiplier(OPTION_MULTIPLIER)
                    .underlyingId(underlying.getId())
                    .optionExpiry(o.expiry())
                    .optionRight(String.valueOf(o.right()))
                    .optionStrike(o.strike())
                    .build();
        }
        if (parsed instanceof InstrumentKey.Future f) {
            FuturesSpecs spec = specFor(f.root());
            if (!spec.exchange().equals(f.exchange())) {
                throw new IllegalArgumentException(f.root() + " futures list on " + spec.exchange()
                        + ", not " + f.exchange() + " (expected " + InstrumentKey.futureKey(
                                f.root(), f.month(), f.year(), spec.exchange()) + ")");
            }
            return base(f.key(), AssetClass.FUTURE, f.root())
                    .name(spec.name())
                    .exchange(spec.exchange())
                    .multiplier(spec.multiplier())
                    .tickSize(spec.tickSize())
                    .futureRoot(f.root())
                    .futureContractMonth(f.contractMonth())
                    .build();
        }
        if (parsed instanceof InstrumentKey.ContinuousFuture c) {
            FuturesSpecs spec = specFor(c.root());
            return base(c.key(), AssetClass.FUTURE, c.root())
                    .name(spec.name() + " (continuous #" + c.rank() + ")")
                    .exchange(spec.exchange())
                    .multiplier(spec.multiplier())
                    .tickSize(spec.tickSize())
                    .futureRoot(c.root())
                    .build();
        }
        if (parsed instanceof InstrumentKey.Crypto c) {
            return base(c.key(), AssetClass.CRYPTO, c.key())
                    .currency(c.quote())
                    .cryptoBase(c.base())
                    .cryptoQuote(c.quote())
                    .build();
        }
        throw new IllegalStateException("Unhandled key kind: " + parsed);
    }

    private static FuturesSpecs specFor(String root) {
        return FuturesSpecs.forRoot(root).orElseThrow(() -> new IllegalArgumentException(
                "Unknown futures root '" + root + "': add its contract spec to FuturesSpecs before trading it"));
    }

    private static Instrument.InstrumentBuilder base(String key, AssetClass assetClass, String symbol) {
        return Instrument.builder()
                .key(key)
                .assetClass(assetClass)
                .symbol(symbol)
                .sessionCalendar(SessionCalendar.forAssetClass(assetClass));
    }

    /** Drop DB-scale padding without going to exponent form (50, not 5E+1 or 50.000000). */
    private static BigDecimal plain(BigDecimal v) {
        if (v == null) return null;
        BigDecimal s = v.stripTrailingZeros();
        return s.scale() < 0 ? s.setScale(0) : s;
    }

    private ApiDto.InstrumentResponse toResponse(Instrument i) {
        ApiDto.InstrumentResponse r = new ApiDto.InstrumentResponse();
        r.setId(i.getId());
        r.setKey(i.getKey());
        r.setAssetClass(i.getAssetClass().name());
        r.setSymbol(i.getSymbol());
        r.setName(i.getName());
        r.setExchange(i.getExchange());
        r.setCurrency(i.getCurrency());
        r.setMultiplier(plain(i.getMultiplier()));
        r.setTickSize(plain(i.getTickSize()));
        r.setSessionCalendar(i.getSessionCalendar().name());
        r.setUnderlyingKey(i.getUnderlyingId() == null ? null
                : repo.findById(i.getUnderlyingId()).map(Instrument::getKey).orElse(null));
        if (i.getOptionExpiry() != null) {
            r.setOption(new ApiDto.InstrumentResponse.OptionSpec(
                    i.getOptionExpiry().toString(), i.getOptionRight(), plain(i.getOptionStrike())));
        }
        if (i.getFutureRoot() != null) {
            r.setFuture(new ApiDto.InstrumentResponse.FutureSpec(
                    i.getFutureRoot(), i.getFutureContractMonth(),
                    i.getFutureLastTrade() == null ? null : i.getFutureLastTrade().toString()));
        }
        if (i.getCryptoBase() != null) {
            r.setCrypto(new ApiDto.InstrumentResponse.CryptoSpec(i.getCryptoBase(), i.getCryptoQuote()));
        }
        r.setActive(i.isActive());
        return r;
    }
}
