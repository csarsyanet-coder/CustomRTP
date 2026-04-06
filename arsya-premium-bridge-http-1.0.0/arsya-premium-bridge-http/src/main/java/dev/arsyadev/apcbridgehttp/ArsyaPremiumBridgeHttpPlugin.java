package dev.arsyadev.apcbridgehttp;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

public final class ArsyaPremiumBridgeHttpPlugin extends JavaPlugin implements Listener {

    private ProxyApiClient proxyApiClient;
    private BalanceCache balanceCache;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        reloadConfig();
        this.balanceCache = new BalanceCache();
        this.proxyApiClient = new ProxyApiClient(this, balanceCache);

        if (getCommand("c") != null) {
            CoinCommand command = new CoinCommand(this, proxyApiClient, balanceCache);
            getCommand("c").setExecutor(command);
            getCommand("c").setTabCompleter(command);
        }

        Bukkit.getPluginManager().registerEvents(this, this);

        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            new APCPlaceholderExpansion(this, balanceCache).register();
            getLogger().info("PlaceholderAPI terdeteksi. %apc_balance% aktif.");
        }

        getLogger().info("ArsyaPremiumBridgeHttp aktif.");
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        new BukkitRunnable() {
            @Override
            public void run() {
                proxyApiClient.refreshBalance(player.getUniqueId(), player.getName(), false);
            }
        }.runTaskLaterAsynchronously(this, 20L);
    }

    public String currencyName() { return getConfig().getString("messages.currency-name", "Arsya Premium Coin"); }
    public String baseUrl() { return getConfig().getString("proxy-api.base-url", "http://127.0.0.1:37841"); }
    public String token() { return getConfig().getString("proxy-api.token", "ganti-token-internal-ini"); }
    public int timeoutMs() { return getConfig().getInt("proxy-api.timeout-ms", 5000); }
}
