package com.stocktracker.agent.model;

import com.stocktracker.service.LLMClient;

/**
 * A role's model could not be resolved or called. {@link #code} is a stable, UI-mappable
 * reason (§17.3: "Selected Pack is offline", "This model failed the tool-call probe"), and
 * the message says what to do about it. Never carries a credential.
 *
 * <p>Extends {@link LLMClient.LLMUnavailableException} so code written against the legacy
 * client (the tool loop, Copilot's 503 mapping) treats an unresolvable role as "the AI
 * provider is unusable" without knowing about bindings.
 */
public class ModelResolutionException extends LLMClient.LLMUnavailableException {

    public enum Code {
        MODEL_BINDING_REQUIRED,
        CONNECTION_NOT_FOUND,
        CONNECTION_DISABLED,
        CONNECTION_CHANGED,
        CREDENTIAL_REVOKED,
        DEPLOYMENT_UNAVAILABLE,
        DEPLOYMENT_CHANGED,
        TRUST_ZONE_NOT_PERMITTED,
        CAPABILITY_NOT_VERIFIED,
        INVALID_BINDING,
        MODEL_CALL_FAILED
    }

    private final Code code;
    private final String roleId;

    public ModelResolutionException(Code code, String roleId, String message) {
        super(message);
        this.code = code;
        this.roleId = roleId;
    }

    public Code code() {
        return code;
    }

    public String roleId() {
        return roleId;
    }
}
