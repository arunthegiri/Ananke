package com.stocktracker.packs;

/**
 * Runs after a Pack is removed (Packs build doc §7.4): a cloud provider terminates the VM it
 * provisioned for that Pack. Resolved lazily by {@link PackService} so providers may depend on it.
 */
public interface PackRemovalHook {
    void afterPackRemoved(Pack pack, ComputeTarget target);
}
