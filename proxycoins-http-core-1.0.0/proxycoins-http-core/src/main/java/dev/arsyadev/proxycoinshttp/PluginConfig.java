package dev.arsyadev.proxycoinshttp;

public record PluginConfig(
        DatabaseConfig database,
        WebstoreConfig webstore,
        InternalApiConfig internalApi,
        MessageConfig messages,
        LoggingConfig logging
) {
    public record DatabaseConfig(String file) {}
    public record WebstoreConfig(String endpoint, String userAgent, int timeoutMs, boolean checkOnProxyLogin) {}
    public record InternalApiConfig(String host, int port, String token) {}
    public record MessageConfig(String currencyName) {}
    public record LoggingConfig(boolean verbose) {}
}
