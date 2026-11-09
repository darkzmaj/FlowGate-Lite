package dev.korinetwork.flowgate.model;

/**
 * Represents the result of a routing decision made by {@link dev.korinetwork.flowgate.service.RouterService}.
 * Immutable. Use the static factory methods.
 */
public final class RoutingResult {

    private final boolean success;
    private final String serverName;
    private final String reason;

    private RoutingResult(boolean success, String serverName, String reason) {
        this.success = success;
        this.serverName = serverName;
        this.reason = reason;
    }

    public static RoutingResult success(String serverName) {
        return new RoutingResult(true, serverName, null);
    }

    public static RoutingResult failure(String reason) {
        return new RoutingResult(false, null, reason);
    }

    public boolean isSuccess() { return success; }

    /** The target server name. Non-null only when {@link #isSuccess()} is true. */
    public String getServerName() { return serverName; }

    /** Human-readable failure reason. Non-null only when {@link #isSuccess()} is false. */
    public String getReason() { return reason; }

    @Override
    public String toString() {
        return success
                ? "RoutingResult{success, server='" + serverName + "'}"
                : "RoutingResult{failure, reason='" + reason + "'}";
    }
}

