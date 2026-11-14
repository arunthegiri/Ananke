package com.stocktracker.agent.model;

/** Persists {@link ModelCallRecord}s. Synchronous: a call is recorded before its result is used. */
@FunctionalInterface
public interface ModelCallRecorder {
    void record(ModelCallRecord call);
}
