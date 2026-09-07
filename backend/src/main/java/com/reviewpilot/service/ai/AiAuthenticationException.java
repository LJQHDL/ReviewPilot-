package com.reviewpilot.service.ai;

/** Model authentication/configuration failure, independent of provider message wording. */
public class AiAuthenticationException extends AiProviderException {
    public AiAuthenticationException(String message) { super(message); }
}
