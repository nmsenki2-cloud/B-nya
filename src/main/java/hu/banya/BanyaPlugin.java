package hu.banya;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.TileState;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.RayTraceResult;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import java.util.UUID;

public class BanyaPlugin extends JavaPlugin implements Listener {

    private static final String[] ARMOR_PIECES = {"helmet", "chestplate", "leggings", "boots"};

    /** Csak operátoroknak (banya.admin jogosultság) elérhető alparancsok. */
    private static final Set<String> ADMIN_SUBS = Set.of("reload", "debug", "zones", "setregion", "setwarp",
            "setlevel", "addlevel", "settool", "reset");

    /** Csak játékos használhatja (konzol nem). */
    private static final Set<String> PLAYER_ONLY_SUBS = Set.of("info", "tp", "pvpmine", "tool", "debug",
            "setregion", "setwarp");

    private NamespacedKey mainXpKey;
    private NamespacedKey toolKey;
    private Economy economy;
    private boolean areaBreaking = false;

    private final Map<String, Region> regions = new HashMap<>();
    private final Map<String, ZoneCfg> zoneCfgs = new LinkedHashMap<>();
    private final Map<UUID, Long> lastDeniedMsg = new HashMap<>();
    private final Map<UUID, Long> lastFullMsg = new HashMap<>();

    /** Játékos által lerakott jutalmazott blokkok - ezekért nem jár XP/pénz (újraindításkor törlődik). */
    private final Set<String> placedBlocks = new HashSet<>();

    /** Két sarokkal megadott kocka alakú zóna (ignoreY: a teljes magasságot lefedi). */
    private record Region(String world, int minX, int minY, int minZ, int maxX, int maxY, int maxZ,
                          boolean ignoreY) {
        boolean contains(Location l) {
            return l.getWorld() != null && l.getWorld().getName().equals(world)
                    && l.getBlockX() >= minX && l.getBlockX() <= maxX
                    && (ignoreY || (l.getBlockY() >= minY && l.getBlockY() <= maxY))
                    && l.getBlockZ() >= minZ && l.getBlockZ() <= maxZ;
        }
    }

    /** Egy bányászzóna beállításai a configból. */
    private record ZoneCfg(String display, Set<Material> mats, boolean logs, int requiredLevel, int mainXp,
                           double moneyBase, double moneyPerLevel, int moneyStart, double moneyMax) {
        boolean matches(Material m) {
            return mats.contains(m) || (logs && Tag.LOGS.isTagged(m));
        }
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        mainXpKey = new NamespacedKey(this, "banya_xp");
        toolKey = new NamespacedKey(this, "banya_tool");
        loadZoneConfigs();
        getServer().getPluginManager().registerEvents(this, this);
        // A világok betöltése után töltjük be a zónák helyét
        Bukkit.getScheduler().runTask(this, this::loadRegions);

        // PlaceholderAPI (nem kötelező)
        if (getServer().getPluginManager().getPlugin("PlaceholderAPI") != null) {
            new BanyaExpansion(this).register();
            getLogger().info("PlaceholderAPI megtalálva, a %banya_...% placeholderek elérhetők.");
        }
        getLogger().info("BanyaXP elindult! Bányászzónák: " + zoneCfgs.size());
    }

    // =====================================================================
    //  Segédfüggvények
    // =====================================================================

    private NamespacedKey key(String name) {
        return new NamespacedKey(this, name);
    }

    private Component legacy(String text) {
        return LegacyComponentSerializer.legacySection().deserialize(text)
                .decoration(TextDecoration.ITALIC, false);
    }

    private int cfgInt(String path, int def) {
        return getConfig().getInt(path, def);
    }

    private String blockKey(Block b) {
        return b.getWorld().getName() + ":" + b.getX() + ":" + b.getY() + ":" + b.getZ();
    }

    private String gearName(String type) {
        return switch (type) {
            case "axe" -> "Balta";
            case "pickaxe" -> "Csákány";
            case "armor" -> "Páncél";
            default -> "Kard";
        };
    }

    // =====================================================================
    //  Zónák: beállítások a configból + helyük (két sarok)
    // =====================================================================

    private void loadZoneConfigs() {
        zoneCfgs.clear();
        ConfigurationSection sec = getConfig().getConfigurationSection("zones");
        if (sec == null) {
            getLogger().warning("A config.yml-ből hiányzik a 'zones:' rész! Töröld a régi configot, hogy újragenerálódjon.");
            return;
        }
        for (String name : sec.getKeys(false)) {
            ConfigurationSection z = sec.getConfigurationSection(name);
            if (z == null) continue;

            Set<Material> mats = new HashSet<>();
            boolean logs = false;
            for (String m : z.getStringList("materials")) {
                if (m.equalsIgnoreCase("LOGS")) {
                    logs = true;
                    continue;
                }
                Material mat = Material.matchMaterial(m);
                if (mat == null) {
                    getLogger().warning("Ismeretlen blokk a(z) " + name + " zónában: " + m);
                } else {
                    mats.add(mat);
                }
            }
            zoneCfgs.put(name, new ZoneCfg(z.getString("display", name), mats, logs,
                    z.getInt("required-level", 0), z.getInt("main-xp", 1),
                    z.getDouble("money.base", 0), z.getDouble("money.per-level", 0),
                    z.getInt("money.start-level", 0), z.getDouble("money.max", 0)));
        }
    }

    /** Az összes zóna neve: a bányászzónák + a pvp. */
    private List<String> zoneNames() {
        List<String> names = new ArrayList<>(zoneCfgs.keySet());
        names.add("pvp");
        return names;
    }

    private boolean isValidZone(String zone) {
        return zone.equals("pvp") || zoneCfgs.containsKey(zone);
    }

    private String zoneDisplay(String zone) {
        if (zone.equals("pvp")) return "PvP";
        ZoneCfg z = zoneCfgs.get(zone);
        return z == null ? zone : z.display();
    }

    private void loadRegions() {
        regions.clear();
        boolean ignoreY = getConfig().getBoolean("zones-ignore-height", true);
        for (String zone : zoneNames()) {
            Location a = getConfig().getLocation("regions." + zone + ".pos1");
            Location b = getConfig().getLocation("regions." + zone + ".pos2");
            if (a == null || b == null || a.getWorld() == null) continue;
            regions.put(zone, new Region(a.getWorld().getName(),
                    Math.min(a.getBlockX(), b.getBlockX()), Math.min(a.getBlockY(), b.getBlockY()),
                    Math.min(a.getBlockZ(), b.getBlockZ()), Math.max(a.getBlockX(), b.getBlockX()),
                    Math.max(a.getBlockY(), b.getBlockY()), Math.max(a.getBlockZ(), b.getBlockZ()),
                    ignoreY));
        }
    }

    /** Melyik zónában van a hely, vagy null. A pvp zóna az első. */
    private String zoneAt(Location l) {
        Region pvp = regions.get("pvp");
        if (pvp != null && pvp.contains(l)) return "pvp";
        for (String zone : zoneCfgs.keySet()) {
            Region r = regions.get(zone);
            if (r != null && r.contains(l)) return zone;
        }
        return null;
    }

    /** Melyik zónához tartozik a blokk anyaga (jutalmazott blokk), vagy null. */
    private String zoneForMaterial(Material m) {
        for (Map.Entry<String, ZoneCfg> en : zoneCfgs.entrySet()) {
            if (en.getValue().matches(m)) return en.getKey();
        }
        return null;
    }

    private int requiredLevel(String zone) {
        if (zone.equals("pvp")) return cfgInt("pvp.required-level", 15);
        ZoneCfg z = zoneCfgs.get(zone);
        return z == null ? 0 : z.requiredLevel();
    }

    private boolean canAccess(Player p, String zone) {
        return p.hasPermission("banya.admin") || getMainLevel(p) >= requiredLevel(zone);
    }

    private void denyMessage(Player p, String zone) {
        long now = System.currentTimeMillis();
        Long last = lastDeniedMsg.get(p.getUniqueId());
        if (last != null && now - last < 3000) return;
        lastDeniedMsg.put(p.getUniqueId(), now);
        p.sendMessage("§cIde (" + zoneDisplay(zone) + ") legalább §e" + requiredLevel(zone)
                + ". §cszint kell! (Most: " + getMainLevel(p) + ")");
    }

    /** Nem lehet belépni a zónába, ha nincs meg a szint (mozgás). */
    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        Location from = e.getFrom();
        Location to = e.getTo();
        if (to == null) return;
        if (from.getBlockX() == to.getBlockX() && from.getBlockY() == to.getBlockY()
                && from.getBlockZ() == to.getBlockZ()) return;

        Player p = e.getPlayer();
        String zone = zoneAt(to);
        if (zone == null || canAccess(p, zone)) return;

        denyMessage(p, zone);
        if (zone.equals(zoneAt(from))) {
            // már bent van (pl. később húztuk fel a zónát) -> kiküldjük a spawnra
            e.setTo(p.getWorld().getSpawnLocation());
        } else {
            e.setCancelled(true);
        }
    }

    /** Teleporttal/ender gyöngyel/portállal sem lehet bejutni. */
    @EventHandler(ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent e) {
        if (e.getTo() == null) return;
        String zone = zoneAt(e.getTo());
        if (zone != null && !canAccess(e.getPlayer(), zone)) {
            e.setCancelled(true);
            denyMessage(e.getPlayer(), zone);
        }
    }

    /** A bányászzónák PvP-mentesek (nyíl és hógolyó sem sebez). A pvp zónában lehet harcolni. */
    private boolean isPvpFree(Location l) {
        String zone = zoneAt(l);
        return zone != null && !zone.equals("pvp");
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPvp(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Player victim)) return;

        Player attacker = null;
        if (e.getDamager() instanceof Player direct) {
            attacker = direct;
        } else if (e.getDamager() instanceof Projectile proj && proj.getShooter() instanceof Player shooter) {
            attacker = shooter;
        }
        if (attacker == null || attacker.equals(victim)) return;

        if (isPvpFree(victim.getLocation()) || isPvpFree(attacker.getLocation())) {
            e.setCancelled(true);
            attacker.sendActionBar(legacy("§cA bányákban nem lehet harcolni!"));
        }
    }

    // =====================================================================
    //  Fő szint (rendes szint)
    // =====================================================================

    /** Ennyi összes XP kell az adott szint eléréséhez. */
    long xpForLevel(int level) {
        return (long) cfgInt("level-xp-base", 40) * level * (level + 1) / 2;
    }

    private int levelFromXp(long xp) {
        int level = 0;
        while (xp >= xpForLevel(level + 1)) {
            level++;
        }
        return level;
    }

    long getMainXp(Player p) {
        return p.getPersistentDataContainer().getOrDefault(mainXpKey, PersistentDataType.LONG, 0L);
    }

    int getMainLevel(Player p) {
        return levelFromXp(getMainXp(p));
    }

    /** Hozzáadja a fő XP-t, visszaadja az actionbar szövegrészletet. */
    private String addMainXp(Player p, int amount) {
        long oldXp = getMainXp(p);
        long newXp = oldXp + amount;
        p.getPersistentDataContainer().set(mainXpKey, PersistentDataType.LONG, newXp);

        int oldLevel = levelFromXp(oldXp);
        int newLevel = levelFromXp(newXp);
        for (int lvl = oldLevel + 1; lvl <= newLevel; lvl++) {
            mainLevelUp(p, lvl);
        }
        return "§6Szint §e+" + amount + " §7(" + newXp + "/" + xpForLevel(newLevel + 1) + ")";
    }

    private void mainLevelUp(Player p, int level) {
        p.sendMessage("§a§lSZINTLÉPÉS! §eÚj szinted: §6" + level);
        p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1f);

        if (level == cfgInt("requirements.pickaxe-level", 5)) {
            p.sendMessage("§aKaptál egy §6Csákányt§a! Ez is magától fejlődik bányászás közben.");
        }

        StringJoiner opened = new StringJoiner("§7, §b");
        for (ZoneCfg z : zoneCfgs.values()) {
            if (level > 0 && z.requiredLevel() == level) {
                opened.add(z.display());
            }
        }
        if (opened.length() > 0) {
            p.sendMessage("§aMegnyíltak új bányák: §b" + opened);
        }

        if (level == cfgInt("pvp.required-level", 15)) {
            p.sendMessage("§cMegnyílt a §4PvP zóna§c!");
            p.sendMessage("§cKaptál egy §4PvP páncélt§c és egy §4kardot§c! Ölésekkel fejlődnek.");
        }

        for (String cmd : getConfig().getStringList("rewards." + level)) {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd.replace("%player%", p.getName()));
        }
        ensureTools(p);
    }

    // =====================================================================
    //  Pénz (Vault)
    // =====================================================================

    private Economy getEconomy() {
        if (economy == null) {
            RegisteredServiceProvider<Economy> rsp = getServer().getServicesManager().getRegistration(Economy.class);
            if (rsp != null) economy = rsp.getProvider();
        }
        return economy;
    }

    /** Zónánként és szintenként számolt pénz. */
    private double moneyFor(ZoneCfg z, int level) {
        double amount = z.moneyBase() + z.moneyPerLevel() * Math.max(0, level - z.moneyStart());
        if (z.moneyMax() > 0) amount = Math.min(amount, z.moneyMax());
        return amount;
    }

    private void pay(Player p, double amount) {
        Economy eco = getEconomy();
        if (eco == null) {
            getLogger().warning("Nincs Vault gazdasági plugin (pl. EssentialsX), nem tudok pénzt adni!");
            return;
        }
        eco.depositPlayer(p, amount); // chatben nem jelez
    }

    // =====================================================================
    //  Felszerelés szintjei (axe, pickaxe, armor, sword) - a játékoson tárolódik
    // =====================================================================

    int getToolLevel(Player p, String type) {
        return p.getPersistentDataContainer().getOrDefault(key(type + "_level"), PersistentDataType.INTEGER, 1);
    }

    int getToolXp(Player p, String type) {
        return p.getPersistentDataContainer().getOrDefault(key(type + "_xp"), PersistentDataType.INTEGER, 0);
    }

    int maxLevel(String type) {
        return switch (type) {
            case "armor", "sword" -> cfgInt("pvp." + type + ".max-level", 60);
            default -> cfgInt("tools.max-level", 100);
        };
    }

    int xpNeeded(String type, int level) {
        return switch (type) {
            case "armor", "sword" -> cfgInt("pvp." + type + ".xp-needed-base", 150) * level;
            default -> cfgInt("tools.xp-needed-base", 100) * level;
        };
    }

    private boolean pvpUnlocked(Player p) {
        return getMainLevel(p) >= cfgInt("pvp.required-level", 15);
    }

    // =====================================================================
    //  Eszközök (balta, csákány)
    // =====================================================================

    private Material materialFor(String type, int level) {
        boolean axe = type.equals("axe");
        if (level >= 15) return axe ? Material.DIAMOND_AXE : Material.NETHERITE_PICKAXE;
        if (level >= 10) return axe ? Material.IRON_AXE : Material.DIAMOND_PICKAXE;
        if (level >= 5) return axe ? Material.STONE_AXE : Material.IRON_PICKAXE;
        return axe ? Material.WOODEN_AXE : Material.STONE_PICKAXE;
    }

    private ItemStack buildTool(String type, int level) {
        ItemStack item = new ItemStack(materialFor(type, level));
        ItemMeta meta = item.getItemMeta();

        meta.displayName(legacy("§6§lBányász " + gearName(type) + " §7[Lv. " + level + "]"));

        // Minden szintlépésnél +1 (vagy amennyi a configban van) Hatékonyság
        int eff = Math.min(cfgInt("tools.max-efficiency", 255),
                (level - 1) * cfgInt("tools.efficiency-per-level", 1));
        List<Component> lore = new ArrayList<>();
        lore.add(legacy("§7Szint: §e" + level + "§7/" + maxLevel(type)));
        lore.add(legacy("§7Hatékonyság: §e" + eff));
        if (type.equals("axe")) {
            lore.add(legacy("§7Csak fák vágására alkalmas."));
        }
        if (type.equals("pickaxe") && level >= cfgInt("tools.pickaxe.area-level", 100)) {
            lore.add(legacy("§d3x3-as területet bányász!"));
        }
        lore.add(legacy("§8Használat közben magától fejlődik."));
        meta.lore(lore);

        meta.setUnbreakable(true);
        if (eff > 0) {
            meta.addEnchant(Enchantment.EFFICIENCY, eff, true);
        }
        meta.getPersistentDataContainer().set(toolKey, PersistentDataType.STRING, type);
        item.setItemMeta(meta);
        return item;
    }

    /** Bármilyen BanyaXP-s tárgy (balta, csákány, kard, páncél): nem dobható el, halálkor megmarad. */
    private boolean isBanyaTool(ItemStack item) {
        return item != null && item.hasItemMeta()
                && item.getItemMeta().getPersistentDataContainer().has(toolKey, PersistentDataType.STRING);
    }

    private boolean isTool(ItemStack item, String type) {
        if (!isBanyaTool(item)) return false;
        String t = item.getItemMeta().getPersistentDataContainer().get(toolKey, PersistentDataType.STRING);
        return type.equals(t);
    }

    private boolean hasTool(Player p, String type) {
        for (ItemStack item : p.getInventory().getContents()) {
            if (isTool(item, type)) return true;
        }
        return false;
    }

    private ItemStack buildItem(String type, int level) {
        return type.equals("sword") ? buildSword(level) : buildTool(type, level);
    }

    private void giveTool(Player p, String type) {
        ItemStack tool = buildItem(type, getToolLevel(p, type));
        Map<Integer, ItemStack> left = p.getInventory().addItem(tool);
        for (ItemStack rest : left.values()) {
            p.getWorld().dropItem(p.getLocation(), rest);
        }
    }

    /** A meglévő balta/csákány/kard tárgyakat az aktuális szintnek megfelelőre cseréli. */
    private void refreshTools(Player p) {
        ItemStack[] contents = p.getInventory().getContents();
        for (int i = 0; i < contents.length; i++) {
            for (String type : new String[]{"axe", "pickaxe", "sword"}) {
                if (isTool(contents[i], type)) {
                    p.getInventory().setItem(i, buildItem(type, getToolLevel(p, type)));
                }
            }
        }
    }

    /** Pótolja a hiányzó eszközöket, kardot és páncélt. */
    private void ensureTools(Player p) {
        if (!hasTool(p, "axe")) {
            giveTool(p, "axe");
        }
        if (getMainLevel(p) >= cfgInt("requirements.pickaxe-level", 5) && !hasTool(p, "pickaxe")) {
            giveTool(p, "pickaxe");
        }
        if (pvpUnlocked(p)) {
            if (!hasTool(p, "sword")) {
                giveTool(p, "sword");
            }
            ensureArmor(p);
        }
    }

    /** Eltávolítja azokat a tárgyakat, amikhez a játékosnak (még) nincs meg a szintje. */
    private void removeLockedItems(Player p) {
        boolean removePickaxe = getMainLevel(p) < cfgInt("requirements.pickaxe-level", 5);
        boolean removePvp = !pvpUnlocked(p);
        PlayerInventory inv = p.getInventory();
        ItemStack[] contents = inv.getContents();
        for (int i = 0; i < contents.length; i++) {
            ItemStack item = contents[i];
            if (!isBanyaTool(item)) continue;
            String id = item.getItemMeta().getPersistentDataContainer().get(toolKey, PersistentDataType.STRING);
            if (id == null) continue;
            boolean locked = (removePickaxe && id.equals("pickaxe"))
                    || (removePvp && (id.equals("sword") || id.startsWith("armor_")));
            if (locked) {
                inv.setItem(i, null);
            }
        }
    }

    /** Szinkronizálja a játékos tárgyait az adataival (belépés, admin parancsok, reload után). */
    private void syncPlayer(Player p) {
        removeLockedItems(p);
        refreshTools(p);
        refreshArmor(p);
        ensureTools(p);
    }

    /** Eszköz XP bányászatért, csak ha a játékos a megfelelő eszközt tartja a kezében. */
    private String gainToolXp(Player p, String type) {
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (!isTool(hand, type)) return "";

        int level = getToolLevel(p, type);
        String name = gearName(type);
        int max = maxLevel(type);
        if (level >= max) {
            return "§b" + name + " §7MAX";
        }

        int gain = cfgInt("tools." + type + ".xp-base", 2)
                + cfgInt("tools." + type + ".xp-per-level", 1) * (level - 1);
        int xp = getToolXp(p, type) + gain;

        boolean leveledUp = false;
        while (level < max && xp >= xpNeeded(type, level)) {
            xp -= xpNeeded(type, level);
            level++;
            leveledUp = true;
        }
        if (level >= max) xp = 0;

        p.getPersistentDataContainer().set(key(type + "_level"), PersistentDataType.INTEGER, level);
        p.getPersistentDataContainer().set(key(type + "_xp"), PersistentDataType.INTEGER, xp);

        if (leveledUp) {
            p.getInventory().setItemInMainHand(buildTool(type, level));
            p.sendMessage("§b§l" + name.toUpperCase() + " SZINTLÉPÉS! §eÚj szint: §6" + level
                    + " §7(+Hatékonyság, több XP jár egy blokkért)");
            p.playSound(p.getLocation(), Sound.BLOCK_ANVIL_USE, 0.6f, 1.4f);
        }
        return "§b" + name + " Lv." + level + " §e+" + gain + " §7(" + xp + "/" + xpNeeded(type, level) + ")";
    }

    // =====================================================================
    //  PvP felszerelés: páncél (láncing -> netherite) és kard (kő -> netherite)
    //  Csak játékos-ölésből fejlődik.
    // =====================================================================

    private String armorTier(int level) {
        if (level >= cfgInt("pvp.armor.netherite-level", 40)) return "NETHERITE";
        if (level >= cfgInt("pvp.armor.diamond-level", 25)) return "DIAMOND";
        if (level >= cfgInt("pvp.armor.iron-level", 10)) return "IRON";
        return "CHAINMAIL";
    }

    private Material swordMaterial(int level) {
        if (level >= cfgInt("pvp.sword.netherite-level", 40)) return Material.NETHERITE_SWORD;
        if (level >= cfgInt("pvp.sword.diamond-level", 25)) return Material.DIAMOND_SWORD;
        if (level >= cfgInt("pvp.sword.iron-level", 10)) return Material.IRON_SWORD;
        return Material.STONE_SWORD;
    }

    private String armorPieceName(String piece) {
        return switch (piece) {
            case "helmet" -> "Sisak";
            case "chestplate" -> "Mellvért";
            case "leggings" -> "Lábvért";
            default -> "Csizma";
        };
    }

    private ItemStack buildArmor(String piece, int level) {
        Material mat = Material.valueOf(armorTier(level) + "_" + piece.toUpperCase());
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();

        meta.displayName(legacy("§c§lPvP " + armorPieceName(piece) + " §7[Lv. " + level + "]"));

        int prot = Math.min(cfgInt("pvp.armor.max-protection", 10),
                (level - 1) / Math.max(1, cfgInt("pvp.armor.protection-every", 3)));
        int unb = Math.min(cfgInt("pvp.armor.max-unbreaking", 10),
                (level - 1) / Math.max(1, cfgInt("pvp.armor.unbreaking-every", 3)));

        List<Component> lore = new ArrayList<>();
        lore.add(legacy("§7Szint: §e" + level + "§7/" + maxLevel("armor")));
        lore.add(legacy("§7Védelem: §e" + prot));
        lore.add(legacy("§7Törhetetlenség: §e" + unb));
        lore.add(legacy("§8Játékos-ölésekkel fejlődik. Nem kopik, nem dobódik el."));
        meta.lore(lore);

        meta.setUnbreakable(true);
        if (prot > 0) {
            meta.addEnchant(Enchantment.PROTECTION, prot, true);
        }
        if (unb > 0) {
            meta.addEnchant(Enchantment.UNBREAKING, unb, true);
        }
        meta.getPersistentDataContainer().set(toolKey, PersistentDataType.STRING, "armor_" + piece);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack buildSword(int level) {
        ItemStack item = new ItemStack(swordMaterial(level));
        ItemMeta meta = item.getItemMeta();

        meta.displayName(legacy("§c§lPvP Kard §7[Lv. " + level + "]"));

        int sharp = Math.min(cfgInt("pvp.sword.max-sharpness", 10),
                (level - 1) / Math.max(1, cfgInt("pvp.sword.sharpness-every", 3)));

        List<Component> lore = new ArrayList<>();
        lore.add(legacy("§7Szint: §e" + level + "§7/" + maxLevel("sword")));
        lore.add(legacy("§7Élesség: §e" + sharp));
        lore.add(legacy("§8Játékos-ölésekkel fejlődik. Nem kopik, nem dobódik el."));
        meta.lore(lore);

        meta.setUnbreakable(true);
        if (sharp > 0) {
            meta.addEnchant(Enchantment.SHARPNESS, sharp, true);
        }
        meta.getPersistentDataContainer().set(toolKey, PersistentDataType.STRING, "sword");
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack getArmorSlot(Player p, String piece) {
        PlayerInventory inv = p.getInventory();
        return switch (piece) {
            case "helmet" -> inv.getHelmet();
            case "chestplate" -> inv.getChestplate();
            case "leggings" -> inv.getLeggings();
            default -> inv.getBoots();
        };
    }

    private void setArmorSlot(Player p, String piece, ItemStack item) {
        PlayerInventory inv = p.getInventory();
        switch (piece) {
            case "helmet" -> inv.setHelmet(item);
            case "chestplate" -> inv.setChestplate(item);
            case "leggings" -> inv.setLeggings(item);
            default -> inv.setBoots(item);
        }
    }

    /** Felrakja a PvP páncélt: ha már rajta van, nem nyúl hozzá; ha a táskában van (halál után), felrakja; ha nincs, újat ad. */
    private void ensureArmor(Player p) {
        if (!pvpUnlocked(p)) return;
        int level = getToolLevel(p, "armor");

        for (String piece : ARMOR_PIECES) {
            String id = "armor_" + piece;
            ItemStack worn = getArmorSlot(p, piece);
            if (isTool(worn, id)) continue; // már rajta van

            ItemStack[] storage = p.getInventory().getStorageContents();
            int found = -1;
            for (int i = 0; i < storage.length; i++) {
                if (isTool(storage[i], id)) {
                    found = i;
                    break;
                }
            }

            ItemStack ours;
            if (found >= 0) {
                ours = storage[found];
                p.getInventory().setItem(found, null);
            } else {
                ours = buildArmor(piece, level);
            }

            // ha más ruha volt rajta, az a táskába kerül
            if (worn != null && !worn.getType().isAir()) {
                Map<Integer, ItemStack> left = p.getInventory().addItem(worn);
                for (ItemStack rest : left.values()) {
                    p.getWorld().dropItem(p.getLocation(), rest);
                }
            }
            setArmorSlot(p, piece, ours);
        }
    }

    /** A meglévő páncéldarabokat az aktuális szintre cseréli. */
    private void refreshArmor(Player p) {
        int level = getToolLevel(p, "armor");
        for (String piece : ARMOR_PIECES) {
            if (isTool(getArmorSlot(p, piece), "armor_" + piece)) {
                setArmorSlot(p, piece, buildArmor(piece, level));
            }
        }
        ItemStack[] storage = p.getInventory().getStorageContents();
        for (int i = 0; i < storage.length; i++) {
            for (String piece : ARMOR_PIECES) {
                if (isTool(storage[i], "armor_" + piece)) {
                    p.getInventory().setItem(i, buildArmor(piece, level));
                }
            }
        }
    }

    /** Páncél/kard XP egy ölésért. Visszaadja az actionbar szövegrészletet (üres, ha nem jár). */
    private String gainGearXp(Player p, String type, int amount) {
        if (!pvpUnlocked(p) || amount <= 0) return "";
        int max = maxLevel(type);
        int level = getToolLevel(p, type);
        if (level >= max) return "";

        int xp = getToolXp(p, type) + amount;
        boolean leveledUp = false;
        while (level < max && xp >= xpNeeded(type, level)) {
            xp -= xpNeeded(type, level);
            level++;
            leveledUp = true;
        }
        if (level >= max) xp = 0;

        p.getPersistentDataContainer().set(key(type + "_level"), PersistentDataType.INTEGER, level);
        p.getPersistentDataContainer().set(key(type + "_xp"), PersistentDataType.INTEGER, xp);

        if (leveledUp) {
            if (type.equals("armor")) {
                refreshArmor(p);
            } else {
                refreshTools(p);
            }
            p.sendMessage("§c§l" + gearName(type).toUpperCase() + " SZINTLÉPÉS! §eÚj szint: §6" + level);
            p.playSound(p.getLocation(), Sound.ITEM_ARMOR_EQUIP_IRON, 1f, 1f);
        }
        return "§c" + gearName(type) + " Lv." + level + " §e+" + amount + " §7(" + xp + "/"
                + xpNeeded(type, level) + ")";
    }

    // =====================================================================
    //  Események
    // =====================================================================

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        syncPlayer(e.getPlayer());
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTaskLater(this, () -> ensureTools(p), 2L);
    }

    /** Játékos-ölés a PvP zónában: fő XP + páncél XP + kard XP. A felszerelés halálkor megmarad. */
    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        Player victim = e.getEntity();
        Player killer = victim.getKiller();
        if (killer != null && !killer.equals(victim) && "pvp".equals(zoneAt(victim.getLocation()))) {
            StringBuilder sb = new StringBuilder();
            int killMain = cfgInt("pvp.kill-main-xp", 20);
            if (killMain > 0) {
                sb.append(addMainXp(killer, killMain));
            }
            String armor = gainGearXp(killer, "armor", cfgInt("pvp.armor.kill-xp", 25));
            String sword = gainGearXp(killer, "sword", cfgInt("pvp.sword.kill-xp", 25));
            if (!armor.isEmpty()) {
                sb.append(sb.length() > 0 ? " §8| " : "").append(armor);
            }
            if (!sword.isEmpty()) {
                sb.append(sb.length() > 0 ? " §8| " : "").append(sword);
            }
            if (sb.length() > 0) {
                killer.sendActionBar(legacy(sb.toString()));
            }
        }

        Iterator<ItemStack> it = e.getDrops().iterator();
        while (it.hasNext()) {
            ItemStack item = it.next();
            if (isBanyaTool(item)) {
                it.remove();
                e.getItemsToKeep().add(item);
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent e) {
        if (isBanyaTool(e.getItemDrop().getItemStack())) {
            e.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        Material t = e.getBlockPlaced().getType();
        if (zoneForMaterial(t) != null) {
            placedBlocks.add(blockKey(e.getBlockPlaced()));
        }
    }

    /** Védelem: zónaszint-ellenőrzés + a balta csak fát vághat. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreakGuard(BlockBreakEvent e) {
        Player p = e.getPlayer();
        if (p.getGameMode() == GameMode.CREATIVE) return;
        Block b = e.getBlock();

        String zone = zoneAt(b.getLocation());
        if (zone != null && !canAccess(p, zone)) {
            e.setCancelled(true);
            denyMessage(p, zone);
            return;
        }

        if (isTool(p.getInventory().getItemInMainHand(), "axe")) {
            Material t = b.getType();
            boolean allowed = Tag.LOGS.isTagged(t)
                    || (getConfig().getBoolean("axe-can-break-leaves", false) && Tag.LEAVES.isTagged(t));
            if (!allowed) {
                e.setCancelled(true);
                p.sendActionBar(legacy("§cA balta csak fák vágására jó!"));
            }
        }
    }

    /** Autopickup: a kitört blokk dropjai és az XP közvetlenül a játékoshoz kerülnek. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onAutoPickup(BlockBreakEvent e) {
        if (!getConfig().getBoolean("autopickup.enabled", true)) return;
        Player p = e.getPlayer();
        if (p.getGameMode() != GameMode.SURVIVAL) return;

        Block b = e.getBlock();
        if (b.getState() instanceof TileState) return; // láda, kemence, shulker stb. marad vanilla
        if (getConfig().getBoolean("autopickup.only-in-zones", false) && zoneAt(b.getLocation()) == null) return;

        ItemStack hand = p.getInventory().getItemInMainHand();
        Collection<ItemStack> drops = b.getDrops(hand, p);
        e.setDropItems(false);

        boolean full = false;
        for (ItemStack drop : drops) {
            Map<Integer, ItemStack> left = p.getInventory().addItem(drop);
            for (ItemStack rest : left.values()) {
                b.getWorld().dropItemNaturally(b.getLocation(), rest);
                full = true;
            }
        }

        if (getConfig().getBoolean("autopickup.pickup-exp", true) && e.getExpToDrop() > 0) {
            p.giveExp(e.getExpToDrop());
            e.setExpToDrop(0);
        }

        if (full) {
            long now = System.currentTimeMillis();
            Long last = lastFullMsg.get(p.getUniqueId());
            if (last == null || now - last > 3000) {
                lastFullMsg.put(p.getUniqueId(), now);
                p.sendMessage("§cTele a táskád! A felesleg a földre esett.");
            }
        }
    }

    /** Magas szintű csákány: a kibányászott blokk körül 3x3-as területet szed ki. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onAreaBreak(BlockBreakEvent e) {
        if (areaBreaking) return;
        Player p = e.getPlayer();
        if (p.getGameMode() != GameMode.SURVIVAL) return;
        if (!isTool(p.getInventory().getItemInMainHand(), "pickaxe")) return;
        if (getToolLevel(p, "pickaxe") < cfgInt("tools.pickaxe.area-level", 100)) return;

        Block center = e.getBlock();
        RayTraceResult hit = p.rayTraceBlocks(6);
        if (hit == null || hit.getHitBlock() == null || hit.getHitBlockFace() == null) return;
        if (!hit.getHitBlock().equals(center)) return;
        BlockFace face = hit.getHitBlockFace();

        List<Block> targets = new ArrayList<>();
        for (int a = -1; a <= 1; a++) {
            for (int c = -1; c <= 1; c++) {
                if (a == 0 && c == 0) continue;
                int dx = 0, dy = 0, dz = 0;
                switch (face) {
                    case UP, DOWN -> { dx = a; dz = c; }
                    case NORTH, SOUTH -> { dx = a; dy = c; }
                    default -> { dy = a; dz = c; }
                }
                Block n = center.getRelative(dx, dy, dz);
                Material nt = n.getType();
                if (nt.isAir() || nt.getHardness() < 0) continue;
                if (!Tag.MINEABLE_PICKAXE.isTagged(nt)) continue;
                if (n.getState() instanceof TileState) continue; // láda, kemence, spawner stb. marad
                targets.add(n);
            }
        }

        areaBreaking = true;
        try {
            for (Block n : targets) {
                p.breakBlock(n); // normál törés: védelem, XP és pénz is működik rá
            }
        } finally {
            areaBreaking = false;
        }
    }

    /** Jutalom a bányászatért: fő XP, balta/csákány XP és pénz. A kard és a páncél innen NEM fejlődik. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Player p = e.getPlayer();
        if (p.getGameMode() != GameMode.SURVIVAL) return;

        Block b = e.getBlock();
        Material t = b.getType();
        if (placedBlocks.remove(blockKey(b))) return;

        String matZone = zoneForMaterial(t);
        if (matZone == null) return; // nem jutalmazott blokk
        ZoneCfg z = zoneCfgs.get(matZone);

        String here = zoneAt(b.getLocation());
        boolean inZone = matZone.equals(here);
        if (!inZone && getConfig().getBoolean("zones-only", true)) return;

        int levelBefore = getMainLevel(p);
        String toolType = Tag.LOGS.isTagged(t) ? "axe" : "pickaxe";
        String main = addMainXp(p, z.mainXp());
        String tool = gainToolXp(p, toolType);
        String line = tool.isEmpty() ? main : main + " §8| " + tool;

        if (inZone) {
            double money = moneyFor(z, levelBefore);
            if (money > 0) {
                pay(p, money);
                if (getConfig().getBoolean("show-money-actionbar", false)) {
                    line += " §8| §a+$" + (long) money;
                }
            }
        }
        p.sendActionBar(legacy(line));
    }

    // =====================================================================
    //  Parancsok
    // =====================================================================

    private void teleportToZone(Player p, String zone) {
        if (!isValidZone(zone)) {
            p.sendMessage("§eHasználat: /banya tp <zóna>  §7(zónák: /banya zones)");
            return;
        }
        int required = requiredLevel(zone);
        int level = getMainLevel(p);
        if (level < required && !p.hasPermission("banya.admin")) {
            p.sendMessage("§cEhhez legalább §e" + required + ". §cszint kell! (Most: " + level + ")");
            return;
        }
        Location loc = getConfig().getLocation("warps." + zone);
        if (loc == null || loc.getWorld() == null) {
            p.sendMessage("§cEnnek a zónának a belépési pontja még nincs beállítva. (Admin: /banya setwarp " + zone + ")");
            return;
        }
        p.teleport(loc);
        p.sendMessage("§aBelépsz ide: §b" + zoneDisplay(zone));
    }

    /** Diagnosztika: miért (nem) jár XP a célzott blokkra. */
    private void sendDebug(Player p) {
        p.sendMessage("§6--- BanyaXP debug ---");
        boolean survival = p.getGameMode() == GameMode.SURVIVAL;
        boolean zonesOnly = getConfig().getBoolean("zones-only", true);
        p.sendMessage("§eJátékmód: §f" + p.getGameMode() + (survival ? "" : " §c(csak SURVIVAL-ban jár XP!)"));
        p.sendMessage("§ezones-only: §f" + zonesOnly
                + " §7| zones-ignore-height: §f" + getConfig().getBoolean("zones-ignore-height", true));
        p.sendMessage("§eBeállított zónák: §f" + regions.size() + "§7/" + zoneNames().size()
                + " §7(a teljes listát a /banya zones mutatja)");

        String here = zoneAt(p.getLocation());
        p.sendMessage("§eItt állsz, zóna: §f" + (here == null ? "nincs" : here));

        ItemStack hand = p.getInventory().getItemInMainHand();
        String handType = isTool(hand, "axe") ? "BanyaXP balta"
                : isTool(hand, "pickaxe") ? "BanyaXP csákány" : "§cnem BanyaXP bányászeszköz";
        p.sendMessage("§eKezedben: §f" + handType);

        Block target = p.getTargetBlockExact(6);
        if (target == null) {
            p.sendMessage("§7Nézz egy blokkra, és írd be újra a parancsot!");
            return;
        }
        Material t = target.getType();
        String matZone = zoneForMaterial(t);
        String zone = zoneAt(target.getLocation());
        p.sendMessage("§eCélzott blokk: §f" + t.name() + " §7(zóna ott: " + (zone == null ? "nincs" : zone)
                + ", a blokk zónája: " + (matZone == null ? "nincs" : matZone) + ")");

        if (matZone == null) {
            p.sendMessage("§cEz a blokk egyik zóna anyaglistájában sincs -> nem jár XP.");
        } else if (placedBlocks.contains(blockKey(target))) {
            p.sendMessage("§cEzt a blokkot játékos rakta le -> nem jár XP.");
        } else if (!matZone.equals(zone) && zonesOnly) {
            p.sendMessage("§cEzt a blokkot a(z) §e" + matZone + " §czónában kell bányászni -> itt nem jár XP.");
        } else if (!survival) {
            p.sendMessage("§cKreatív/más játékmódban nem jár XP.");
        } else {
            p.sendMessage("§aIde járna XP. Ha mégsem kapsz, akkor másik plugin (pl. WorldGuard) tiltja a törést.");
        }
    }

    private void sendInfo(Player p) {
        int level = getMainLevel(p);
        String zone = zoneAt(p.getLocation());
        p.sendMessage("§6--- Bányász ---");
        p.sendMessage("§eSzint: §f" + level + " §7(" + getMainXp(p) + "/" + xpForLevel(level + 1) + " XP)");
        p.sendMessage("§eBalta: §fLv." + getToolLevel(p, "axe") + " §7(" + getToolXp(p, "axe") + "/"
                + xpNeeded("axe", getToolLevel(p, "axe")) + ")");
        if (level >= cfgInt("requirements.pickaxe-level", 5)) {
            p.sendMessage("§eCsákány: §fLv." + getToolLevel(p, "pickaxe") + " §7(" + getToolXp(p, "pickaxe")
                    + "/" + xpNeeded("pickaxe", getToolLevel(p, "pickaxe")) + ")");
        }
        if (pvpUnlocked(p)) {
            p.sendMessage("§ePáncél: §fLv." + getToolLevel(p, "armor") + " §7(" + getToolXp(p, "armor") + "/"
                    + xpNeeded("armor", getToolLevel(p, "armor")) + ")");
            p.sendMessage("§eKard: §fLv." + getToolLevel(p, "sword") + " §7(" + getToolXp(p, "sword") + "/"
                    + xpNeeded("sword", getToolLevel(p, "sword")) + ")");
        }
        p.sendMessage("§7PvP zóna: szint " + requiredLevel("pvp"));
        p.sendMessage("§7Jelenlegi zóna: §f" + (zone == null ? "nincs" : zoneDisplay(zone)));
    }

    private void sendHelp(CommandSender s) {
        boolean admin = s.hasPermission("banya.admin");
        s.sendMessage("§6--- BanyaXP parancsok ---");
        s.sendMessage("§e/banya §7- a saját szinted és eszközeid állapota");
        s.sendMessage("§e/banya help §7- ez a lista");
        s.sendMessage("§e/banya tp <zóna> §7- teleport egy bányába (ha megvan a szinted)");
        s.sendMessage("§e/banya pvpmine §7- teleport a PvP zónába (szint " + requiredLevel("pvp") + ")");
        s.sendMessage("§e/banya tool §7- hiányzó eszközök, kard és páncél pótlása");
        if (!admin) return;
        s.sendMessage("§c--- Admin (operátor) parancsok ---");
        s.sendMessage("§e/banya reload §7- config és zónák újratöltése");
        s.sendMessage("§e/banya zones §7- zónák listája (melyik van beállítva)");
        s.sendMessage("§e/banya debug §7- diagnosztika a célzott blokkra");
        s.sendMessage("§e/banya setregion <zóna> <1|2> §7- zóna sarkának beállítása");
        s.sendMessage("§e/banya setwarp <zóna> §7- zóna belépési pontja");
        s.sendMessage("§e/banya setlevel <játékos> <szint> §7- fő szint beállítása");
        s.sendMessage("§e/banya addlevel <játékos> <szám> §7- szintek hozzáadása (negatívval elvétel)");
        s.sendMessage("§e/banya settool <játékos> <axe|pickaxe|armor|sword> <szint> §7- szint beállítása");
        s.sendMessage("§e/banya reset <játékos> [main|axe|pickaxe|armor|sword|all] §7- adatok nullázása");
    }

    private void sendZones(CommandSender s) {
        s.sendMessage("§6--- Zónák ---  §a+ §7beállítva  §c- §7nincs beállítva");
        for (String zone : zoneNames()) {
            boolean set = regions.containsKey(zone);
            s.sendMessage((set ? "§a+ " : "§c- ") + "§e" + zone + " §7(" + zoneDisplay(zone) + ", szint "
                    + requiredLevel(zone) + ")");
        }
    }

    private void doReload(CommandSender s) {
        reloadConfig();
        loadZoneConfigs();
        loadRegions();
        for (Player online : Bukkit.getOnlinePlayers()) {
            syncPlayer(online);
        }
        s.sendMessage("§aBanyaXP config újratöltve. §7(Bányászzónák: " + zoneCfgs.size()
                + ", beállított zónák: " + regions.size() + ")");
    }

    private void cmdSetWarp(Player p, String[] args) {
        if (args.length < 2 || !isValidZone(args[1])) {
            p.sendMessage("§eHasználat: /banya setwarp <zóna>  §7(zónák: /banya zones)");
            return;
        }
        getConfig().set("warps." + args[1], p.getLocation());
        saveConfig();
        p.sendMessage("§aA(z) " + args[1] + " zóna belépési pontja beállítva.");
    }

    private void cmdSetRegion(Player p, String[] args) {
        boolean validZone = args.length >= 2 && isValidZone(args[1]);
        boolean validPos = args.length >= 3 && (args[2].equals("1") || args[2].equals("2"));
        if (!validZone || !validPos) {
            p.sendMessage("§eHasználat: /banya setregion <zóna> <1|2>  §7(zónák: /banya zones)");
            p.sendMessage("§7Állj a zóna egyik sarkába, majd a szemközti sarokba.");
            return;
        }
        Location corner = p.getLocation().getBlock().getLocation();
        getConfig().set("regions." + args[1] + ".pos" + args[2], corner);
        saveConfig();
        loadRegions();
        boolean complete = regions.containsKey(args[1]);
        p.sendMessage("§a" + args[1] + " zóna " + args[2] + ". sarka beállítva: §f"
                + corner.getBlockX() + ", " + corner.getBlockY() + ", " + corner.getBlockZ()
                + (complete ? " §a(a zóna kész)" : " §7(még kell a másik sarok)"));
    }

    private Player findTarget(CommandSender s, String name) {
        Player target = Bukkit.getPlayerExact(name);
        if (target == null) {
            s.sendMessage("§cNincs ilyen online játékos: §f" + name);
        }
        return target;
    }

    private Integer parseInt(CommandSender s, String text) {
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException ex) {
            s.sendMessage("§cEz nem szám: §f" + text);
            return null;
        }
    }

    /** /banya setlevel és /banya addlevel. */
    private void cmdSetLevel(CommandSender s, String[] args, boolean add) {
        if (args.length < 3) {
            s.sendMessage(add ? "§eHasználat: /banya addlevel <játékos> <szám>"
                    : "§eHasználat: /banya setlevel <játékos> <szint>");
            return;
        }
        Player target = findTarget(s, args[1]);
        if (target == null) return;
        Integer n = parseInt(s, args[2]);
        if (n == null) return;

        int current = getMainLevel(target);
        int level = add ? current + n : n;
        level = Math.max(0, Math.min(10000, level));

        target.getPersistentDataContainer().set(mainXpKey, PersistentDataType.LONG, xpForLevel(level));
        syncPlayer(target);

        s.sendMessage("§a" + target.getName() + " szintje: §e" + current + " §7-> §6" + level);
        if (!target.equals(s)) {
            target.sendMessage("§eA szintedet egy admin módosította. Új szinted: §6" + level);
        }
    }

    /** /banya settool <játékos> <axe|pickaxe|armor|sword> <szint>. */
    private void cmdSetTool(CommandSender s, String[] args) {
        String usage = "§eHasználat: /banya settool <játékos> <axe|pickaxe|armor|sword> <szint>";
        if (args.length < 4) {
            s.sendMessage(usage);
            return;
        }
        Player target = findTarget(s, args[1]);
        if (target == null) return;
        String type = args[2].toLowerCase();
        if (!(type.equals("axe") || type.equals("pickaxe") || type.equals("armor") || type.equals("sword"))) {
            s.sendMessage(usage);
            return;
        }
        Integer n = parseInt(s, args[3]);
        if (n == null) return;

        int max = maxLevel(type);
        int level = Math.max(1, Math.min(max, n));

        PersistentDataContainer pdc = target.getPersistentDataContainer();
        pdc.set(key(type + "_level"), PersistentDataType.INTEGER, level);
        pdc.set(key(type + "_xp"), PersistentDataType.INTEGER, 0);
        syncPlayer(target);

        s.sendMessage("§a" + target.getName() + " " + type + " szintje: §6" + level + " §7(max: " + max + ")");
        if (!target.equals(s)) {
            target.sendMessage("§eA(z) " + type + " szintedet egy admin módosította. Új szint: §6" + level);
        }
    }

    private void resetPair(PersistentDataContainer pdc, String base) {
        pdc.remove(key(base + "_level"));
        pdc.remove(key(base + "_xp"));
    }

    /** /banya reset <játékos> [main|axe|pickaxe|armor|sword|all]. */
    private void cmdReset(CommandSender s, String[] args) {
        String usage = "§eHasználat: /banya reset <játékos> [main|axe|pickaxe|armor|sword|all]";
        if (args.length < 2) {
            s.sendMessage(usage);
            return;
        }
        Player target = findTarget(s, args[1]);
        if (target == null) return;

        String what = args.length >= 3 ? args[2].toLowerCase() : "all";
        PersistentDataContainer pdc = target.getPersistentDataContainer();

        switch (what) {
            case "main" -> pdc.remove(mainXpKey);
            case "axe", "pickaxe", "armor", "sword" -> resetPair(pdc, what);
            case "all" -> {
                pdc.remove(mainXpKey);
                resetPair(pdc, "axe");
                resetPair(pdc, "pickaxe");
                resetPair(pdc, "armor");
                resetPair(pdc, "sword");
            }
            default -> {
                s.sendMessage(usage);
                return;
            }
        }
        syncPlayer(target);

        s.sendMessage("§a" + target.getName() + " adatai nullázva: §6" + what);
        if (!target.equals(s)) {
            target.sendMessage("§eAz adataid (" + what + ") egy admin által nullázva lettek.");
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        String sub = args.length == 0 ? "info" : args[0].toLowerCase();

        if (ADMIN_SUBS.contains(sub) && !sender.hasPermission("banya.admin")) {
            sender.sendMessage("§cEhhez operátor jogosultság kell.");
            return true;
        }
        if (PLAYER_ONLY_SUBS.contains(sub) && !(sender instanceof Player)) {
            sender.sendMessage("Ezt a parancsot csak játékos használhatja.");
            return true;
        }
        Player p = sender instanceof Player self ? self : null;

        switch (sub) {
            case "help", "?" -> sendHelp(sender);
            case "info" -> sendInfo(p);
            case "tp" -> teleportToZone(p, args.length >= 2 ? args[1].toLowerCase() : "");
            case "pvpmine" -> teleportToZone(p, "pvp");
            case "tool" -> {
                ensureTools(p);
                p.sendMessage("§aHiányzó eszközeid, kardod és páncélod pótolva.");
            }
            case "reload" -> doReload(sender);
            case "zones" -> sendZones(sender);
            case "debug" -> sendDebug(p);
            case "setwarp" -> cmdSetWarp(p, args);
            case "setregion" -> cmdSetRegion(p, args);
            case "setlevel" -> cmdSetLevel(sender, args, false);
            case "addlevel" -> cmdSetLevel(sender, args, true);
            case "settool" -> cmdSetTool(sender, args);
            case "reset" -> cmdReset(sender, args);
            default -> sender.sendMessage("§cIsmeretlen parancs. Írd be: §e/banya help");
        }
        return true;
    }

    // =====================================================================
    //  Tab kiegészítés
    // =====================================================================

    private List<String> filter(List<String> options, String prefix) {
        String lower = prefix.toLowerCase();
        List<String> result = new ArrayList<>();
        for (String option : options) {
            if (option.toLowerCase().startsWith(lower)) {
                result.add(option);
            }
        }
        return result;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        boolean admin = sender.hasPermission("banya.admin");
        List<String> out = new ArrayList<>();

        if (args.length == 1) {
            out.addAll(List.of("help", "tp", "pvpmine", "tool"));
            if (admin) {
                out.addAll(List.of("reload", "zones", "debug", "setregion", "setwarp", "setlevel", "addlevel",
                        "settool", "reset"));
            }
            return filter(out, args[0]);
        }

        String sub = args[0].toLowerCase();
        if (args.length == 2) {
            switch (sub) {
                case "tp" -> out.addAll(zoneNames());
                case "setregion", "setwarp" -> {
                    if (admin) out.addAll(zoneNames());
                }
                case "setlevel", "addlevel", "settool", "reset" -> {
                    if (admin) {
                        for (Player online : Bukkit.getOnlinePlayers()) {
                            out.add(online.getName());
                        }
                    }
                }
                default -> { }
            }
            return filter(out, args[1]);
        }
        if (args.length == 3 && admin) {
            switch (sub) {
                case "setregion" -> out.addAll(List.of("1", "2"));
                case "settool" -> out.addAll(List.of("axe", "pickaxe", "armor", "sword"));
                case "reset" -> out.addAll(List.of("all", "main", "axe", "pickaxe", "armor", "sword"));
                default -> { }
            }
            return filter(out, args[2]);
        }
        return out;
    }
}
