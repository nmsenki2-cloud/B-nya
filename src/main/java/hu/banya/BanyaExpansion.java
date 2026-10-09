package hu.banya;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

/** PlaceholderAPI bővítés: %banya_...% placeholderek. */
public class BanyaExpansion extends PlaceholderExpansion {

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

        int level = plugin.getMainLevel(p);
        long xp = plugin.getMainXp(p);

        return switch (params.toLowerCase()) {
            case "level" -> String.valueOf(level);
            case "xp" -> String.valueOf(xp);
            case "xp_next" -> String.valueOf(plugin.xpForLevel(level + 1));
            case "xp_percent" -> {
                long cur = plugin.xpForLevel(level);
                long next = plugin.xpForLevel(level + 1);
                yield String.valueOf((xp - cur) * 100 / (next - cur));
            }
            case "axe_level" -> String.valueOf(plugin.getToolLevel(p, "axe"));
            case "axe_xp" -> String.valueOf(plugin.getToolXp(p, "axe"));
            case "axe_next" -> String.valueOf(plugin.toolXpNeeded(plugin.getToolLevel(p, "axe")));
            case "pickaxe_level" -> String.valueOf(plugin.getToolLevel(p, "pickaxe"));
            case "pickaxe_xp" -> String.valueOf(plugin.getToolXp(p, "pickaxe"));
            case "pickaxe_next" -> String.valueOf(plugin.toolXpNeeded(plugin.getToolLevel(p, "pickaxe")));
            case "armor_level" -> String.valueOf(plugin.getArmorLevel(p));
            case "armor_xp" -> String.valueOf(plugin.getArmorXp(p));
            case "armor_next" -> String.valueOf(plugin.armorXpNeeded(plugin.getArmorLevel(p)));
            default -> null;
        };
    }
}
