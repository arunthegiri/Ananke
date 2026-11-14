package com.stocktracker.marketdata;

import com.stocktracker.instrument.AssetClass;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * Picks the provider for an asset class. Swapping Alpaca for Databento /
 * Massive / Webull later means registering another provider bean and choosing
 * it here; nothing upstream changes.
 *
 * <p>Equities are not routed through here yet — they keep their original
 * stock_prices path (StockPriceService) until that table folds into bars.
 */
@Component
public class MarketDataRouter {

    private final List<MarketData.BarProvider> barProviders;
    private final List<MarketData.OptionChainProvider> chainProviders;

    public MarketDataRouter(List<MarketData.BarProvider> barProviders,
                            List<MarketData.OptionChainProvider> chainProviders) {
        this.barProviders = barProviders;
        this.chainProviders = chainProviders;
    }

    public Optional<MarketData.BarProvider> barsFor(AssetClass assetClass) {
        return barProviders.stream().filter(p -> p.assetClasses().contains(assetClass)).findFirst();
    }

    public MarketData.OptionChainProvider chains() {
        return chainProviders.stream().findFirst().orElseThrow(() ->
                new UnsupportedOperationException("No option chain provider is configured"));
    }
}
