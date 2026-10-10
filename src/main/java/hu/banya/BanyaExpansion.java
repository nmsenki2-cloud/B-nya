package hu.banya;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.util.Set;

/** PlaceholderAPI bővítés: %banya_...% placeholderek. */
public class BanyaExpansion extends PlaceholderExpansion {

    private static final Set<String> TYPES = Set.of("axe", "pickaxe", "armor", "sword");

    private final BanyaPlugin plugin;

    public BanyaExpansion(BanyaPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getIdentifier() {
        return "banya";
    }

    @Override
    public String getAuthor() {
        return "BanyaXP";
    }

    @Override
    public String getVersion() {
        return plugin.getPluginMeta().getVersion();
    }

    /** A /papi reload ne törölje ki a bővítményt. */
    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onRequest(OfflinePlayer offline, String params) {
        Player p = offline == null ? null : offline.getPlayer();
        if (p == null) return "0";

        String key = params.toLowerCase();
        int level = plugin.getMainLevel(p);
        long xp = plugin.getMainXp(p);

        switch (key) {
            case "level":
                return String.valueOf(level);
            case "xp":
                return String.valueOf(xp);
            case "xp_next":
                return String.valueOf(plugin.xpForLevel(level + 1));
            case "xp_percent": {
                long cur = plugin.xpForLevel(level);
                long next = plugin.xpForLevel(level + 1);
                return String.valueOf((xp - cur) * 100 / (next - cur));
            }
            default:
                break;
        }

        // <típus>_level, <típus>_xp, <típus>_next (axe, pickaxe, armor, sword)
        int us = key.indexOf('_');
        if (us > 0) {
            String type = key.substring(0, us);
            String field = key.substring(us + 1);
            if (TYPES.contains(type)) {
                int lvl = plugin.getToolLevel(p, type);
                switch (field) {
                    case "level":
                        return String.valueOf(lvl);
                    case "xp":
                        return String.valueOf(plugin.getToolXp(p, type));
                    case "next":
                        return String.valueOf(plugin.xpNeeded(type, lvl));
                    default:
                        return null;
                }
            }
        }
        return null;
    }
}
