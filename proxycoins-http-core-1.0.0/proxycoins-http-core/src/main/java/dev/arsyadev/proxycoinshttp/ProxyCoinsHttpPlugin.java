package dev.arsyadev.proxycoinshttp;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.inject.Inject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import org.slf4j.Logger;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.Executors;

@Plugin(
        id = "proxycoinshttp",
        name = "ProxyCoinsHttpCore",
        version = "1.0.0",
        authors = {"ArsyaDev"}
)
public final class ProxyCoinsHttpPlugin {

    private final ProxyServer server;
    private final Logger logger;
    private final Path dataDirectory;

    private final Gson gson = new Gson();

    private ProxyDatabase database;
    private HttpClient httpClient;
    private HttpServer httpServer;

    private Config config;

    @Inject
    public ProxyCoinsHttpPlugin(ProxyServer server, Logger logger, @DataDirectory Path dataDirectory) {
        this.server = server;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe
    public void onProxyInitialize(ProxyInitializeEvent event) {
        try {
            Files.createDirectories(dataDirectory);
            writeDefaultConfigIfMissing();
            loadConfig();

            this.httpClient = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofMillis(config.webstoreTimeoutMs))
                    .build();

            this.database = new ProxyDatabase(dataDirectory.resolve(config.databaseFile));
            this.database.initialize();

            startHttpApi();

            logger.info("[ProxyCoinsHttpCore] Aktif di {}:{}.", config.apiHost, config.apiPort);
        } catch (Exception exception) {
            logger.error("[ProxyCoinsHttpCore] Gagal start plugin.", exception);
        }
    }

    @Subscribe
    public void onShutdown(ProxyShutdownEvent event) {
        if (httpServer != null) {
            httpServer.stop(0);
        }
        if (database != null) {
            database.close();
        }
    }

    @Subscribe
    public void onPostLogin(PostLoginEvent event) {
        Player player = event.getPlayer();
        database.upsertPlayer(player.getUniqueId(), player.getUsername());

        if (config.checkOnProxyLogin) {
            server.getScheduler().buildTask(this, () -> {
                try {
                    CheckTopupResult result = checkTopup(player.getUsername());
                    if (config.verbose && result.ok && result.added > 0) {
                        logger.info("[ProxyCoinsHttpCore] Login check topup {} +{} => {}", player.getUsername(), result.added, result.balance);
                    }
                } catch (Exception exception) {
                    if (config.verbose) {
                        logger.warn("[ProxyCoinsHttpCore] Gagal cek top up login untuk {}: {}", player.getUsername(), safeMessage(exception));
                    }
                }
            }).schedule();
        }
    }

    private void startHttpApi() throws IOException {
        this.httpServer = HttpServer.create(new InetSocketAddress(config.apiHost, config.apiPort), 0);
        this.httpServer.createContext("/internal/balance", this::handleBalance);
        this.httpServer.createContext("/internal/check-topup", this::handleCheckTopup);
        this.httpServer.createContext("/internal/add-balance", this::handleAddBalance);
        this.httpServer.createContext("/internal/take-balance", this::handleTakeBalance);
        this.httpServer.createContext("/internal/set-balance", this::handleSetBalance);
        this.httpServer.setExecutor(Executors.newCachedThreadPool());
        this.httpServer.start();
    }

    private void handleBalance(HttpExchange exchange) throws IOException {
        try {
            if (!authenticate(exchange)) {
                return;
            }
            if (!exchange.getRequestMethod().equalsIgnoreCase("GET")) {
                sendJson(exchange, 405, Map.of("ok", false, "error", "Method not allowed"));
                return;
            }

            Map<String, String> query = parseQuery(exchange.getRequestURI().getRawQuery());
            String player = firstNonBlank(query.get("player"), query.get("username"));
            if (blank(player)) {
                sendJson(exchange, 400, Map.of("ok", false, "error", "player wajib diisi"));
                return;
            }

            ensureKnownPlayer(player);

            long balance = database.getBalanceByName(player);
            if (balance < 0) {
                sendJson(exchange, 404, Map.of("ok", false, "error", "Player belum pernah join proxy"));
                return;
            }

            sendJson(exchange, 200, Map.of(
                    "ok", true,
                    "player", player,
                    "balance", balance
            ));
        } catch (Exception exception) {
            sendJson(exchange, 500, Map.of("ok", false, "error", safeMessage(exception)));
        }
    }

    private void handleCheckTopup(HttpExchange exchange) throws IOException {
        try {
            if (!authenticate(exchange)) {
                return;
            }
            if (!exchange.getRequestMethod().equalsIgnoreCase("POST")) {
                sendJson(exchange, 405, Map.of("ok", false, "error", "Method not allowed"));
                return;
            }

            JsonObject body = readBodyAsJson(exchange);
            String player = getString(body, "player");
            if (blank(player)) {
                sendJson(exchange, 400, Map.of("ok", false, "error", "player wajib diisi"));
                return;
            }

            CheckTopupResult result = checkTopup(player);
            if (!result.ok) {
                sendJson(exchange, 400, Map.of(
                        "ok", false,
                        "player", player,
                        "error", result.error,
                        "balance", result.balance,
                        "added", result.added
                ));
                return;
            }

            sendJson(exchange, 200, Map.of(
                    "ok", true,
                    "player", result.player,
                    "added", result.added,
                    "balance", result.balance
            ));
        } catch (Exception exception) {
            sendJson(exchange, 500, Map.of("ok", false, "error", safeMessage(exception)));
        }
    }

    private void handleAddBalance(HttpExchange exchange) throws IOException {
        handleBalanceChange(exchange, "add");
    }

    private void handleTakeBalance(HttpExchange exchange) throws IOException {
        handleBalanceChange(exchange, "take");
    }

    private void handleSetBalance(HttpExchange exchange) throws IOException {
        handleBalanceChange(exchange, "set");
    }

    private void handleBalanceChange(HttpExchange exchange, String mode) throws IOException {
        try {
            if (!authenticate(exchange)) {
                return;
            }
            if (!exchange.getRequestMethod().equalsIgnoreCase("POST")) {
                sendJson(exchange, 405, Map.of("ok", false, "error", "Method not allowed"));
                return;
            }

            JsonObject body = readBodyAsJson(exchange);
            String player = getString(body, "player");
            long amount = getLong(body, "amount");

            if (blank(player)) {
                sendJson(exchange, 400, Map.of("ok", false, "error", "player wajib diisi"));
                return;
            }

            if (amount < 0) {
                sendJson(exchange, 400, Map.of("ok", false, "error", "amount tidak boleh minus"));
                return;
            }

            ensureKnownPlayer(player);

            long balance;
            switch (mode) {
                case "add" -> balance = database.addBalanceByName(player, amount);
                case "take" -> balance = database.takeBalanceByName(player, amount);
                case "set" -> balance = database.setBalanceByName(player, amount);
                default -> throw new IllegalStateException("Mode tidak dikenal: " + mode);
            }

            if (balance < 0) {
                sendJson(exchange, 404, Map.of("ok", false, "error", "Player belum pernah join proxy"));
                return;
            }

            sendJson(exchange, 200, Map.of(
                    "ok", true,
                    "player", player,
                    "amount", amount,
                    "balance", balance,
                    "mode", mode
            ));
        } catch (Exception exception) {
            sendJson(exchange, 500, Map.of("ok", false, "error", safeMessage(exception)));
        }
    }

    private CheckTopupResult checkTopup(String player) {
        try {
            ensureKnownPlayer(player);

            if (!database.playerExistsByName(player)) {
                return new CheckTopupResult(false, player, 0L, 0L, "Player belum pernah join proxy");
            }

            String checkUrl = config.webstoreEndpoint
                    + "?action=check&username="
                    + URLEncoder.encode(player, StandardCharsets.UTF_8);

            HttpRequest request = HttpRequest.newBuilder(URI.create(checkUrl))
                    .timeout(Duration.ofMillis(config.webstoreTimeoutMs))
                    .header("User-Agent", config.webstoreUserAgent)
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                return new CheckTopupResult(false, player, 0L, database.getBalanceByName(player),
                        "HTTP " + response.statusCode());
            }

            JsonObject root = gson.fromJson(response.body(), JsonObject.class);
            if (root == null) {
                return new CheckTopupResult(false, player, 0L, database.getBalanceByName(player),
                        "JSON kosong");
            }

            long added = 0L;

            if (root.has("transactions") && root.get("transactions").isJsonArray()) {
                JsonArray array = root.getAsJsonArray("transactions");
                for (int i = 0; i < array.size(); i++) {
                    JsonObject trx = array.get(i).getAsJsonObject();
                    added += processTransaction(player, trx);
                }
            } else if (root.has("trx_id") && root.has("amount")) {
                added += processTransaction(player, root);
            }

            long balance = database.getBalanceByName(player);
            return new CheckTopupResult(true, player, added, balance, null);
        } catch (Exception exception) {
            long balance;
            try {
                balance = database.getBalanceByName(player);
            } catch (Exception ignored) {
                balance = 0L;
            }
            return new CheckTopupResult(false, player, 0L, balance, safeMessage(exception));
        }
    }

    private long processTransaction(String player, JsonObject trx) {
        String trxId = getString(trx, "trx_id");
        long amount = getLong(trx, "amount");

        if (blank(trxId) || amount <= 0) {
            return 0L;
        }

        if (database.isProcessed(trxId)) {
            return 0L;
        }

        long balance = database.addBalanceByName(player, amount);
        if (balance < 0) {
            return 0L;
        }

        database.markProcessed(trxId);

        try {
            confirmTransaction(trxId);
        } catch (Exception exception) {
            if (config.verbose) {
                logger.warn("[ProxyCoinsHttpCore] Gagal confirm transaksi {}: {}", trxId, safeMessage(exception));
            }
        }

        return amount;
    }

    private void confirmTransaction(String trxId) throws Exception {
        String confirmUrl = config.webstoreEndpoint
                + "?action=confirm&trx_id="
                + URLEncoder.encode(trxId, StandardCharsets.UTF_8);

        HttpRequest request = HttpRequest.newBuilder(URI.create(confirmUrl))
                .timeout(Duration.ofMillis(config.webstoreTimeoutMs))
                .header("User-Agent", config.webstoreUserAgent)
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (config.verbose && response.statusCode() != 200) {
            logger.warn("[ProxyCoinsHttpCore] Confirm {} gagal. HTTP {}", trxId, response.statusCode());
        }
    }

    private boolean authenticate(HttpExchange exchange) throws IOException {
        String token = exchange.getRequestHeaders().getFirst("X-APC-Token");
        if (!Objects.equals(token, config.apiToken)) {
            sendJson(exchange, 401, Map.of("ok", false, "error", "Token tidak valid"));
            return false;
        }
        return true;
    }

    private JsonObject readBodyAsJson(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        if (body.isBlank()) {
            return new JsonObject();
        }
        return gson.fromJson(body, JsonObject.class);
    }

    private void sendJson(HttpExchange exchange, int statusCode, Object payload) throws IOException {
        byte[] bytes = gson.toJson(payload).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(statusCode, bytes.length);

        try (OutputStream outputStream = exchange.getResponseBody()) {
            outputStream.write(bytes);
        }
    }

    private void ensureKnownPlayer(String playerName) {
        if (database.playerExistsByName(playerName)) {
            return;
        }

        Optional<Player> online = server.getAllPlayers().stream()
                .filter(player -> player.getUsername().equalsIgnoreCase(playerName))
                .findFirst();

        online.ifPresent(player -> database.upsertPlayer(player.getUniqueId(), player.getUsername()));
    }

    private void writeDefaultConfigIfMissing() throws IOException {
        Path configPath = dataDirectory.resolve("config.yml");
        if (Files.exists(configPath)) {
            return;
        }

        String content = """
                database:
                  file: "coins.db"

                webstore:
                  endpoint: "https://arsyanet.site/api.php"
                  user-agent: "Bedrock-Server"
                  timeout-ms: 5000
                  check-on-proxy-login: true

                internal-api:
                  host: "0.0.0.0"
                  port: 18080
                  token: "mbud"

                messages:
                  currency-name: "Arsya Premium Coin"

                logging:
                  verbose: false
                """;

        Files.writeString(configPath, content, StandardCharsets.UTF_8);
    }

    @SuppressWarnings("unchecked")
    private void loadConfig() throws IOException {
        Path configPath = dataDirectory.resolve("config.yml");
        Yaml yaml = new Yaml();
        Map<String, Object> root = yaml.load(Files.readString(configPath, StandardCharsets.UTF_8));

        Map<String, Object> databaseSection = (Map<String, Object>) root.getOrDefault("database", new HashMap<>());
        Map<String, Object> webstoreSection = (Map<String, Object>) root.getOrDefault("webstore", new HashMap<>());
        Map<String, Object> apiSection = (Map<String, Object>) root.getOrDefault("internal-api", new HashMap<>());
        Map<String, Object> messagesSection = (Map<String, Object>) root.getOrDefault("messages", new HashMap<>());
        Map<String, Object> loggingSection = (Map<String, Object>) root.getOrDefault("logging", new HashMap<>());

        this.config = new Config(
                String.valueOf(databaseSection.getOrDefault("file", "coins.db")),
                String.valueOf(webstoreSection.getOrDefault("endpoint", "https://arsyanet.site/api.php")),
                String.valueOf(webstoreSection.getOrDefault("user-agent", "Bedrock-Server")),
                Integer.parseInt(String.valueOf(webstoreSection.getOrDefault("timeout-ms", 5000))),
                Boolean.parseBoolean(String.valueOf(webstoreSection.getOrDefault("check-on-proxy-login", true))),
                String.valueOf(apiSection.getOrDefault("host", "0.0.0.0")),
                Integer.parseInt(String.valueOf(apiSection.getOrDefault("port", 18080))),
                String.valueOf(apiSection.getOrDefault("token", "mbud")),
                String.valueOf(messagesSection.getOrDefault("currency-name", "Arsya Premium Coin")),
                Boolean.parseBoolean(String.valueOf(loggingSection.getOrDefault("verbose", false)))
        );
    }

    private static Map<String, String> parseQuery(String query) {
        Map<String, String> map = new HashMap<>();
        if (query == null || query.isBlank()) {
            return map;
        }

        String[] pairs = query.split("&");
        for (String pair : pairs) {
            String[] kv = pair.split("=", 2);
            String key = urlDecode(kv[0]);
            String value = kv.length > 1 ? urlDecode(kv[1]) : "";
            map.put(key, value);
        }
        return map;
    }

    private static String urlDecode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private static String firstNonBlank(String a, String b) {
        if (!blank(a)) {
            return a;
        }
        return b;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static String getString(JsonObject object, String key) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) {
            return null;
        }
        return object.get(key).getAsString();
    }

    private static long getLong(JsonObject object, String key) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) {
            return 0L;
        }
        return object.get(key).getAsLong();
    }

    private static String safeMessage(Throwable throwable) {
        if (throwable == null) {
            return "Unknown error";
        }
        if (throwable.getMessage() == null || throwable.getMessage().isBlank()) {
            return throwable.getClass().getSimpleName();
        }
        return throwable.getMessage();
    }

    private record Config(
            String databaseFile,
            String webstoreEndpoint,
            String webstoreUserAgent,
            int webstoreTimeoutMs,
            boolean checkOnProxyLogin,
            String apiHost,
            int apiPort,
            String apiToken,
            String currencyName,
            boolean verbose
    ) {
    }

    private record CheckTopupResult(
            boolean ok,
            String player,
            long added,
            long balance,
            String error
    ) {
    }
}
