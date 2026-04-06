package dev.arsyadev.apcbridgehttp;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;

public final class ApcPlaceholderExpansion extends PlaceholderExpansion {

    private final ArsyaPremiumBridgeHttpPlugin plugin;

    public ApcPlaceholderExpansion(ArsyaPremiumBridgeHttpPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "apc";
    }

    @Override
    public @NotNull String getAuthor() {
        return "ArsyaDev";
    }

    @Override
    public @NotNull String getVersion() {
        return plugin.getDescription().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onRequest(OfflinePlayer player, @NotNull String params) {
        if (player == null || player.getUniqueId() == null) {
            return "0";
        }

        String key = params.toLowerCase();
        if (key.equals("balance") || key.equals("saldo")) {
            return String.valueOf(plugin.getCachedBalance(player.getUniqueId()));
        }

        return null;
    }
}
