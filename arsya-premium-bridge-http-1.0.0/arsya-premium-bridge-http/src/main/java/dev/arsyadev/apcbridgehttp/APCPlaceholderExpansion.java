package dev.arsyadev.apcbridgehttp;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;

public final class APCPlaceholderExpansion extends PlaceholderExpansion {
    private final ArsyaPremiumBridgeHttpPlugin plugin;
    private final BalanceCache cache;

    public APCPlaceholderExpansion(ArsyaPremiumBridgeHttpPlugin plugin, BalanceCache cache) {
        this.plugin = plugin; this.cache = cache;
    }

    @Override public @NotNull String getIdentifier() { return "apc"; }
    @Override public @NotNull String getAuthor() { return "ArsyaDev"; }
    @Override public @NotNull String getVersion() { return plugin.getDescription().getVersion(); }
    @Override public boolean persist() { return true; }

    @Override
    public String onRequest(OfflinePlayer player, @NotNull String params) {
        long value = cache.get(player.getUniqueId());
        if (params.equalsIgnoreCase("balance") || params.equalsIgnoreCase("saldo")) return String.valueOf(value);
        return null;
    }
}
