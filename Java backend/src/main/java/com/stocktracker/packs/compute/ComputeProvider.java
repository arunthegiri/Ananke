package com.stocktracker.packs.compute;

import com.stocktracker.packs.ComputeTarget;
import com.stocktracker.packs.runtime.SshCredentials;

/**
 * Where compute comes from. Today: {@link SshComputeProvider}, a machine the
 * user already has. A cloud provider (AWS, GCP) implements this too: it links
 * an account, provisions a GPU VM, and hands back an SSH-reachable
 * {@link ComputeTarget}, after which deployment is identical. None is
 * implemented yet — see the Packs build doc — and none is pretended.
 */
public interface ComputeProvider {

    String id();

    String label();

    /** True only when this provider can create (and bill for) machines itself. */
    boolean canProvision();

    /** Decrypted credentials for one remote operation on a target this provider owns. */
    SshCredentials credentials(ComputeTarget target);
}
