package me.SuperRonanCraft.BetterRTP.references.database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

import org.bukkit.Bukkit;
import org.bukkit.World;

import me.SuperRonanCraft.BetterRTP.BetterRTP;
import me.SuperRonanCraft.BetterRTP.references.rtpinfo.CooldownData;

public class DatabaseCooldowns extends SQLite {

    public DatabaseCooldowns() {
        super(DATABASE_TYPE.COOLDOWN);
    }

    @Override
    public List<String> getTables() {
        List<String> list = new ArrayList<>();

        // Ignore loaded world names if cooldowns are disabled
        if (!BetterRTP.getInstance().getCooldowns().isEnabled())
            return list;

        // Get list of disabled worlds and ensure list isn't null
        List<String> disabledWorlds = BetterRTP.getInstance().getRTP().getDisabledWorlds();
        if (disabledWorlds == null) disabledWorlds = new ArrayList<>();

        // If there are disabled worlds, iterate through the loaded worlds on the server and
        // add the world name to the list of table names if they aren't marked as disabled
        List<World> worlds = Bukkit.getWorlds();
        if (!disabledWorlds.isEmpty()) {
            for (World world : worlds) {
                if (!disabledWorlds.contains(world.getName()))
                    list.add(world.getName());
            }
        }

        return list;
    }

    public enum COLUMNS {
        UUID("uuid", "varchar(32) PRIMARY KEY"),
        //COOLDOWN DATA
        COOLDOWN_DATE("date", "long"),
        //USES("uses", "integer"),
        ;

        public final String name;
        public final String type;

        COLUMNS(String name, String type) {
            this.name = name;
            this.type = type;
        }
    }

    public void removePlayer(UUID uuid, World world) {
        if (world == null) return;
        // Create SQL query string with backtick-ed table name to allow for special characters
        String sql = String.format(
                "DELETE FROM `%s` WHERE %s = ?",
                world.getName(),
                COLUMNS.UUID.name
        );
        List<Object> params = new ArrayList<Object>() {{
            add(uuid.toString());
        }};
        sqlUpdate(sql, params);
    }

    /**
     * Reads every enabled world's cooldown row for a player in one pass on the DB thread.
     * The old per-world {@link #getCooldown} was a blocking JDBC call made from the scheduler thread
     * for every world of every joining player, which serialised joins behind disk I/O.
     */
    public void loadCooldowns(UUID uuid, Map<World, CooldownData> target) {
        if (!isEnabledCooldowns() || target == null)
            return;
        List<World> worlds = Bukkit.getWorlds();
        if (worlds.isEmpty())
            return;
        try {
            SQLiteExecutor.EXECUTOR.submit(() -> {
                Connection conn = null;
                PreparedStatement ps = null;
                ResultSet rs = null;
                try {
                    conn = getSQLConnection();
                    if (conn == null) return;
                    for (World world : worlds) {
                        ps = conn.prepareStatement(String.format(
                                "SELECT * FROM `%s` WHERE %s = ?",
                                world.getName(),
                                COLUMNS.UUID.name
                        ));
                        ps.setString(1, uuid.toString());
                        rs = ps.executeQuery();
                        if (rs.next()) {
                            Long time = rs.getLong(COLUMNS.COOLDOWN_DATE.name);
                            target.putIfAbsent(world, new CooldownData(uuid, time));
                        }
                        close(ps, rs, conn);
                        ps = null;
                        rs = null;
                    }
                } catch (SQLException ex) {
                    BetterRTP.getInstance().getLogger().log(Level.SEVERE, Errors.sqlConnectionExecute(), ex);
                } finally {
                    close(ps, rs, conn);
                }
            }).get();
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    private boolean isEnabledCooldowns() {
        //Mirror of getTables(): tables only exist when the cooldown system is on AND at least one
        //world is not in DisabledWorlds
        if (!BetterRTP.getInstance().getCooldowns().isEnabled())
            return false;
        List<String> disabledWorlds = BetterRTP.getInstance().getRTP().getDisabledWorlds();
        return disabledWorlds != null && !disabledWorlds.isEmpty();
    }

    public CooldownData getCooldown(UUID uuid, World world) {
        if (world == null) return null;
        Connection conn = null;
        PreparedStatement ps = null;
        ResultSet rs = null;
        try {
            conn = getSQLConnection();
            if (conn == null) return null;
            // Create prepared statement with backtick-ed table name to allow for special characters
            ps = conn.prepareStatement(String.format(
                    "SELECT * FROM `%s` WHERE %s = ?",
                    world.getName(),
                    COLUMNS.UUID.name
            ));
            ps.setString(1, uuid.toString());

            rs = ps.executeQuery();
            if (rs.next()) {
                Long time = rs.getLong(COLUMNS.COOLDOWN_DATE.name);
                //int uses = rs.getInt(COLUMNS.USES.name);
                return new CooldownData(uuid, time);
            }
        } catch (SQLException ex) {
            BetterRTP.getInstance().getLogger().log(Level.SEVERE, Errors.sqlConnectionExecute(), ex);
        } finally {
            close(ps, rs, conn);
        }
        return null;
    }

    //Set a player Cooldown
    public void setCooldown(World world, CooldownData data) {
        if (world == null || data == null) return;
        String pre = "INSERT OR REPLACE INTO ";
        String sql = pre + world.getName() + " ("
                + COLUMNS.UUID.name + ", "
                + COLUMNS.COOLDOWN_DATE.name + " "
                //+ COLUMNS.USES.name + " "
                + ") VALUES(?, ?)";
        List<Object> params = new ArrayList<Object>() {{
                add(data.getUuid().toString());
                add(data.getTime());
                //add(data.getUses());
        }};
        sqlUpdate(sql, params);
    }

    //Update multiple players cooldowns
    /*public void setCooldown(List<CooldownData> cooldownData) {
        String pre = "INSERT OR REPLACE INTO ";
        String sql = pre + table + " ("
                + COLUMNS.UUID.name + ", "
                + COLUMNS.COOLDOWN_DATE.name + ", "
                + COLUMNS.USES.name + " "
                + ") VALUES(?, ?, ?)";
        for (CooldownData data : cooldownData) {
            List<Object> param = new ArrayList<Object>() {{
                add(data.getUuid().toString());
                add(data.getTime());
                add(data.getUses());
            }};
            sqlUpdate(sql, param);
        }
    }*/
}