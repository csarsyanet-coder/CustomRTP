package dev.arsyadev.apcbridgehttp;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class ArsyaPremiumBridgeHttpPlugin extends JavaPlugin implements Listener, TabExecutor {

    private final Map<UUID, Long> balanceCache = new ConcurrentHashMap<>();
    private final Gson gson = new Gson();

    private HttpClient httpClient;
    private String baseUrl;
    private String token;
    private int timeoutMs;
    private String currencyName;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        reloadLocalConfig();

        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(timeoutMs))
                .build();

        if (getCommand("c") != null) {
            getCommand("c").setExecutor(this);
            getCommand("c").setTabCompleter(this);
        }

        getServer().getPluginManager().registerEvents(this, this);

        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            new ApcPlaceholderExpansion(this).register();
            getLogger().info("Placeholder %apc_balance% dan %apc_saldo% aktif.");
        }

        getLogger().info("ArsyaPremiumBridgeHttp aktif.");
    }

    @Override
    public void onDisable() {
        balanceCache.clear();
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTaskLaterAsynchronously(this, () -> refreshBalance(player.getName(), false, null), 20L);
    }

    public long getCachedBalance(UUID uuid) {
        return balanceCache.getOrDefault(uuid, 0L);
    }

    private void setCachedBalance(String playerName, long balance) {
        Player online = findOnlinePlayer(playerName);
        if (online != null) {
            balanceCache.put(online.getUniqueId(), balance);
        }
    }

    private Player findOnlinePlayer(String name) {
        return Bukkit.getOnlinePlayers().stream()
                .filter(player -> player.getName().equalsIgnoreCase(name))
                .findFirst()
                .orElse(null);
    }

    private void reloadLocalConfig() {
        reloadConfig();
        this.baseUrl = Objects.requireNonNullElse(getConfig().getString("proxy-api.base-url"), "http://127.0.0.1:18080");
        this.token = Objects.requireNonNullElse(getConfig().getString("proxy-api.token"), "mbud");
        this.timeoutMs = getConfig().getInt("proxy-api.timeout-ms", 5000);
        this.currencyName = Objects.requireNonNullElse(getConfig().getString("messages.currency-name"), "Arsya Premium Coin");

        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);

        switch (sub) {
            case "saldo" -> {
                if (!has(sender, "arsyacoin.command.saldo")) {
                    sender.sendMessage("§cKamu tidak punya izin.");
                    return true;
                }

                String target;
                boolean console = !(sender instanceof Player);

                if (args.length >= 2) {
                    if (!console && !has(sender, "arsyacoin.command.check")) {
                        sender.sendMessage("§cKamu tidak punya izin cek saldo player lain.");
                        return true;
                    }
                    target = args[1];
                } else {
                    if (console) {
                        sender.sendMessage("§cGunakan /c saldo <player> dari console.");
                        return true;
                    }
                    target = ((Player) sender).getName();
                }

                Bukkit.getScheduler().runTaskAsynchronously(this, () -> refreshBalance(target, true, sender));
                return true;
            }

            case "check" -> {
                if (!has(sender, "arsyacoin.command.check")) {
                    sender.sendMessage("§cKamu tidak punya izin.");
                    return true;
                }

                if (args.length < 2) {
                    sender.sendMessage("§eGunakan: /c check <player>");
                    return true;
                }

                String target = args[1];
                Bukkit.getScheduler().runTaskAsynchronously(this, () -> refreshBalance(target, true, sender));
                return true;
            }

            case "refresh" -> {
                if (!has(sender, "arsyacoin.command.refresh")) {
                    sender.sendMessage("§cKamu tidak punya izin.");
                    return true;
                }

                String target;
                boolean console = !(sender instanceof Player);

                if (args.length >= 2) {
                    if (!console && !has(sender, "arsyacoin.command.check")) {
                        sender.sendMessage("§cKamu tidak punya izin refresh player lain.");
                        return true;
                    }
                    target = args[1];
                } else {
                    if (console) {
                        sender.sendMessage("§cGunakan /c refresh <player> dari console.");
                        return true;
                    }
                    target = ((Player) sender).getName();
                }

                Bukkit.getScheduler().runTaskAsynchronously(this, () -> refreshBalance(target, true, sender));
                return true;
            }

            case "cektopup" -> {
                if (!has(sender, "arsyacoin.command.cektopup")) {
                    sender.sendMessage("§cKamu tidak punya izin.");
                    return true;
                }

                String target;
                boolean console = !(sender instanceof Player);

                if (args.length >= 2) {
                    if (!console && !has(sender, "arsyacoin.command.check")) {
                        sender.sendMessage("§cKamu tidak punya izin cek top up player lain.");
                        return true;
                    }
                    target = args[1];
                } else {
                    if (console) {
                        sender.sendMessage("§cGunakan /c cektopup <player> dari console.");
                        return true;
                    }
                    target = ((Player) sender).getName();
                }

                sender.sendMessage("§7Memeriksa top up dari proxy...");
                Bukkit.getScheduler().runTaskAsynchronously(this, () -> checkTopup(target, sender));
                return true;
            }

            case "addcoin" -> {
                if (!has(sender, "arsyacoin.command.add")) {
                    sender.sendMessage("§cKamu tidak punya izin.");
                    return true;
                }

                if (args.length < 3) {
                    sender.sendMessage("§eGunakan: /c addcoin <player> <amount>");
                    return true;
                }

                String target = args[1];
                long amount = parseAmount(args[2], sender);
                if (amount < 0) {
                    return true;
                }

                Bukkit.getScheduler().runTaskAsynchronously(this, () -> changeBalance("add-balance", target, amount, sender, "ditambahkan"));
                return true;
            }

            case "takecoin" -> {
                if (!has(sender, "arsyacoin.command.take")) {
                    sender.sendMessage("§cKamu tidak punya izin.");
                    return true;
                }

                if (args.length < 3) {
                    sender.sendMessage("§eGunakan: /c takecoin <player> <amount>");
                    return true;
                }

                String target = args[1];
                long amount = parseAmount(args[2], sender);
                if (amount < 0) {
                    return true;
                }

                Bukkit.getScheduler().runTaskAsynchronously(this, () -> changeBalance("take-balance", target, amount, sender, "dikurangi"));
                return true;
            }

            case "setcoin" -> {
                if (!has(sender, "arsyacoin.command.set")) {
                    sender.sendMessage("§cKamu tidak punya izin.");
                    return true;
                }

                if (args.length < 3) {
                    sender.sendMessage("§eGunakan: /c setcoin <player> <amount>");
                    return true;
                }

                String target = args[1];
                long amount = parseAmount(args[2], sender);
                if (amount < 0) {
                    return true;
                }

                Bukkit.getScheduler().runTaskAsynchronously(this, () -> changeBalance("set-balance", target, amount, sender, "di-set"));
                return true;
            }

            case "reload" -> {
                if (!has(sender, "arsyacoin.command.reload")) {
                    sender.sendMessage("§cKamu tidak punya izin.");
                    return true;
                }

                reloadLocalConfig();
                sender.sendMessage("§aConfig Arsya Premium Coin berhasil direload.");
                return true;
            }

            default -> {
                sendHelp(sender);
                return true;
            }
        }
    }

    private void refreshBalance(String playerName, boolean announce, CommandSender sender) {
        try {
            ApiResult result = requestBalance(playerName);
            if (!result.ok) {
                if (announce && sender != null) {
                    syncMessage(sender, "§cGagal mengambil saldo dari proxy: " + result.error);
                }
                getLogger().warning("Gagal refresh balance: " + result.error);
                return;
            }

            setCachedBalance(playerName, result.balance);

            if (announce && sender != null) {
                syncMessage(sender, "§aSaldo " + playerName + ": §e" + result.balance + " §7(" + currencyName + ")");
            }
        } catch (Exception exception) {
            if (announce && sender != null) {
                syncMessage(sender, "§cGagal mengambil saldo dari proxy.");
            }
            getLogger().warning("Gagal refresh balance: " + safeMessage(exception));
        }
    }

    private void checkTopup(String playerName, CommandSender sender) {
        try {
            JsonObject body = new JsonObject();
            body.addProperty("player", playerName);

            ApiResult result = requestPost("/internal/check-topup", body);
            if (!result.ok) {
                syncMessage(sender, "§cGagal cek top up dari proxy: " + result.error);
                return;
            }

            setCachedBalance(playerName, result.balance);

            if (result.added > 0) {
                syncMessage(sender, "§aTop up berhasil diproses untuk §e" + playerName + "§a. +" + result.added + " " + currencyName + ". Saldo baru: §e" + result.balance);
            } else {
                syncMessage(sender, "§7Tidak ada top up baru untuk §e" + playerName + "§7. Saldo: §e" + result.balance);
            }
        } catch (Exception exception) {
            syncMessage(sender, "§cGagal cek top up dari proxy.");
            getLogger().warning("Gagal cek top up: " + safeMessage(exception));
        }
    }

    private void changeBalance(String endpoint, String playerName, long amount, CommandSender sender, String actionText) {
        try {
            JsonObject body = new JsonObject();
            body.addProperty("player", playerName);
            body.addProperty("amount", amount);

            ApiResult result = requestPost("/internal/" + endpoint, body);
            if (!result.ok) {
                syncMessage(sender, "§cGagal ubah saldo: " + result.error);
                return;
            }

            setCachedBalance(playerName, result.balance);
            syncMessage(sender, "§aSaldo " + playerName + " berhasil " + actionText + ". Saldo sekarang: §e" + result.balance);
        } catch (Exception exception) {
            syncMessage(sender, "§cGagal ubah saldo.");
            getLogger().warning("Gagal ubah saldo: " + safeMessage(exception));
        }
    }

    private ApiResult requestBalance(String playerName) throws Exception {
        String url = baseUrl + "/internal/balance?player=" + URLEncoder.encode(playerName, StandardCharsets.UTF_8);

        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMillis(timeoutMs))
                .header("X-APC-Token", token)
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        return parseApiResult(response);
    }

    private ApiResult requestPost(String path, JsonObject body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofMillis(timeoutMs))
                .header("Content-Type", "application/json; charset=utf-8")
                .header("X-APC-Token", token)
                .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(body), StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        return parseApiResult(response);
    }

    private ApiResult parseApiResult(HttpResponse<String> response) {
        try {
            JsonObject root = gson.fromJson(response.body(), JsonObject.class);
            if (root == null) {
                return new ApiResult(false, 0L, 0L, "JSON kosong");
            }

            boolean ok = root.has("ok") && root.get("ok").getAsBoolean();
            long balance = root.has("balance") ? root.get("balance").getAsLong() : 0L;
            long added = root.has("added") ? root.get("added").getAsLong() : 0L;
            String error = root.has("error") ? root.get("error").getAsString() : ("HTTP " + response.statusCode());

            if (response.statusCode() != 200 && ok) {
                ok = false;
            }

            return new ApiResult(ok, balance, added, error);
        } catch (Exception exception) {
            return new ApiResult(false, 0L, 0L, safeMessage(exception));
        }
    }

    private long parseAmount(String raw, CommandSender sender) {
        try {
            long value = Long.parseLong(raw);
            if (value < 0) {
                sender.sendMessage("§cAmount tidak boleh minus.");
                return -1L;
            }
            return value;
        } catch (NumberFormatException exception) {
            sender.sendMessage("§cAmount harus angka.");
            return -1L;
        }
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage("§6=== Arsya Premium Coin ===");
        sender.sendMessage("§e/c saldo §7- lihat saldo sendiri");
        sender.sendMessage("§e/c cektopup §7- cek top up sendiri");
        sender.sendMessage("§e/c refresh §7- refresh saldo sendiri");

        if (has(sender, "arsyacoin.command.check")) {
            sender.sendMessage("§e/c check <player> §7- cek saldo player");
        }
        if (has(sender, "arsyacoin.command.add")) {
            sender.sendMessage("§e/c addcoin <player> <amount> §7- tambah saldo");
        }
        if (has(sender, "arsyacoin.command.take")) {
            sender.sendMessage("§e/c takecoin <player> <amount> §7- kurangi saldo");
        }
        if (has(sender, "arsyacoin.command.set")) {
            sender.sendMessage("§e/c setcoin <player> <amount> §7- set saldo");
        }
        if (has(sender, "arsyacoin.command.reload")) {
            sender.sendMessage("§e/c reload §7- reload config");
        }
    }

    private boolean has(CommandSender sender, String node) {
        if (!(sender instanceof Player)) {
            return true;
        }
        return sender.hasPermission("arsyacoin.admin") || sender.hasPermission(node);
    }

    private void syncMessage(CommandSender sender, String message) {
        Bukkit.getScheduler().runTask(this, () -> sender.sendMessage(message));
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

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> list = new ArrayList<>();
            list.add("saldo");
            list.add("cektopup");
            list.add("refresh");

            if (has(sender, "arsyacoin.command.check")) {
                list.add("check");
            }
            if (has(sender, "arsyacoin.command.add")) {
                list.add("addcoin");
            }
            if (has(sender, "arsyacoin.command.take")) {
                list.add("takecoin");
            }
            if (has(sender, "arsyacoin.command.set")) {
                list.add("setcoin");
            }
            if (has(sender, "arsyacoin.command.reload")) {
                list.add("reload");
            }

            return list;
        }

        if (args.length == 2) {
            String sub = args[0].toLowerCase(Locale.ROOT);
            if (Set.of("saldo", "cektopup", "refresh", "check", "addcoin", "takecoin", "setcoin").contains(sub)) {
                return Bukkit.getOnlinePlayers().stream().map(Player::getName).toList();
            }
        }

        return Collections.emptyList();
    }

    private record ApiResult(boolean ok, long balance, long added, String error) {
    }
}
