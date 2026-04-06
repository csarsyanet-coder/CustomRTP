package dev.arsyadev.apcbridgehttp;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class BalanceCache {
    private final ConcurrentHashMap<UUID, Long> balances = new ConcurrentHashMap<>();
    public long get(UUID uuid) { return balances.getOrDefault(uuid, 0L); }
    public void set(UUID uuid, long balance) { balances.put(uuid, Math.max(0L, balance)); }
}
