package dev.arsyadev.proxycoinshttp;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.slf4j.Logger;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

public final class WebstoreService {
    private final PluginConfig config;
    private final ProxyDatabase database;
    private final Logger logger;
    private final HttpClient httpClient = HttpClient.newBuilder().build();
    private final Gson gson = new Gson();

    public WebstoreService(PluginConfig config, ProxyDatabase database, Logger logger) {
        this.config = config;
        this.database = database;
        this.logger = logger;
    }

    public CheckResult checkForPlayer(String uuid, String username) throws IOException, InterruptedException {
        database.upsertPlayer(uuid, username);
        if (config.webstore().endpoint().isBlank()) return new CheckResult(0, database.getBalance(uuid, username));

        String url = config.webstore().endpoint() + "?action=check&username=" + URLEncoder.encode(username, StandardCharsets.UTF_8);
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", config.webstore().userAgent())
                .timeout(java.time.Duration.ofMillis(config.webstore().timeoutMs()))
                .GET().build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        JsonObject root = gson.fromJson(response.body(), JsonObject.class);
        boolean found = root != null && root.has("found") && root.get("found").getAsBoolean();
        if (!found) return new CheckResult(0, database.getBalance(uuid, username));

        JsonArray txs = root.has("transactions") && root.get("transactions").isJsonArray() ? root.getAsJsonArray("transactions") : null;
        int claimed = 0;
        if (txs != null) {
            for (var element : txs) {
                if (element.isJsonObject()) claimed += processOne(uuid, username, element.getAsJsonObject());
            }
        } else {
            claimed += processOne(uuid, username, root);
        }
        return new CheckResult(claimed, database.getBalance(uuid, username));
    }

    private int processOne(String uuid, String username, JsonObject tx) throws IOException, InterruptedException {
        if (!tx.has("trx_id") || !tx.has("amount")) return 0;
        String trxId = tx.get("trx_id").getAsString();
        long amount = tx.get("amount").getAsLong();
        if (database.isProcessed(trxId)) return 0;

        long newBalance = database.addBalance(uuid, username, amount);
        database.markProcessed(trxId);
        boolean confirmed = confirm(trxId);
        if (!confirmed) logger.warn("[ProxyCoinsHttpCore] Confirm webstore gagal untuk trx {}. Saldo tetap disimpan lokal.", trxId);
        logger.info("[ProxyCoinsHttpCore] Top up {} APC ke {}. Saldo baru: {}", amount, username, newBalance);
        return 1;
    }

    private boolean confirm(String trxId) throws IOException, InterruptedException {
        String url = config.webstore().endpoint() + "?action=confirm&trx_id=" + URLEncoder.encode(trxId, StandardCharsets.UTF_8);
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", config.webstore().userAgent())
                .timeout(java.time.Duration.ofMillis(config.webstore().timeoutMs()))
                .GET().build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        return response.statusCode() >= 200 && response.statusCode() < 300;
    }

    public record CheckResult(int claimedCount, long newBalance) {}
}
