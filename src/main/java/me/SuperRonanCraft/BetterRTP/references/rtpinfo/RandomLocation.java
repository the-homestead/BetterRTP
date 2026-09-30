package me.SuperRonanCraft.BetterRTP.references.rtpinfo;

import me.SuperRonanCraft.BetterRTP.versions.AsyncHandler;
import me.SuperRonanCraft.BetterRTP.BetterRTP;
import me.SuperRonanCraft.BetterRTP.references.rtpinfo.worlds.RTPWorld;
import me.SuperRonanCraft.BetterRTP.references.rtpinfo.worlds.WORLD_TYPE;
import org.bukkit.*;
import org.bukkit.block.Biome;
import org.bukkit.block.Block;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

public class RandomLocation {

    //Upper-cased BlacklistedBlocks, rebuilt on every RTP.load()/reload so badBlock() is a set lookup
    //instead of a linear equalsIgnoreCase scan per candidate block. Volatile: read from async chunk tasks.
    private static volatile Set<String> blockCache = Collections.emptySet();

    public static void cacheBlockList(List<String> blocks) {
        Set<String> set = new HashSet<>(Math.max(16, blocks.size() * 2));
        for (String block : blocks)
            set.add(block.toUpperCase(Locale.ROOT));
        blockCache = Collections.unmodifiableSet(set);
    }

    public static Location generateLocation(RTPWorld rtpWorld) {
        Location loc;
        switch (rtpWorld.getShape()) {
            case CIRCLE: loc = generateRound(rtpWorld); break;
            case SQUARE:
            default: loc = generateSquare(rtpWorld); break;
        }
        return loc;
    }

    private static Location generateSquare(RTPWorld rtpWorld) {
        // Return a Location where a random X and Z are within the bounds defined by MinRadius and MaxRadius
        int radius_min = rtpWorld.getMinRadius();
        int radius_max = rtpWorld.getMaxRadius();
        try {
            if (radius_min < 0 || radius_max < 0 || radius_min >= radius_max) {
                throw new IllegalArgumentException(); // If MinRadius or MaxRadius is negative, throw an exception
            }
        } catch (IllegalArgumentException e) {
            e.printStackTrace();
            BetterRTP.getInstance().getLogger().warning("Incorrect configuration! Check your config and confirm that MinRadius is smaller than MaxRadius and that they are both positive numbers!");
            BetterRTP.getInstance().getLogger().warning("Max: " + rtpWorld.getMaxRadius() + " Min: " + rtpWorld.getMinRadius());
            return null;
        }
        // Generate a random X and Z based off the radius. No quadrants voodoo.
        Random random = new Random();
        int x = random.nextInt(radius_max * 2) - radius_max;
        int z = (Math.abs(x) >= radius_min)
            ? random.nextInt(radius_max * 2) - radius_max
            : (random.nextBoolean() ? 1 : -1) * (radius_min + random.nextInt(radius_max - radius_min));
        x += rtpWorld.getCenterX();
        z += rtpWorld.getCenterZ();
        return new Location(rtpWorld.getWorld(), x, 69, z);
    }

    private static Location generateRound(RTPWorld rtpWorld) {
        // Return a random X and Z based off location on a spiral curve
        int min = rtpWorld.getMinRadius();
        int max = rtpWorld.getMaxRadius() - min;
        int x, z;

        double area = Math.PI * (max - min) * (max + min); //of all the area in this donut
        double subArea = area * new Random().nextDouble(); //pick a random subset of that area

        double r = Math.sqrt(subArea/Math.PI + min * min); //convert area to radius
        double theta = (r - (int) r) * 2 * Math.PI; //use the remainder as an angle

        // polar to cartesian
        x = (int) (r * Math.cos(theta));
        z = (int) (r * Math.sin(theta));
        x += rtpWorld.getCenterX();
        z += rtpWorld.getCenterZ();
        return new Location(rtpWorld.getWorld(), x, 69, z);
    }

    /**
     * Live-block-read safe location search. MUST run on the thread owning the location's region
     * (the main thread on Paper/Spigot). Prefer the {@link ChunkSnapshot} overload wherever possible.
     */
    public static Location getSafeLocation(WORLD_TYPE type, World world, Location loc, int minY, int maxY, List<String> biomes) {
        return getSafeLocation(type, world, loc, minY, maxY, biomes, null);
    }

    /**
     * Safe location search backed by a ChunkSnapshot, so it is thread safe and may run off the main
     * thread. Pass a snapshot of the chunk covering {@code loc}: every block and biome read is served
     * from it. Passing null means the caller is on the owning thread and live reads are safe. Passing
     * a snapshot that does NOT cover the column returns null rather than falling back to live reads,
     * which would be an unsafe off-thread access.
     */
    public static Location getSafeLocation(WORLD_TYPE type, World world, Location loc, int minY, int maxY,
                                           List<String> biomes, @Nullable ChunkSnapshot snapshot) {
        if (snapshot != null) {
            if (!covers(snapshot, loc.getBlockX(), loc.getBlockZ()))
                return null; //Refuse rather than touch live blocks off-thread
            switch (type) { //Get a Y position and check for bad blocks
                case NETHER: return getLocAtNether(loc.getBlockX(), loc.getBlockZ(), minY, maxY, world, biomes, snapshot);
                case NORMAL:
                default: return getLocAtNormal(loc.getBlockX(), loc.getBlockZ(), minY, maxY, world, biomes, snapshot);
            }
        }
        switch (type) {
            case NETHER: return getLocAtNether(loc.getBlockX(), loc.getBlockZ(), minY, maxY, world, biomes, null);
            case NORMAL:
            default: return getLocAtNormal(loc.getBlockX(), loc.getBlockZ(), minY, maxY, world, biomes, null);
        }
    }

    private static boolean covers(ChunkSnapshot snapshot, int x, int z) {
        return (x >> 4) == snapshot.getX() && (z >> 4) == snapshot.getZ();
    }

    private static Location getLocAtNormal(int x, int z, int minY, int maxY, World world, List<String> biomes,
                                          @Nullable ChunkSnapshot snapshot) {
        int lx = x & 15, lz = z & 15;
        int y = (snapshot != null) ? snapshot.getHighestBlockYAt(lx, lz) : world.getHighestBlockYAt(x, z);
        if (y > world.getMaxHeight() - 1) //1.15.1 or less: the heightmap can sit above the build limit
            y = world.getMaxHeight() - 1;
        if (y < minY)
            return null;
        Material m = (snapshot != null) ? materialAt(snapshot, lx, y, lz) : world.getBlockAt(x, y, z).getType();
        if (m.name().endsWith("AIR") && --y < minY) //1.15.1 or less
            return null;
        if (!m.isSolid()) { //Water, lava, shrubs...
            if (!badBlock(m.name(), null, snapshot, lx, y, lz, world, x, z)) { //Make sure it's not an invalid block (ex: water, lava...)
                if (--y < minY)
                    return null;
                m = (snapshot != null) ? materialAt(snapshot, lx, y, lz) : world.getBlockAt(x, y, z).getType();
            }
        }
        //Between max and min y
        if (    y <= maxY
                && !badBlock(m.name(), biomes, snapshot, lx, y, lz, world, x, z)) {
            return new Location(world, x, y + 1, z);
        }
        return null;
    }

    public static Block getHighestBlock(int x, int z, World world) {
        Block b = world.getHighestBlockAt(x, z);
        if (b.getType().toString().endsWith("AIR")) //1.15.1 or less
            b = world.getBlockAt(x, b.getY() - 1, z);
        return b;
    }

    private static Location getLocAtNether(int x, int z, int minY, int maxY, World world, List<String> biomes,
                                          @Nullable ChunkSnapshot snapshot) {
        int lx = x & 15, lz = z & 15;
        //Max and Min Y, clamped to the world so a fat config MaxY can't burn thousands of iterations
        int top = Math.min(maxY, world.getMaxHeight());
        for (int y = minY + 1; y < top; y++) {
            Material current = (snapshot != null) ? materialAt(snapshot, lx, y, lz) : world.getBlockAt(x, y, z).getType();
            String currentName = current.name();
            if (currentName.endsWith("AIR") || !current.isSolid()) {
                if (!currentName.endsWith("AIR")
                        && !current.isSolid()) { //Block is not a solid (ex: lava, water...)
                    if (badBlock(currentName, null, snapshot, lx, y, lz, world, x, z))
                        continue;
                }
                Material below = (snapshot != null) ? materialAt(snapshot, lx, y - 1, lz) : world.getBlockAt(x, y - 1, z).getType();
                if (below.name().endsWith("AIR")) //Block below is air, skip
                    continue;
                Material head = (snapshot != null) ? materialAt(snapshot, lx, y + 1, lz) : world.getBlockAt(x, y + 1, z).getType();
                if (head.name().endsWith("AIR") //Head space
                        && !badBlock(below.name(), biomes, snapshot, lx, y - 1, lz, world, x, z)) //Valid block
                    return new Location(world, x, y, z);
            }
        }
        return null;
    }

    private static Material materialAt(ChunkSnapshot snapshot, int lx, int y, int lz) {
        if (y < 0 || y >= 4096)
            return Material.AIR;
        //getBlockType, not getBlockData().getMaterial(): same Material, but skips allocating a
        //BlockData wrapper per read, and this runs up to 3x per Y in the Nether column loop.
        return snapshot.getBlockType(lx, y, lz);
    }

    // Bad blocks, or bad biome
    public static boolean badBlock(String block, int x, int z, World world, List<String> biomes) {
        return badBlock(block, biomes, null, -1, -1, -1, world, x, z);
    }

    private static boolean badBlock(String block, @Nullable List<String> biomes, @Nullable ChunkSnapshot snapshot,
                                    int lx, int y, int lz, @Nullable World world, int x, int z) {
        if (blockCache.contains(block.toUpperCase(Locale.ROOT))) //Check Block
            return true;
        //Check Biomes
        if (biomes == null || biomes.isEmpty())
            return false;
        String biomeCurrent = (snapshot != null)
                ? snapshot.getBiome(lx, Math.max(0, y), lz).name()
                : world.getBiome(x, z).name();
        String upper = biomeCurrent.toUpperCase(Locale.ROOT);
        for (String biome : biomes)
            if (upper.contains(biome.toUpperCase(Locale.ROOT)))
                return false;
        return true;
        //FALSE MEANS NO BAD BLOCKS/BIOME WHERE FOUND!
    }

    public static void runChunkTest() {
        BetterRTP.getInstance().getLogger().info("---------------- Starting chunk test!");
        World world = Bukkit.getWorld("world");
        cacheChunkAt(world, 32, -32, -32, -32);
    }

    private static void cacheTask(World world, int goal, int start, int xat, int zat) {
        zat += 1;
        if (zat > goal) {
            zat = start;
            xat += 1;
        }
        if (xat <= goal)
            cacheChunkAt(world, goal, start, xat, zat);
    }

    private static void cacheChunkAt(World world, int goal, int start, int xat, int zat) {
        CompletableFuture<Chunk> task = AsyncHandler.getChunkAtAsync(new Location(world, xat * 16, 0, zat * 16));
        task.thenAccept(chunk -> {
            try {
                ChunkSnapshot snapshot = chunk.getChunkSnapshot(true, true, false);
                int maxy = snapshot.getHighestBlockYAt(8, 8);
                Biome biome = snapshot.getBiome(8, 8);
                //BetterRTP.getInstance().getLogger().info("Added " + chunk.getX() + " " + chunk.getZ());
                BetterRTP.getInstance().getDatabaseHandler().getDatabaseChunks().addChunk(chunk, maxy, biome);
            } catch (Throwable e) {
                e.printStackTrace();
                throw new RuntimeException();
                //BetterRTP.getInstance().getLogger().info("Tried Adding " + chunk.getX() + " " + chunk.getZ());
            }
        }).thenRun(() -> cacheTask(world, goal, start, xat, zat));
    }

}
