package dev.arsyadev.proxycoinshttp;

import org.slf4j.Logger;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

public final class ConfigService {

    private final Path dataDirectory;
    private final Logger logger;

    public ConfigService(Path dataDirectory, Logger logger) {
        this.dataDirectory = dataDirectory;
        this.logger = logger;
    }

    public PluginConfig loadOrCreate() throws IOException {
        Files.createDirectories(dataDirectory);
        Path configPath = dataDirectory.resolve("config.yml");
        if (Files.notExists(configPath)) {
            try (InputStream in = getClass().getClassLoader().getResourceAsStream("config.yml")) {
                if (in == null) throw new IOException("Default config.yml tidak ditemukan.");
                Files.copy(in, configPath);
            }
        }
        Yaml yaml = new Yaml();
        try (InputStream in = Files.newInputStream(configPath)) {
            @SuppressWarnings("unchecked")
            Map<String, Object> root = yaml.load(in);
            Map<String, Object> database = map(root.get("database"));
            Map<String, Object> webstore = map(root.get("webstore"));
            Map<String, Object> internalApi = map(root.get("internal-api"));
            Map<String, Object> messages = map(root.get("messages"));
            Map<String, Object> logging = map(root.get("logging"));
            return new PluginConfig(
                    new PluginConfig.DatabaseConfig(string(database, "file", "coins.db")),
                    new PluginConfig.WebstoreConfig(
                            string(webstore, "endpoint", ""),
                            string(webstore, "user-agent", "Bedrock-Server"),
                            integer(webstore, "timeout-ms", 5000),
                            bool(webstore, "check-on-proxy-login", true)
                    ),
                    new PluginConfig.InternalApiConfig(
                            string(internalApi, "host", "127.0.0.1"),
                            integer(internalApi, "port", 37841),
                            string(internalApi, "token", "ganti-token-internal-ini")
                    ),
                    new PluginConfig.MessageConfig(string(messages, "currency-name", "Arsya Premium Coin")),
                    new PluginConfig.LoggingConfig(bool(logging, "verbose", false))
            );
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Object raw) { return raw instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of(); }
    private String string(Map<String, Object> map, String key, String fallback) {
        Object value = map.get(key); return value == null ? fallback : String.valueOf(value);
    }
    private int integer(Map<String, Object> map, String key, int fallback) {
        Object value = map.get(key);
        if (value instanceof Number number) return number.intValue();
        if (value != null) try { return Integer.parseInt(String.valueOf(value)); } catch (NumberFormatException ignored) {}
        return fallback;
    }
    private boolean bool(Map<String, Object> map, String key, boolean fallback) {
        Object value = map.get(key);
        if (value instanceof Boolean b) return b;
        if (value != null) return Boolean.parseBoolean(String.valueOf(value));
        return fallback;
    }
}
