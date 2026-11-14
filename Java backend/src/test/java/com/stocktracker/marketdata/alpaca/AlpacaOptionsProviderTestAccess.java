package com.stocktracker.marketdata.alpaca;

import com.stocktracker.instrument.InstrumentKey;
import com.stocktracker.marketdata.MarketData;

import java.net.URI;

/** Exposes package-private Alpaca helpers to tests in other packages. */
public final class AlpacaOptionsProviderTestAccess {

    private AlpacaOptionsProviderTestAccess() {}

    public static MarketData.OptionQuote toQuote(InstrumentKey.Option o,
                                                 AlpacaMarketDataDto.OptionSnapshot snap,
                                                 AlpacaMarketDataDto.OptionContract contract) {
        return AlpacaOptionsProvider.toQuote(o, snap, contract);
    }

    public static URI uri(String base, String path, Object... kv) {
        return AlpacaUris.build(base, path, AlpacaUris.params(kv));
    }
}
