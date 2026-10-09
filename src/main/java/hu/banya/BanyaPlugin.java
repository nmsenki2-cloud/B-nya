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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class BanyaPlugin extends JavaPlugin implements Listener {

    private static final String[] ZONES = {"pvp", "safe", "wood"};
    private static final String[] ARMOR_PIECES = {"helmet", "chestplate", "leggings", "boots"};

    /** Régi, összesített XP mentés (csak az átalakításhoz kell). */
    private NamespacedKey legacyMainXpKey;
    private NamespacedKey mainLevelKey;
    private NamespacedKey mainXpKey;
    private NamespacedKey toolKey;
    private Economy economy;
    private boolean areaBreaking = false;

    private final Map<String, Region> regions = new HashMap<>();
    private final Map<UUID, Long> lastDeniedMsg = new HashMap<>();
    private final Map<UUID, Long> lastFullMsg = new HashMap<>();

    /** Játékos által lerakott fák/ércek - ezekért nem jár XP/pénz (újraindításkor törlődik). */
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

    @Override
    public void onEnable() {
        saveDefaultConfig();
        legacyMainXpKey = new NamespacedKey(this, "banya_xp");
        mainLevelKey = new NamespacedKey(this, "main_level");
        mainXpKey = new NamespacedKey(this, "main_xp");
        toolKey = new NamespacedKey(this, "banya_tool");
        getServer().getPluginManager().registerEvents(this, this);
        // A világok betöltése után töltjük be a zónákat
        Bukkit.getScheduler().runTask(this, this::loadRegions);

        // PlaceholderAPI (nem kötelező)
        if (getServer().getPluginManager().getPlugin("PlaceholderAPI") != null) {
            new BanyaExpansion(this).register();
            getLogger().info("PlaceholderAPI megtalálva, a %banya_...% placeholderek elérhetők.");
        }
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
        boolean ignoreY = getConfig().getBoolean("zones-ignore-height", true);
        for (String zone : ZONES) {
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

    /** Melyik zónában van a hely (pvp, safe, wood) vagy null. */
    private String zoneAt(Location l) {
        for (String zone : ZONES) {
            Region r = regions.get(zone);
            if (r != null && r.contains(l)) return zone;
        }
        return null;
    }

    /** Fa a wood zónában, érc a safe/pvp zónában számít. */
    private boolean zoneMatches(boolean log, String zone) {
        return log ? "wood".equals(zone) : ("safe".equals(zone) || "pvp".equals(zone));
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
    //  Fő szint (rendes szint): szintlépéskor az XP nullázódik
    // =====================================================================

    /** Ennyi XP kell az adott szintről a következőre (szintenként nő). */
    long mainXpNeeded(int level) {
        double base = getConfig().getDouble("level-xp-base", 40);
        double exp = getConfig().getDouble("level-xp-exponent", 1.0);
        return Math.max(1L, Math.round(base * Math.pow(level + 1, exp)));
    }

    /** Régi (összesített XP-s) mentés átalakítása szint + szinten belüli XP formára. */
    private void migrateMain(Player p) {
        PersistentDataContainer pdc = p.getPersistentDataContainer();
        if (pdc.has(mainLevelKey, PersistentDataType.INTEGER)) return;

        long old = pdc.getOrDefault(legacyMainXpKey, PersistentDataType.LONG, 0L);
        long base = cfgInt("level-xp-base", 40);
        int level = 0;
        while (old >= base * (level + 1) * (level + 2) / 2) {
            level++;
        }
        long used = base * level * (level + 1) / 2;
        pdc.set(mainLevelKey, PersistentDataType.INTEGER, level);
        pdc.set(mainXpKey, PersistentDataType.LONG, old - used);
    }

    int getMainLevel(Player p) {
        migrateMain(p);
        return p.getPersistentDataContainer().getOrDefault(mainLevelKey, PersistentDataType.INTEGER, 0);
    }

    /** A jelenlegi szinten belül gyűjtött XP. */
    long getMainXp(Player p) {
        migrateMain(p);
        return p.getPersistentDataContainer().getOrDefault(mainXpKey, PersistentDataType.LONG, 0L);
    }

    /** Hozzáadja a fő XP-t, visszaadja az actionbar szövegrészletet. */
    private String addMainXp(Player p, int amount) {
        int level = getMainLevel(p);
        long xp = getMainXp(p) + amount;

        boolean leveledUp = false;
        if (xp >= mainXpNeeded(level)) {
            level++;
            xp = 0; // szintlépéskor az XP nullázódik
            leveledUp = true;
        }

        PersistentDataContainer pdc = p.getPersistentDataContainer();
        pdc.set(mainLevelKey, PersistentDataType.INTEGER, level);
        pdc.set(mainXpKey, PersistentDataType.LONG, xp);

        if (leveledUp) {
            mainLevelUp(p, level);
        }
        return "§6Szint §e+" + amount + " §7(" + xp + "/" + mainXpNeeded(level) + ")";
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
            p.sendMessage("§cKaptál egy §4PvP páncélt§c! Ércbányászással és gyilkolással a PvP bányában fejlődik.");
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

    int getToolLevel(Player p, String type) {
        return p.getPersistentDataContainer().getOrDefault(key(type + "_level"), PersistentDataType.INTEGER, 1);
    }

    int getToolXp(Player p, String type) {
        return p.getPersistentDataContainer().getOrDefault(key(type + "_xp"), PersistentDataType.INTEGER, 0);
    }

    int toolXpNeeded(int level) {
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

        // Minden szintlépésnél +1 (vagy amennyi a configban van) Hatékonyság
        int eff = Math.min(cfgInt("tools.max-efficiency", 255),
                (level - 1) * cfgInt("tools.efficiency-per-level", 1));
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

    /** Bármilyen BanyaXP-s tárgy (balta, csákány, páncél): nem dobható el, halálkor megmarad. */
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

    /** A meglévő eszközöket az aktuális szintnek megfelelőre cseréli (pl. plugin frissítés után). */
    private void refreshTools(Player p) {
        ItemStack[] contents = p.getInventory().getContents();
        for (int i = 0; i < contents.length; i++) {
            for (String type : new String[]{"axe", "pickaxe"}) {
                if (isTool(contents[i], type)) {
                    p.getInventory().setItem(i, buildTool(type, getToolLevel(p, type)));
                }
            }
        }
    }

    /** Pótolja a hiányzó eszközöket és a PvP páncélt. */
    private void ensureTools(Player p) {
        if (!hasTool(p, "axe")) {
            giveTool(p, "axe");
        }
        if (getMainLevel(p) >= cfgInt("requirements.pickaxe-level", 5) && !hasTool(p, "pickaxe")) {
            giveTool(p, "pickaxe");
        }
        ensureArmor(p);
    }

    /** Eszköz XP, csak ha a játékos a megfelelő eszközt tartja a kezében. mult = zóna szorzó. */
    private String gainToolXp(Player p, String type, double mult) {
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (!isTool(hand, type)) return "";

        int level = getToolLevel(p, type);
        String name = toolName(type);
        if (level >= toolMaxLevel()) {
            return "§b" + name + " §7MAX";
        }

        int baseGain = cfgInt("tools." + type + ".xp-base", 2)
                + cfgInt("tools." + type + ".xp-per-level", 1) * (level - 1);
        int gain = Math.max(1, (int) Math.round(baseGain * mult));
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
                    + " §7(+Hatékonyság, több XP jár egy blokkért)");
            p.playSound(p.getLocation(), Sound.BLOCK_ANVIL_USE, 0.6f, 1.4f);
        }
        return "§b" + name + " Lv." + level + " §e+" + gain + " §7(" + xp + "/" + toolXpNeeded(level) + ")";
    }

    // =====================================================================
    //  PvP páncél (láncing -> vas -> gyémánt -> netherite)
    // =====================================================================

    int getArmorLevel(Player p) {
        return p.getPersistentDataContainer().getOrDefault(key("armor_level"), PersistentDataType.INTEGER, 1);
    }

    int getArmorXp(Player p) {
        return p.getPersistentDataContainer().getOrDefault(key("armor_xp"), PersistentDataType.INTEGER, 0);
    }

    int armorXpNeeded(int level) {
        return cfgInt("armor.xp-needed-base", 150) * level;
    }

    private int armorMaxLevel() {
        return cfgInt("armor.max-level", 60);
    }

    /** A páncél attól a szinttől jár, amitől a PvP bánya megnyílik. */
    private boolean armorUnlocked(Player p) {
        return getMainLevel(p) >= cfgInt("requirements.pvp-mine-level", 15);
    }

    private String armorTier(int level) {
        if (level >= cfgInt("armor.netherite-level", 40)) return "NETHERITE";
        if (level >= cfgInt("armor.diamond-level", 25)) return "DIAMOND";
        if (level >= cfgInt("armor.iron-level", 10)) return "IRON";
        return "CHAINMAIL";
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

        int prot = Math.min(cfgInt("armor.max-protection", 10),
                (level - 1) / Math.max(1, cfgInt("armor.protection-every", 3)));
        int unb = Math.min(cfgInt("armor.max-unbreaking", 10),
                (level - 1) / Math.max(1, cfgInt("armor.unbreaking-every", 3)));

        List<Component> lore = new ArrayList<>();
        lore.add(legacy("§7Szint: §e" + level + "§7/" + armorMaxLevel()));
        lore.add(legacy("§7Védelem: §e" + prot));
        lore.add(legacy("§7Törhetetlenség: §e" + unb));
        lore.add(legacy("§8A PvP bányában fejlődik. Nem kopik, nem dobódik el."));
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
        if (!armorUnlocked(p)) return;
        int level = getArmorLevel(p);

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
        int level = getArmorLevel(p);
        for (String piece : ARMOR_PIECES) {
            String id = "armor_" + piece;
            if (isTool(getArmorSlot(p, piece), id)) {
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

    /** Páncél XP hozzáadása, visszaadja az actionbar szövegrészletet (üres, ha nem jár). */
    private String addArmorXp(Player p, int amount) {
        if (!armorUnlocked(p)) return "";
        int level = getArmorLevel(p);
        if (level >= armorMaxLevel()) return "";

        int xp = getArmorXp(p) + amount;
        boolean leveledUp = false;
        while (level < armorMaxLevel() && xp >= armorXpNeeded(level)) {
            xp -= armorXpNeeded(level);
            level++;
            leveledUp = true;
        }
        if (level >= armorMaxLevel()) xp = 0;

        p.getPersistentDataContainer().set(key("armor_level"), PersistentDataType.INTEGER, level);
        p.getPersistentDataContainer().set(key("armor_xp"), PersistentDataType.INTEGER, xp);

        if (leveledUp) {
            refreshArmor(p);
            p.sendMessage("§c§lPÁNCÉL SZINTLÉPÉS! §eÚj szint: §6" + level
                    + " §7(több Védelem és Törhetetlenség)");
            p.playSound(p.getLocation(), Sound.ITEM_ARMOR_EQUIP_IRON, 1f, 1f);
        }
        return "§cPáncél Lv." + level + " §e+" + amount + " §7(" + xp + "/" + armorXpNeeded(level) + ")";
    }

    // =====================================================================
    //  Események
    // =====================================================================

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        refreshTools(p);
        refreshArmor(p);
        ensureTools(p);
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTaskLater(this, () -> ensureTools(p), 2L);
    }

    /** Eszközök és páncél halálkor nem dobódnak el + XP a PvP zónában megölt játékosért. */
    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        Player victim = e.getEntity();
        Player killer = victim.getKiller();
        if (killer != null && !killer.equals(victim) && "pvp".equals(zoneAt(victim.getLocation()))) {
            String msg = addArmorXp(killer, cfgInt("armor.kill-xp", 25));
            if (!msg.isEmpty()) {
                killer.sendActionBar(legacy(msg));
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
                b.getWorld().dropItem
