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
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.RayTraceResult;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class BanyaPlugin extends JavaPlugin implements Listener {

    private static final String[] ZONES = {"pvp", "safe", "wood"};

    private NamespacedKey mainXpKey;
    private NamespacedKey toolKey;
    private Economy economy;
    private boolean areaBreaking = false;

    private final Map<String, Region> regions = new HashMap<>();
    private final Map<UUID, Long> lastDeniedMsg = new HashMap<>();

    /** Játékos által lerakott fák/ércek - ezekért nem jár XP/pénz (újraindításkor törlődik). */
    private final Set<String> placedBlocks = new HashSet<>();

    /** Két sarokkal megadott kocka alakú zóna. */
    private record Region(String world, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        boolean contains(Location l) {
            return l.getWorld() != null && l.getWorld().getName().equals(world)
                    && l.getBlockX() >= minX && l.getBlockX() <= maxX
                    && l.getBlockY() >= minY && l.getBlockY() <= maxY
                    && l.getBlockZ() >= minZ && l.getBlockZ() <= maxZ;
        }
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        mainXpKey = new NamespacedKey(this, "banya_xp");
        toolKey = new NamespacedKey(this, "banya_tool");
        getServer().getPluginManager().registerEvents(this, this);
        // A világok betöltése után töltjük be a zónákat
        Bukkit.getScheduler().runTask(this, this::loadRegions);
        getLogger().info("BanyaXP elindult!");
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

    private String toolName(String type) {
        return type.equals("axe") ? "Balta" : "Csákány";
    }

    // =====================================================================
    //  Zónák (két sarok) és hozzáférés
    // =====================================================================

    private void loadRegions() {
        regions.clear();
        for (String zone : ZONES) {
            Location a = getConfig().getLocation("regions." + zone + ".pos1");
            Location b = getConfig().getLocation("regions." + zone + ".pos2");
            if (a == null || b == null || a.getWorld() == null) continue;
            regions.put(zone, new Region(a.getWorld().getName(),
                    Math.min(a.getBlockX(), b.getBlockX()), Math.min(a.getBlockY(), b.getBlockY()),
                    Math.min(a.getBlockZ(), b.getBlockZ()), Math.max(a.getBlockX(), b.getBlockX()),
                    Math.max(a.getBlockY(), b.getBlockY()), Math.max(a.getBlockZ(), b.getBlockZ())));
        }
    }

    /** Melyik zónában van a hely (pvp, safe, wood) vagy null. */
    private String zoneAt(Location l) {
        for (String zone : ZONES) {
            Region r = regions.get(zone);
            if (r != null && r.contains(l)) return zone;
        }
        return null;
    }

    private int requiredLevel(String zone) {
        return switch (zone) {
            case "safe" -> cfgInt("requirements.safe-mine-level", 5);
            case "pvp" -> cfgInt("requirements.pvp-mine-level", 15);
            default -> 0;
        };
    }

    private boolean canAccess(Player p, String zone) {
        return p.hasPermission("banya.admin") || getMainLevel(p) >= requiredLevel(zone);
    }

    private void denyMessage(Player p, String zone) {
        long now = System.currentTimeMillis();
        Long last = lastDeniedMsg.get(p.getUniqueId());
        if (last != null && now - last < 3000) return;
        lastDeniedMsg.put(p.getUniqueId(), now);
        p.sendMessage("§cIde legalább §e" + requiredLevel(zone) + ". §cszint kell! (Most: " + getMainLevel(p) + ")");
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

    /** A PvP-mentes zónában nem lehet PvP-zni (nyíl és hógolyó sem). */
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

        if ("safe".equals(zoneAt(victim.getLocation())) || "safe".equals(zoneAt(attacker.getLocation()))) {
            e.setCancelled(true);
            attacker.sendActionBar(legacy("§cA PvP-mentes bányában nem lehet harcolni!"));
        }
    }

    // =====================================================================
    //  Fő szint (rendes szint)
    // =====================================================================

    /** Ennyi összes XP kell az adott szint eléréséhez. */
    private long xpForLevel(int level) {
        return (long) cfgInt("level-xp-base", 40) * level * (level + 1) / 2;
    }

    private int levelFromXp(long xp) {
        int level = 0;
        while (xp >= xpForLevel(level + 1)) {
            level++;
        }
        return level;
    }

    private long getMainXp(Player p) {
        return p.getPersistentDataContainer().getOrDefault(mainXpKey, PersistentDataType.LONG, 0L);
    }

    private int getMainLevel(Player p) {
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
        if (level == cfgInt("requirements.safe-mine-level", 5)) {
            p.sendMessage("§aMegnyílt a §bPvP-mentes bánya§a!");
        }
        if (level == cfgInt("requirements.pvp-mine-level", 15)) {
            p.sendMessage("§cMegnyílt a §4PvP bánya§c!");
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
    private double moneyFor(String zone, int level) {
        String base = "money." + zone;
        double amount = getConfig().getDouble(base + ".base", 0)
                + getConfig().getDouble(base + ".per-level", 0)
                * Math.max(0, level - cfgInt(base + ".start-level", 0));
        double max = getConfig().getDouble(base + ".max", 0);
        if (max > 0) amount = Math.min(amount, max);
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
    //  Eszközök (balta, csákány) - a fejlődés a játékoson tárolódik
    // =====================================================================

    private int getToolLevel(Player p, String type) {
        return p.getPersistentDataContainer().getOrDefault(key(type + "_level"), PersistentDataType.INTEGER, 1);
    }

    private int getToolXp(Player p, String type) {
        return p.getPersistentDataContainer().getOrDefault(key(type + "_xp"), PersistentDataType.INTEGER, 0);
    }

    private int toolXpNeeded(int level) {
        return cfgInt("tools.xp-needed-base", 100) * level;
    }

    private int toolMaxLevel() {
        return cfgInt("tools.max-level", 20);
    }

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

        meta.displayName(legacy("§6§lBányász " + toolName(type) + " §7[Lv. " + level + "]"));

        int eff = Math.min(5, (level - 1) / 3);
        List<Component> lore = new ArrayList<>();
        lore.add(legacy("§7Szint: §e" + level + "§7/" + toolMaxLevel()));
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

    private void giveTool(Player p, String type) {
        ItemStack tool = buildTool(type, getToolLevel(p, type));
        Map<Integer, ItemStack> left = p.getInventory().addItem(tool);
        for (ItemStack rest : left.values()) {
            p.getWorld().dropItem(p.getLocation(), rest);
        }
    }

    /** Pótolja a hiányzó eszközöket (baltát mindig, csákányt a megfelelő szinttől). */
    private void ensureTools(Player p) {
        if (!hasTool(p, "axe")) {
            giveTool(p, "axe");
        }
        if (getMainLevel(p) >= cfgInt("requirements.pickaxe-level", 5) && !hasTool(p, "pickaxe")) {
            giveTool(p, "pickaxe");
        }
    }

    /** Eszköz XP, csak ha a játékos a megfelelő eszközt tartja a kezében. */
    private String gainToolXp(Player p, String type) {
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (!isTool(hand, type)) return "";

        int level = getToolLevel(p, type);
        String name = toolName(type);
        if (level >= toolMaxLevel()) {
            return "§b" + name + " §7MAX";
        }

        int gain = cfgInt("tools." + type + ".xp-base", 2)
                + cfgInt("tools." + type + ".xp-per-level", 1) * (level - 1);
        int xp = getToolXp(p, type) + gain;

        boolean leveledUp = false;
        while (level < toolMaxLevel() && xp >= toolXpNeeded(level)) {
            xp -= toolXpNeeded(level);
            level++;
            leveledUp = true;
        }
        if (level >= toolMaxLevel()) xp = 0;

        p.getPersistentDataContainer().set(key(type + "_level"), PersistentDataType.INTEGER, level);
        p.getPersistentDataContainer().set(key(type + "_xp"), PersistentDataType.INTEGER, xp);

        if (leveledUp) {
            p.getInventory().setItemInMainHand(buildTool(type, level));
            p.sendMessage("§b§l" + name.toUpperCase() + " SZINTLÉPÉS! §eÚj szint: §6" + level
                    + " §7(több XP jár egy blokkért)");
            p.playSound(p.getLocation(), Sound.BLOCK_ANVIL_USE, 0.6f, 1.4f);
        }
        return "§b" + name + " Lv." + level + " §e+" + gain + " §7(" + xp + "/" + toolXpNeeded(level) + ")";
    }

    // =====================================================================
    //  Események
    // =====================================================================

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        ensureTools(e.getPlayer());
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTaskLater(this, () -> ensureTools(p), 2L);
    }

    /** Az eszközök halálkor nem dobódnak el. */
    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
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
        if (Tag.LOGS.isTagged(t) || cfgInt("ores." + t.name(), 0) > 0) {
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

    /** Jutalom: XP, eszköz XP és pénz. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Player p = e.getPlayer();
        if (p.getGameMode() != GameMode.SURVIVAL) return;

        Block b = e.getBlock();
        Material t = b.getType();
        if (placedBlocks.remove(blockKey(b))) return;

        boolean log = Tag.LOGS.isTagged(t);
        int oreXp = log ? 0 : cfgInt("ores." + t.name(), 0);
        if (!log && oreXp <= 0) return;

        String zone = zoneAt(b.getLocation());
        boolean zoneOk = log ? "wood".equals(zone) : ("safe".equals(zone) || "pvp".equals(zone));
        if (!zoneOk && getConfig().getBoolean("zones-only", true)) return;

        int levelBefore = getMainLevel(p);
        String main;
        String tool;
        if (log) {
            main = addMainXp(p, cfgInt("wood-main-xp", 2));
            tool = gainToolXp(p, "axe");
        } else {
            main = addMainXp(p, oreXp);
            tool = gainToolXp(p, "pickaxe");
        }

        String line = tool.isEmpty() ? main : main + " §8| " + tool;

        if (zoneOk) {
            double money = moneyFor(zone, levelBefore);
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

    private void teleportToMine(Player p, String mine) {
        int required = requiredLevel(mine);
        int level = getMainLevel(p);
        if (level < required && !p.hasPermission("banya.admin")) {
            p.sendMessage("§cEhhez legalább §e" + required + ". §cszint kell! (Most: " + level + ")");
            return;
        }
        Location loc = getConfig().getLocation("mines." + mine);
        if (loc == null || loc.getWorld() == null) {
            p.sendMessage("§cEz a bánya belépési pontja még nincs beállítva. (Admin: /banya setmine " + mine + ")");
            return;
        }
        p.teleport(loc);
        p.sendMessage(mine.equals("safe") ? "§aBelépsz a §bPvP-mentes bányába§a."
                : "§cBelépsz a §4PvP bányába§c! Vigyázz!");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage("Csak játékos használhatja.");
            return true;
        }
        String sub = args.length == 0 ? "info" : args[0].toLowerCase();

        switch (sub) {
            case "mine" -> teleportToMine(p, "safe");
            case "pvpmine" -> teleportToMine(p, "pvp");
            case "tool" -> {
                ensureTools(p);
                p.sendMessage("§aHiányzó eszközeid pótolva.");
            }
            case "setmine" -> {
                if (!p.hasPermission("banya.admin")) {
                    p.sendMessage("§cNincs jogosultságod.");
                    return true;
                }
                if (args.length < 2 || !(args[1].equals("safe") || args[1].equals("pvp"))) {
                    p.sendMessage("§eHasználat: /banya setmine <safe|pvp>");
                    return true;
                }
                getConfig().set("mines." + args[1], p.getLocation());
                saveConfig();
                p.sendMessage("§aA(z) " + args[1] + " bánya belépési pontja beállítva.");
            }
            case "setregion" -> {
                if (!p.hasPermission("banya.admin")) {
                    p.sendMessage("§cNincs jogosultságod.");
                    return true;
                }
                boolean validZone = args.length >= 2
                        && (args[1].equals("wood") || args[1].equals("safe") || args[1].equals("pvp"));
                boolean validPos = args.length >= 3 && (args[2].equals("1") || args[2].equals("2"));
                if (!validZone || !validPos) {
                    p.sendMessage("§eHasználat: /banya setregion <wood|safe|pvp> <1|2>");
                    p.sendMessage("§7Állj a zóna egyik sarkába (az egyik és a szemközti sarok, magasságban is!).");
                    return true;
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
            default -> {
                int level = getMainLevel(p);
                String zone = zoneAt(p.getLocation());
                p.sendMessage("§6--- Bányász ---");
                p.sendMessage("§eSzint: §f" + level + " §7(" + getMainXp(p) + "/" + xpForLevel(level + 1) + " XP)");
                p.sendMessage("§eBalta: §fLv." + getToolLevel(p, "axe") + " §7(" + getToolXp(p, "axe") + "/"
                        + toolXpNeeded(getToolLevel(p, "axe")) + ")");
                if (level >= cfgInt("requirements.pickaxe-level", 5)) {
                    p.sendMessage("§eCsákány: §fLv." + getToolLevel(p, "pickaxe") + " §7(" + getToolXp(p, "pickaxe")
                            + "/" + toolXpNeeded(getToolLevel(p, "pickaxe")) + ")");
                }
                p.sendMessage("§7PvP-mentes bánya: szint " + requiredLevel("safe")
                        + " | PvP bánya: szint " + requiredLevel("pvp"));
                p.sendMessage("§7Jelenlegi zóna: §f" + (zone == null ? "nincs" : zone));
            }
        }
        return true;
    }
}
