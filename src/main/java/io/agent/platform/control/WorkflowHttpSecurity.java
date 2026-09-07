/*
 * Copyright 2026 by the Agent Platform contributors.
 */
package io.agent.platform.control;

import java.net.InetAddress;
import java.net.URI;
import java.util.Locale;

/** Security policy shared by Workflow HTTP validation and execution. */
public final class WorkflowHttpSecurity {

    private WorkflowHttpSecurity() {}

    public static URI validateRuntimeUrl(String value) {
        URI uri = validateStaticUrl(value);
        if (privateNetworkAllowed()) return uri;
        try {
            for (InetAddress address : InetAddress.getAllByName(uri.getHost())) {
                if (address.isAnyLocalAddress()
                        || address.isLoopbackAddress()
                        || address.isLinkLocalAddress()
                        || address.isSiteLocalAddress()
                        || address.isMulticastAddress()) {
                    throw new IllegalArgumentException(
                            "Workflow HTTP target resolves to a private or local address");
                }
            }
        } catch (java.net.UnknownHostException error) {
            throw new IllegalArgumentException("Workflow HTTP target cannot be resolved", error);
        }
        return uri;
    }

    public static URI validateStaticUrl(String value) {
        String url = value == null ? "" : value.trim();
        if (url.contains("{{input}}") || url.contains("${input}")) {
            throw new IllegalArgumentException(
                    "Workflow HTTP URL cannot interpolate the complete runtime input");
        }
        URI uri;
        try {
            uri = URI.create(url);
        } catch (RuntimeException error) {
            throw new IllegalArgumentException("Workflow HTTP URL is invalid", error);
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            throw new IllegalArgumentException("Workflow HTTP URL must use http or https");
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new IllegalArgumentException("Workflow HTTP URL requires a host");
        }
        if (uri.getUserInfo() != null) {
            throw new IllegalArgumentException("Workflow HTTP URL cannot contain credentials");
        }
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        if (!privateNetworkAllowed()
                && ("localhost".equals(host)
                        || host.endsWith(".localhost")
                        || "0.0.0.0".equals(host)
                        || "::1".equals(host))) {
            throw new IllegalArgumentException(
                    "Workflow HTTP target cannot use a private or local address");
        }
        return uri;
    }

    public static String resolveCredential(String value) {
        String configured = value == null ? "" : value.trim();
        if (!configured.startsWith("env:")) return configured;
        String name = configured.substring(4).trim();
        if (!name.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new IllegalArgumentException("Workflow credential environment reference is invalid");
        }
        String resolved = System.getenv(name);
        if (resolved == null || resolved.isBlank()) {
            throw new IllegalArgumentException(
                    "Workflow credential environment variable is unavailable: " + name);
        }
        return resolved;
    }

    public static boolean sensitiveHeader(String name) {
        String normalized = name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
        return normalized.equals("authorization")
                || normalized.equals("proxy-authorization")
                || normalized.contains("api-key")
                || normalized.contains("apikey")
                || normalized.contains("token")
                || normalized.contains("secret");
    }

    private static boolean privateNetworkAllowed() {
        String environment = System.getenv("AGENT_PLATFORM_WORKFLOW_HTTP_ALLOW_PRIVATE_NETWORK");
        return Boolean.getBoolean("agent.platform.workflow.http.allow-private-network")
                || "true".equalsIgnoreCase(environment);
    }
}
