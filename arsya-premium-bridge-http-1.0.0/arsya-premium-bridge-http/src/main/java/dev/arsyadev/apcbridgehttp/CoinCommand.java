package dev.arsyadev.apcbridgehttp;

import org.bukkit.command.*;
import org.bukkit.entity.Player;
import java.util.List;

public final class CoinCommand implements CommandExecutor, TabCompleter {
    private final ArsyaPremiumBridgeHttpPlugin plugin;
    private final ProxyApiClient proxyApiClient;
    private final BalanceCache cache;

    public CoinCommand(ArsyaPremiumBridgeHttpPlugin plugin, ProxyApiClient proxyApiClient, BalanceCache cache) {
        this.plugin = plugin; this.proxyApiClient = proxyApiClient; this.cache = cache;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) { sender.sendMessage("Command ini hanya untuk player."); return true; }
        if (args.length == 0 || args[0].equalsIgnoreCase("saldo")) {
            proxyApiClient.refreshBalance(player.getUniqueId(), player.getName(), true);
            player.sendMessage("§7Memeriksa saldo...");
            return true;
        }
        if (args[0].equalsIgnoreCase("cektopup")) {
            proxyApiClient.checkTopup(player.getUniqueId(), player.getName());
            player.sendMessage("§7Memeriksa top up...");
            return true;
        }
        if (args[0].equalsIgnoreCase("refresh")) {
            proxyApiClient.refreshBalance(player.getUniqueId(), player.getName(), true);
            player.sendMessage("§7Menyegarkan saldo...");
            return true;
        }
        player.sendMessage("§eGunakan: /c saldo, /c cektopup, atau /c refresh");
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        return args.length == 1 ? List.of("saldo", "cektopup", "refresh") : List.of();
    }
}
