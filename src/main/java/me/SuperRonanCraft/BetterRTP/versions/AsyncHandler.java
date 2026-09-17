package me.SuperRonanCraft.BetterRTP.versions;

import com.tcoded.folialib.impl.ServerImplementation;
import com.tcoded.folialib.wrapper.task.WrappedTask;
import io.papermc.lib.PaperLib;
import me.SuperRonanCraft.BetterRTP.BetterRTP;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.event.player.PlayerTeleportEvent;

import java.util.concurrent.CompletableFuture;

public class AsyncHandler {

    public static void async(Runnable runnable) {
        getFolia().runAsync(task -> runnable.run());
    }

    public static void sync(Runnable runnable) {
        getFolia().runNextTick(task -> runnable.run());
    }

    public static void syncAtEntity(Entity entity, Runnable runnable) {
        getFolia().runAtEntity(entity, task -> runnable.run());
    }

    public static void syncAtLocation(Location location, Runnable runnable) {
        getFolia().runAtLocation(location, task -> runnable.run());
    }

    public static WrappedTask asyncLater(Runnable runnable, long ticks) {
        return getFolia().runLaterAsync(runnable, ticks);
    }

    public static WrappedTask syncLater(Runnable runnable, long ticks) {
        return getFolia().runLater(runnable, ticks);
    }

    public static CompletableFuture<Boolean> teleport(Entity entity, Location location) {
        return getFolia().teleportAsync(entity, location);
    }

    public static CompletableFuture<Boolean> teleport(Entity entity, Location location, PlayerTeleportEvent.TeleportCause cause) {
        return getFolia().teleportAsync(entity, location, cause);
    }

    public static CompletableFuture<Chunk> getChunkAtAsync(Location location) {
        return getChunkAtAsync(location.getWorld(), location.getBlockX() >> 4, location.getBlockZ() >> 4, true);
    }

    public static CompletableFuture<Chunk> getChunkAtAsync(Location location, boolean gen) {
        return getChunkAtAsync(location.getWorld(), location.getBlockX() >> 4, location.getBlockZ() >> 4, gen);
    }

    public static CompletableFuture<Chunk> getChunkAtAsync(World world, int x, int z) {
        return getChunkAtAsync(world, x, z, true);
    }

    public static CompletableFuture<Chunk> getChunkAtAsync(World world, int x, int z, boolean gen) {
        try {
            return world.getChunkAtAsync(x, z, gen);
        } catch (LinkageError | Exception e) {
            try {
                return PaperLib.getChunkAtAsync(world, x, z, gen);
            } catch (Throwable t) {
                CompletableFuture<Chunk> future = new CompletableFuture<>();
                future.complete(world.getChunkAt(x, z, gen));
                return future;
            }
        }
    }

    public static ServerImplementation getFolia() {
        return BetterRTP.getInstance().getFoliaHandler().get();
    }
}
