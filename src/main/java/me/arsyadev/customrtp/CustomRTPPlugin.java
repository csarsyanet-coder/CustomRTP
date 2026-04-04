package me.arsyadev.customrtp;

import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public final class CustomRTPPlugin extends JavaPlugin implements TabExecutor {

    private Economy economy;
    private final DecimalFormat moneyFormat = new DecimalFormat("#,###");

    @Override
    public void onEnable() {
        saveDefaultConfig();

        if (!setupEconomy()) {
            getLogger().severe("Vault economy tidak ditemukan. Plugin akan dinonaktifkan.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        if (getCommand("customrtp") != null) {
            getCommand("customrtp").setExecutor(this);
            getCommand("customrtp").setTabCompleter(this);
        }

        getLogger().info("ArsyaDev CustomRTP berhasil diaktifkan. Economy provider: " + economy.getName());
    }

    private boolean setupEconomy() {
        if (getServer().getPluginManager().getPlugin("Vault") == null) {
            economy = null;
            return false;
        }

        RegisteredServiceProvider<Economy> provider = getServer().getServicesManager().getRegistration(Economy.class);
        if (provider == null) {
            economy = null;
            return false;
        }

        economy = provider.getProvider();
        return economy != null;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("customrtp.admin")) {
                sender.sendMessage(color("&cKamu tidak punya permission."));
                return true;
            }

            reloadConfig();
            setupEconomy();
            sender.sendMessage(color(getConfig().getString("messages.reload", "&aConfig berhasil direload.")));
            return true;
        }

        if (!(sender instanceof Player player)) {
            sender.sendMessage("Command ini hanya bisa dipakai player.");
            return true;
        }

        if (economy == null && !setupEconomy()) {
            player.sendMessage(color("&cVault economy belum siap. Coba lagi sebentar."));
            return true;
        }

        if (args.length != 1) {
            player.sendMessage(color("&cGunakan: /customrtp <overworld|nether|end>"));
            return true;
        }

        String key = args[0].toLowerCase(Locale.ROOT);
        ConfigurationSection worlds = getConfig().getConfigurationSection("worlds");
        if (worlds == null || !worlds.isConfigurationSection(key)) {
            player.sendMessage(color("&cTujuan RTP tidak ditemukan di config."));
            return true;
        }

        ConfigurationSection section = worlds.getConfigurationSection(key);
        if (section == null) {
            player.sendMessage(color("&cKonfigurasi world tidak valid."));
            return true;
        }

        if (!section.getBoolean("enabled", true)) {
            player.sendMessage(color(getConfig().getString("messages.disabled", "&cRTP ini sedang dinonaktifkan.")));
            return true;
        }

        String permission = section.getString("permission", "");
        if (!permission.isBlank() && !player.hasPermission(permission)) {
            player.sendMessage(color(getConfig().getString("messages.no-permission", "&cKamu tidak punya permission.")));
            return true;
        }

        double balance = economy.getBalance(player);
        double minimumBalance = section.getDouble("minimum-balance", 1000.0D);
        double percent = section.getDouble("percent", 10.0D);
        double maxCost = section.getDouble("max-cost", 50000.0D);
        boolean bypassCost = player.hasPermission("customrtp.bypasscost");

        if (!bypassCost && balance < minimumBalance) {
            player.sendMessage(applyPlaceholders(
                    getConfig().getString("messages.not-enough-balance", "&cSaldo minimal untuk RTP ini adalah &a$%minimum_balance%&f."),
                    key, balance, 0, minimumBalance, percent, maxCost
            ));
            return true;
        }

        double rawCost = balance * (percent / 100.0D);
        double finalCost = Math.floor(Math.min(rawCost, maxCost));
        if (finalCost < 0) {
            finalCost = 0;
        }

        if (!bypassCost && finalCost > 0) {
            EconomyResponse withdraw = withdrawAndVerify(player, finalCost);
            if (withdraw == null || !withdraw.transactionSuccess()) {
                String error = withdraw == null ? "Provider economy tidak mengubah saldo." : withdraw.errorMessage;
                if (error == null || error.isBlank()) {
                    error = "Provider economy tidak mengubah saldo.";
                }
                player.sendMessage(color("&cGagal memotong saldo: " + error));
                return true;
            }
        }

        String rtpCommand = section.getString("rtp-command", "rtp world world_resources");
        if (rtpCommand.startsWith("/")) {
            rtpCommand = rtpCommand.substring(1);
        }

        boolean executed = player.performCommand(rtpCommand);
        if (!executed) {
            if (!bypassCost && finalCost > 0) {
                economy.depositPlayer(player, finalCost);
            }
            player.sendMessage(color(getConfig().getString("messages.command-failed", "&cGagal menjalankan command RTP.")));
            return true;
        }

        player.sendMessage(applyPlaceholders(
                getConfig().getString("messages.success", "&aSaldo dipotong &e$%cost% &auntuk RTP &f(%world%)"),
                key, balance, finalCost, minimumBalance, percent, maxCost
        ));
        return true;
    }

    private EconomyResponse withdrawAndVerify(Player player, double amount) {
        double before = economy.getBalance(player);
        getLogger().info("[EconomyDebug] provider=" + economy.getName() + ", player=" + player.getName() + ", before=" + before + ", amount=" + amount);

        EconomyResponse response = economy.withdrawPlayer(player, amount);
        double after = economy.getBalance(player);
        logResponse("offlinePlayer", player, amount, before, after, response);
        if (response != null && response.transactionSuccess() && after < before) {
            return response;
        }

        EconomyResponse byName = economy.withdrawPlayer(player.getName(), amount);
        double afterByName = economy.getBalance(player);
        logResponse("playerName", player, amount, after, afterByName, byName);
        if (byName != null && byName.transactionSuccess() && afterByName < before) {
            return byName;
        }

        if (response != null && response.transactionSuccess()) {
            response = new EconomyResponse(amount, afterByName, EconomyResponse.ResponseType.FAILURE,
                    "Provider economy melaporkan sukses, tetapi saldo tidak berubah.");
        } else if (response == null) {
            response = new EconomyResponse(amount, afterByName, EconomyResponse.ResponseType.FAILURE,
                    "Response dari provider economy null.");
        }

        return response;
    }

    private void logResponse(String mode, Player player, double amount, double before, double after, EconomyResponse response) {
        String type = response == null ? "null" : response.type.name();
        String success = response == null ? "false" : String.valueOf(response.transactionSuccess());
        String responseBalance = response == null ? "null" : String.valueOf(response.balance);
        String error = response == null ? "null" : String.valueOf(response.errorMessage);

        getLogger().info("[EconomyDebug] mode=" + mode
                + ", player=" + player.getName()
                + ", amount=" + amount
                + ", before=" + before
                + ", after=" + after
                + ", type=" + type
                + ", success=" + success
                + ", responseBalance=" + responseBalance
                + ", error=" + error);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> result = new ArrayList<>();
            ConfigurationSection worlds = getConfig().getConfigurationSection("worlds");
            if (worlds != null) {
                result.addAll(worlds.getKeys(false));
            }
            result.add("reload");
            return result;
        }
        return Collections.emptyList();
    }

    private String applyPlaceholders(String message, String world, double balance, double cost, double minimumBalance, double percent, double maxCost) {
        if (message == null) {
            return "";
        }

        return color(message
                .replace("%world%", world)
                .replace("%balance%", formatMoney(balance))
                .replace("%cost%", formatMoney(cost))
                .replace("%minimum_balance%", formatMoney(minimumBalance))
                .replace("%percent%", formatMoney(percent))
                .replace("%max_cost%", formatMoney(maxCost))
        );
    }

    private String formatMoney(double value) {
        return moneyFormat.format(value);
    }

    private String color(String text) {
        return ChatColor.translateAlternateColorCodes('&', text);
    }
}
