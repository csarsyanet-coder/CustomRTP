package dev.arsyadev.apcbridgehttp;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

public final class ProxyApiClient {
    private final ArsyaPremiumBridgeHttpPlugin plugin;
    private final BalanceCache cache;
    private final HttpClient httpClient = HttpClient.newBuilder().build();
    private final Gson gson = new Gson();

    public ProxyApiClient(ArsyaPremiumBridgeHttpPlugin plugin, BalanceCache cache) {
        this.plugin = plugin; this.cache = cache;
    }

    public void refreshBalance(java.util.UUID uuid, String name, boolean notifyPlayer) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                String url = plugin.baseUrl() + "/internal/balance?uuid=" +
                        URLEncoder.encode(uuid.toString(), StandardCharsets.UTF_8) +
                        "&name=" + URLEncoder.encode(name, StandardCharsets.UTF_8);
                HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                        .header("X-APC-Token", plugin.token())
                        .timeout(java.time.Duration.ofMillis(plugin.timeoutMs()))
                        .GET().build();
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                JsonObject root = gson.fromJson(response.body(), JsonObject.class);
                long balance = root != null && root.has("balance") ? root.get("balance").getAsLong() : 0L;
                cache.set(uuid, balance);

                if (notifyPlayer) {
                    Player player = Bukkit.getPlayer(uuid);
                    if (player != null && player.isOnline()) player.sendMessage("§aSaldo " + plugin.currencyName() + " kamu: §e" + balance);
                }
            } catch (Exception exception) {
                if (notifyPlayer) {
                    Player player = Bukkit.getPlayer(uuid);
                    if (player != null && player.isOnline()) player.sendMessage("§cGagal ambil saldo dari proxy.");
                }
                plugin.getLogger().warning("Gagal refresh balance: " + exception.getMessage());
            }
        });
    }

    public void checkTopup(java.util.UUID uuid, String name) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                JsonObject payload = new JsonObject();
                payload.addProperty("uuid", uuid.toString());
                payload.addProperty("name", name);
                HttpRequest request = HttpRequest.newBuilder(URI.create(plugin.baseUrl() + "/internal/check-topup"))
                        .header("X-APC-Token", plugin.token())
                        .header("Content-Type", "application/json")
                        .timeout(java.time.Duration.ofMillis(plugin.timeoutMs()))
                        .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(payload)))
                        .build();
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                JsonObject root = gson.fromJson(response.body(), JsonObject.class);
                long balance = root != null && root.has("balance") ? root.get("balance").getAsLong() : cache.get(uuid);
                int claimed = root != null && root.has("claimedCount") ? root.get("claimedCount").getAsInt() : 0;
                cache.set(uuid, balance);

                Player player = Bukkit.getPlayer(uuid);
                if (player != null && player.isOnline()) {
                    if (claimed > 0) player.sendMessage("§aTop up ditemukan. Saldo " + plugin.currencyName() + " sekarang: §e" + balance);
                    else player.sendMessage("§7Tidak ada top up baru. Saldo saat ini: §e" + balance);
                }
            } catch (Exception exception) {
                Player player = Bukkit.getPlayer(uuid);
                if (player != null && player.isOnline()) player.sendMessage("§cGagal cek top up. Cek console server.");
                plugin.getLogger().warning("Gagal cek top up: " + exception.getMessage());
            }
        });
    }
}
