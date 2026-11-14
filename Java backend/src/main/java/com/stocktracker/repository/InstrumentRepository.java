package com.stocktracker.repository;

import com.stocktracker.instrument.AssetClass;
import com.stocktracker.model.Instrument;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface InstrumentRepository extends JpaRepository<Instrument, Long> {

    Optional<Instrument> findByKey(String key);

    List<Instrument> findAllByOrderByKeyAsc();

    List<Instrument> findAllByAssetClassOrderByKeyAsc(AssetClass assetClass);

    List<Instrument> findAllByUnderlyingIdOrderByKeyAsc(Long underlyingId);
}
