package dev.arsyadev.proxycoinshttp;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

public final class InternalApiServer {
    private final PluginConfig config;
    private final ProxyDatabase database;
    private final WebstoreService webstoreService;
    private final Logger logger;
    private final Gson gson = new Gson();
    private HttpServer server;

    public InternalApiServer(PluginConfig config, ProxyDatabase database, WebstoreService webstoreService, Logger logger) {
        this.config = config;
        this.database = database;
        this.webstoreService = webstoreService;
        this.logger = logger;
    }

    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(config.internalApi().host(), config.internalApi().port()), 0);
        server.createContext("/health", this::health);
        server.createContext("/internal/balance", this::balance);
        server.createContext("/internal/check-topup", this::checkTopup);
        server.createContext("/internal/refresh-player", this::refreshPlayer);
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        server.start();
    }

    public void stop() { if (server != null) server.stop(0); }

    private void health(HttpExchange exchange) throws IOException {
        JsonObject body = new JsonObject(); body.addProperty("ok", true); send(exchange, 200, body);
    }

    private void balance(HttpExchange exchange) throws IOException {
        if (!auth(exchange)) return;
        String uuid = q(exchange, "uuid");
        String name = q(exchange, "name");
        JsonObject body = new JsonObject();
        body.addProperty("ok", true);
        body.addProperty("uuid", uuid == null ? "" : uuid);
        body.addProperty("name", name == null ? "" : name);
        body.addProperty("balance", uuid == null || uuid.isBlank() ? 0L : database.getBalance(uuid, name));
        send(exchange, 200, body);
    }

    private void checkTopup(HttpExchange exchange) throws IOException {
        if (!auth(exchange)) return;
        RequestBody req = body(exchange);
        if (req.uuid().isBlank()) { error(exchange, 400, "uuid wajib diisi"); return; }
        try {
            WebstoreService.CheckResult result = webstoreService.checkForPlayer(req.uuid(), req.name());
            JsonObject body = new JsonObject();
            body.addProperty("ok", true);
            body.addProperty("uuid", req.uuid());
            body.addProperty("name", req.name());
            body.addProperty("claimedCount", result.claimedCount());
            body.addProperty("balance", result.newBalance());
            send(exchange, 200, body);
        } catch (Exception exception) {
            logger.warn("[ProxyCoinsHttpCore] Gagal check-topup internal: {}", exception.getMessage());
            error(exchange, 500, "gagal check topup");
        }
    }

    private void refreshPlayer(HttpExchange exchange) throws IOException {
        if (!auth(exchange)) return;
        RequestBody req = body(exchange);
        if (req.uuid().isBlank()) { error(exchange, 400, "uuid wajib diisi"); return; }
        database.upsertPlayer(req.uuid(), req.name());
        JsonObject body = new JsonObject();
        body.addProperty("ok", true);
        body.addProperty("uuid", req.uuid());
        body.addProperty("name", req.name());
        body.addProperty("balance", database.getBalance(req.uuid(), req.name()));
        send(exchange, 200, body);
    }

    private boolean auth(HttpExchange exchange) throws IOException {
        String value = exchange.getRequestHeaders().getFirst("X-APC-Token");
        if (value == null || !value.equals(config.internalApi().token())) { error(exchange, 401, "unauthorized"); return false; }
        return true;
    }

    private RequestBody body(HttpExchange exchange) throws IOException {
        try (InputStream input = exchange.getRequestBody()) {
            String raw = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            if (raw.isBlank()) return new RequestBody("", "");
            JsonObject root = gson.fromJson(raw, JsonObject.class);
            String uuid = root != null && root.has("uuid") ? root.get("uuid").getAsString() : "";
            String name = root != null && root.has("name") ? root.get("name").getAsString() : "";
            return new RequestBody(uuid, name);
        }
    }

    private String q(HttpExchange exchange, String key) {
        String query = exchange.getRequestURI().getRawQuery();
        if (query == null || query.isBlank()) return null;
        for (String piece : query.split("&")) {
            String[] pair = piece.split("=", 2);
            if (pair.length == 2 && pair[0].equals(key)) return java.net.URLDecoder.decode(pair[1], StandardCharsets.UTF_8);
        }
        return null;
    }

    private void error(HttpExchange exchange, int code, String message) throws IOException {
        JsonObject body = new JsonObject();
        body.addProperty("ok", false);
        body.addProperty("error", message);
        send(exchange, code, body);
    }

    private void send(HttpExchange exchange, int code, JsonObject body) throws IOException {
        byte[] payload = gson.toJson(body).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(code, payload.length);
        try (OutputStream output = exchange.getResponseBody()) { output.write(payload); }
    }

    private record RequestBody(String uuid, String name) {}
}
