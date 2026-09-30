package me.SuperRonanCraft.BetterRTP.player.rtp;

import me.SuperRonanCraft.BetterRTP.references.depends.regionPlugins.REGIONPLUGINS;
import me.SuperRonanCraft.BetterRTP.references.depends.regionPlugins.RegionPluginCheck;
import org.bukkit.Location;

import java.util.ArrayList;
import java.util.List;

public class RTPPluginValidation {

    //Only the validators whose plugin is actually installed and enabled. Rebuilt on load/reload.
    //The old version walked all ~15 REGIONPLUGINS entries on every attempt, and again on every
    //queued location, so most of the work was a wasted isEnabled() call.
    private static volatile List<RegionPluginCheck> active = new ArrayList<>();

    public static void rebuild() {
        List<RegionPluginCheck> list = new ArrayList<>();
        for (REGIONPLUGINS validators : REGIONPLUGINS.values())
            if (validators.isEnabled() && validators.getValidator() != null)
                list.add(validators.getValidator());
        active = List.copyOf(list);
    }

    /**
     * @param loc Location to check
     * @return True if valid location
     */
    public static boolean checkLocation(Location loc) {
        if (loc == null)
            return false;
        for (RegionPluginCheck validator : active)
            if (!validator.check(loc))
                return false;
        return true;
    }
}
