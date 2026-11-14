package com.stocktracker.connectors;

import java.util.List;

/**
 * One external data source (Connectors roadmap §1). A connector is a Spring bean; the
 * registry picks up every bean, so adding a source means adding one class.
 *
 * <p>A connector never stores credentials or calls the network on its own schedule: it
 * receives a {@link ConnectorContext} per call, and {@link ConnectorService} enforces linking,
 * the enabled switch, rate limits and caching around every call — for panels and agents alike.
 */
public interface Connector {

    ConnectorSpec spec();

    /** Read-only tools. Each name must start with {@code spec().id() + "_"}. */
    List<ConnectorTool> tools();

    /**
     * One cheap live call proving the credentials in {@code ctx} work. Returns a short line
     * for the UI ("Linked as …"); throws {@link ConnectorException} with a readable message otherwise.
     */
    String test(ConnectorContext ctx) throws Exception;
}
