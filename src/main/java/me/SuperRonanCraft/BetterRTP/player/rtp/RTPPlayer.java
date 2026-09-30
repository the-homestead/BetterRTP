package me.SuperRonanCraft.BetterRTP.player.rtp;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import lombok.Getter;
import me.SuperRonanCraft.BetterRTP.BetterRTP;
import me.SuperRonanCraft.BetterRTP.references.customEvents.RTP_FailedEvent;
import me.SuperRonanCraft.BetterRTP.references.customEvents.RTP_FindLocationEvent;
import me.SuperRonanCraft.BetterRTP.references.helpers.HelperRTP_Check;
import me.SuperRonanCraft.BetterRTP.references.rtpinfo.QueueData;
import me.SuperRonanCraft.BetterRTP.references.rtpinfo.QueueHandler;
import me.SuperRonanCraft.BetterRTP.references.rtpinfo.RandomLocation;
import me.SuperRonanCraft.BetterRTP.references.rtpinfo.worlds.WorldPlayer;
import me.SuperRonanCraft.BetterRTP.versions.AsyncHandler;

public class RTPPlayer {

    @Getter private final Player player;
    private final RTP settings;
    @Getter WorldPlayer worldPlayer;
    @Getter RTP_TYPE type;
    @Getter int attempts;
    //List<Location> attemptedLocations = new ArrayList<>();

    RTPPlayer(Player player, RTP settings, WorldPlayer worldPlayer, RTP_TYPE type) {
        this.player = player;
        this.settings = settings;
        this.worldPlayer = worldPlayer;
        this.type = type;
    }

    void randomlyTeleport(CommandSender sendi) {
        if (attempts >= settings.maxAttempts) //Cancel out, too many tries
            metMax(sendi, player);
        else { //Try again to find a safe location
            //Find a location from another Plugin
            RTP_FindLocationEvent event = new RTP_FindLocationEvent(this); //Find an external plugin location
            Bukkit.getServer().getPluginManager().callEvent(event);
            //Async Location finder
            if (event.isCancelled()) {
                randomlyTeleport(sendi);
                attempts++;
                return;
            }
            AsyncHandler.async(() -> {
                Location loc;
                if (event.getLocation() != null) // && WorldPlayer.checkIsValid(event.getLocation(), pWorld))
                    loc = event.getLocation();
                else {
                    QueueData queueData = QueueHandler.getRandomAsync(worldPlayer);
                    //BetterRTP.getInstance().getLogger().warning("Center x " + worldPlayer.getCenterX());
                    if (queueData != null)
                        loc = queueData.getLocation();
                    else
                        loc = RandomLocation.generateLocation(worldPlayer);
                }
                if (loc == null || loc.getWorld() == null) { //Bad config or a null world, don't spin forever
                    fail(sendi);
                    return;
                }
                attempts++; //Add an attempt
                //Load chunk and find out if safe location (asynchronously)
                AsyncHandler.getChunkAtAsync(loc).thenAccept(chunk -> {
                    //Snapshot the chunk and resolve the safe spot off the main thread. A snapshot is an
                    //immutable copy, so no live block reads happen on the server thread.
                    ChunkSnapshot snapshot = snapshot(chunk);
                    Location safeLoc = RandomLocation.getSafeLocation(worldPlayer.getWorldtype(),
                            worldPlayer.getWorld(), loc, worldPlayer.getMinY(), worldPlayer.getMaxY(),
                            worldPlayer.getBiomes(), snapshot);
                    //Region checks and economy are not thread safe: hop back to the owning thread
                    AsyncHandler.syncAtLocation(loc, () -> attempt(sendi, loc, safeLoc));
                }).exceptionally(e -> {
                    //Chunk failed to load, so there's no snapshot. Retry on the owning thread.
                    AsyncHandler.syncAtLocation(loc, () -> attempt(sendi, loc, null));
                    return null;
                });
            });
        }
    }

    private static ChunkSnapshot snapshot(Chunk chunk) {
        try {
            //includeMaxblocky MUST be true: getHighestBlockYAt() returns 0 without it, which would
            //make every Overworld candidate fail. includeBiome true for the biome blacklist.
            return chunk.getChunkSnapshot(true, true, false);
        } catch (Throwable e) {
            return null; //Fall back to the live-block path
        }
    }

    private void attempt(CommandSender sendi, Location loc) {
        attempt(sendi, loc, null);
    }

    private void attempt(CommandSender sendi, Location loc, Location preResolvedSafeLoc) {
        Location tpLoc = preResolvedSafeLoc;
        if (tpLoc == null) { //No snapshot available, resolve it live (owning thread)
            tpLoc = RandomLocation.getSafeLocation(worldPlayer.getWorldtype(),
                    worldPlayer.getWorld(), loc, worldPlayer.getMinY(), worldPlayer.getMaxY(), worldPlayer.getBiomes());
        }
        //attemptedLocations.add(loc);
        //Valid location?
        if (tpLoc != null && checkDepends(tpLoc)) {
            tpLoc.add(0.5, 0, 0.5); //Center location
            if (getPl().getEco().charge(player, worldPlayer)) {
                //Successfully found a safe location, set cooldown and teleport player.
                if (worldPlayer.getPlayerInfo().isApplyCooldown() && HelperRTP_Check.applyCooldown(player))
                    getPl().getCooldowns().add(player, worldPlayer.getWorld());
                tpLoc.setYaw(player.getLocation().getYaw());
                tpLoc.setPitch(player.getLocation().getPitch());
                settings.teleport.sendPlayer(sendi, player, tpLoc, worldPlayer, attempts, type);
            } else {
                if (worldPlayer.getPlayerInfo().applyCooldown)
                    getPl().getCooldowns().removeCooldown(player, worldPlayer.getWorld());
                getPl().getPInfo().getRtping().remove(player.getUniqueId());
            }
        } else {
            randomlyTeleport(sendi);
            if (loc != null)
                QueueHandler.remove(loc);
        }
    }

    // Compressed code for MaxAttempts being met
    private void metMax(CommandSender sendi, Player p) {
        settings.teleport.failedTeleport(p, sendi);
        getPl().getCooldowns().removeCooldown(p, worldPlayer.getWorld());
        getPl().getPInfo().getRtping().remove(p.getUniqueId());
        //RTP Failed Event
        Bukkit.getServer().getPluginManager().callEvent(new RTP_FailedEvent(this));
    }

    /**
     * Bailed out before a location ever existed (unusable config, unloaded world). Runs off the main
     * thread, so hop back before touching the player, cooldown cache or events.
     */
    private void fail(CommandSender sendi) {
        AsyncHandler.syncAtEntity(player, () -> metMax(sendi, player));
    }

    /**
     * @param loc Location to check
     * @return True if the location is valid
     */
    public static boolean checkDepends(Location loc) {
        return RTPPluginValidation.checkLocation(loc);
    }

    private BetterRTP getPl() {
        return BetterRTP.getInstance();
    }
}