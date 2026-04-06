package dev.arsyadev.proxycoinshttp;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.proxy.ProxyServer;
import org.slf4j.Logger;

import java.nio.file.Path;

@Plugin(
        id = "proxycoinshttp",
        name = "ProxyCoinsHttpCore",
        version = "1.0.0",
        description = "HTTP-based proxy core for Arsya Premium Coin",
        authors = {"ArsyaDev"}
)
public final class ProxyCoinsHttpPlugin {

    private final ProxyServer proxyServer;
    private final Logger logger;
    private final Path dataDirectory;

    private PluginConfig config;
    private ProxyDatabase database;
    private WebstoreService webstoreService;
    private InternalApiServer internalApiServer;

    @Inject
    public ProxyCoinsHttpPlugin(ProxyServer proxyServer, Logger logger, @com.velocitypowered.api.plugin.annotation.DataDirectory Path dataDirectory) {
        this.proxyServer = proxyServer;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe
    public void onProxyInitialize(ProxyInitializeEvent event) {
        try {
            ConfigService configService = new ConfigService(dataDirectory, logger);
            this.config = configService.loadOrCreate();

            this.database = new ProxyDatabase(dataDirectory.resolve(config.database().file()), logger);
            this.database.initialize();

            this.webstoreService = new WebstoreService(config, database, logger);
            this.internalApiServer = new InternalApiServer(config, database, webstoreService, logger);
            this.internalApiServer.start();

            logger.info("[ProxyCoinsHttpCore] Internal API aktif di {}:{}",
                    config.internalApi().host(), config.internalApi().port());
        } catch (Exception exception) {
            logger.error("[ProxyCoinsHttpCore] Gagal start plugin.", exception);
        }
    }

    @Subscribe
    public void onPostLogin(PostLoginEvent event) {
        if (database == null || config == null || webstoreService == null) return;

        String uuid = event.getPlayer().getUniqueId().toString();
        String username = event.getPlayer().getUsername();
        database.upsertPlayer(uuid, username);

        if (config.webstore().checkOnProxyLogin()) {
            proxyServer.getScheduler().buildTask(this, () -> {
                try {
                    WebstoreService.CheckResult result = webstoreService.checkForPlayer(uuid, username);
                    if (result.claimedCount() > 0) {
                        logger.info("[ProxyCoinsHttpCore] {} klaim {} transaksi. Saldo sekarang: {}",
                                username, result.claimedCount(), result.newBalance());
                    }
                } catch (Exception exception) {
                    logger.warn("[ProxyCoinsHttpCore] Gagal auto-check untuk {}: {}", username, exception.getMessage());
                }
            }).schedule();
        }
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        if (internalApiServer != null) internalApiServer.stop();
        if (database != null) database.close();
    }
}
