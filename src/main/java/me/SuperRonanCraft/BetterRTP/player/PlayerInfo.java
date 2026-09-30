package me.SuperRonanCraft.BetterRTP.player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;

import lombok.Getter;
import me.SuperRonanCraft.BetterRTP.references.invs.RTP_INV_SETTINGS;

public class PlayerInfo {

    // All of these are read/written from async chunk and region threads (RTPPlayer.attempt,
    // RTPTeleport's post-teleport callback), so they must be concurrent. Keyed by UUID rather than
    // Player so a quit/rejoin never orphans an entry holding a dead Player reference.
    private final Map<UUID, Inventory> invs = new ConcurrentHashMap<>();
    @Getter private final Map<UUID, World> invWorld = new ConcurrentHashMap<>();
    @Getter private final Map<UUID, RTP_INV_SETTINGS> invNextInv = new ConcurrentHashMap<>();
    //private final Map<UUID, CooldownData> cooldown = new ConcurrentHashMap<>();
    @Getter private final Map<UUID, Boolean> rtping = new ConcurrentHashMap<>();
    //private final Map<UUID, List<Location>> previousLocations = new ConcurrentHashMap<>();
    //private final Map<UUID, RTP_TYPE> rtpType = new ConcurrentHashMap<>();

    public void setInvWorld(Player p, World type) {
        invWorld.put(p.getUniqueId(), type);
    }

    public void setNextInv(Player p, RTP_INV_SETTINGS type) {
        invNextInv.put(p.getUniqueId(), type);
    }

    //--Logic--

    public Boolean playerExists(Player p) {
        return invs.containsKey(p.getUniqueId());
    }

    public void unloadAll() {
        invs.clear();
        //invType.clear();
        invWorld.clear();
        invNextInv.clear();
        //cooldown.clear();
        rtping.clear();
        //previousLocations.clear();
    }

    /** Drops every cached reference for a player. Safe to call more than once. */
    public void unload(Player p) {
        clearInvs(p);
        rtping.remove(p.getUniqueId());
    }

    public void clearInvs(Player p) {
        UUID id = p.getUniqueId();
        invs.remove(id);
        //invType.remove(p);
        invWorld.remove(id);
        invNextInv.remove(id);
    }
}
