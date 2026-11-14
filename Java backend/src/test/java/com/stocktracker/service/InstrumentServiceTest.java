package com.stocktracker.service;

import com.stocktracker.dto.ApiDto;
import com.stocktracker.model.Instrument;
import com.stocktracker.repository.InstrumentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** InstrumentService derivation rules, against an in-memory repository. */
class InstrumentServiceTest {

    private final Map<String, Instrument> byKey = new HashMap<>();
    private final Map<Long, Instrument> byId = new HashMap<>();
    private final AtomicLong ids = new AtomicLong();
    private InstrumentService service;

    @BeforeEach
    void setUp() {
        InstrumentRepository repo = mock(InstrumentRepository.class);
        when(repo.findByKey(anyString())).thenAnswer(inv -> Optional.ofNullable(byKey.get(inv.<String>getArgument(0))));
        when(repo.findById(anyLong())).thenAnswer(inv -> Optional.ofNullable(byId.get(inv.<Long>getArgument(0))));
        when(repo.save(any(Instrument.class))).thenAnswer(inv -> {
            Instrument i = inv.getArgument(0);
            if (i.getId() == null) i.setId(ids.incrementAndGet());
            byKey.put(i.getKey(), i);
            byId.put(i.getId(), i);
            return i;
        });
        service = new InstrumentService(repo);
    }

    @Test
    void equity() {
        ApiDto.InstrumentResponse r = service.register("aapl");
        assertThat(r.getKey()).isEqualTo("AAPL");
        assertThat(r.getAssetClass()).isEqualTo("EQUITY");
        assertThat(r.getMultiplier()).isEqualByComparingTo("1");
        assertThat(r.getSessionCalendar()).isEqualTo("US_EQUITY");
    }

    @Test
    void registerIsIdempotent() {
        Long first = service.register("AAPL").getId();
        assertThat(service.register(" aapl ").getId()).isEqualTo(first);
        assertThat(byKey).hasSize(1);
    }

    @Test
    void equityOptionRegistersUnderlying() {
        ApiDto.InstrumentResponse r = service.register("AAPL  261218C00200000");
        assertThat(r.getAssetClass()).isEqualTo("OPTION");
        assertThat(r.getMultiplier()).isEqualByComparingTo("100");
        assertThat(r.getUnderlyingKey()).isEqualTo("AAPL");
        assertThat(r.getOption().expiry()).isEqualTo("2026-12-18");
        assertThat(r.getOption().right()).isEqualTo("C");
        assertThat(r.getOption().strike()).isEqualByComparingTo("200");
        assertThat(byKey.get("AAPL").getAssetClass().name()).isEqualTo("EQUITY");
    }

    @Test
    void indexOptionUnderlyingIsTheIndexNotAFakeStock() {
        ApiDto.InstrumentResponse r = service.register("SPXW261218P05000000");
        assertThat(r.getUnderlyingKey()).isEqualTo("^SPX");
        assertThat(byKey).doesNotContainKey("SPXW").doesNotContainKey("SPX");
        assertThat(byKey.get("^SPX").getAssetClass().name()).isEqualTo("INDEX");
    }

    @Test
    void futureUsesContractSpec() {
        ApiDto.InstrumentResponse r = service.register("ESZ26@CME");
        assertThat(r.getAssetClass()).isEqualTo("FUTURE");
        assertThat(r.getMultiplier()).isEqualByComparingTo("50");
        assertThat(r.getTickSize()).isEqualByComparingTo("0.25");
        assertThat(r.getExchange()).isEqualTo("CME");
        assertThat(r.getSessionCalendar()).isEqualTo("CME_GLOBEX");
        assertThat(r.getFuture().root()).isEqualTo("ES");
        assertThat(r.getFuture().contractMonth()).isEqualTo("2026-12");
    }

    @Test
    void continuousFuture() {
        ApiDto.InstrumentResponse r = service.register("MNQ1!@CME");
        assertThat(r.getKey()).isEqualTo("MNQ1!");
        assertThat(r.getMultiplier()).isEqualByComparingTo("2");
        assertThat(r.getFuture().contractMonth()).isNull();
    }

    @Test
    void unknownFuturesRootIsRefusedNotDefaulted() {
        assertThatThrownBy(() -> service.register("ZZZZ26@CME"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown futures root");
        assertThat(byKey).isEmpty();
    }

    @Test
    void futureOnWrongExchangeIsRefused() {
        assertThatThrownBy(() -> service.register("GCZ26@CME"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("GCZ26@COMEX");
    }

    @Test
    void crypto() {
        ApiDto.InstrumentResponse r = service.register("btc/usd");
        assertThat(r.getAssetClass()).isEqualTo("CRYPTO");
        assertThat(r.getSessionCalendar()).isEqualTo("CRYPTO_24_7");
        assertThat(r.getCrypto().base()).isEqualTo("BTC");
        assertThat(r.getCurrency()).isEqualTo("USD");
    }

    @Test
    void lookupOfUnregisteredKeyIs404AndInvalidKeyIs400() {
        assertThatThrownBy(() -> service.get("MSFT")).isInstanceOf(NoSuchElementException.class);
        assertThatThrownBy(() -> service.get("ESZ26")).isInstanceOf(IllegalArgumentException.class);
    }
}
