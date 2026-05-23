package com.sharedhealth;

import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExhaustionEvent;
import org.bukkit.event.entity.EntityPotionEffectEvent;
import org.bukkit.event.entity.EntityRegainHealthEvent;
import org.bukkit.event.entity.EntityResurrectEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

public final class SharedHealthPlugin extends JavaPlugin implements Listener {

    private static final String DATA_FILE_NAME = "shared-health.yml";
    private static final String DATA_HAS_HEALTH = "has-shared-health";
    private static final String DATA_SHARED_HEALTH = "shared-health";
    private static final String DATA_HAS_HUNGER = "has-shared-hunger";
    private static final String DATA_SHARED_FOOD = "shared-food-level";
    private static final String DATA_HAS_ABSORPTION = "has-shared-absorption";
    private static final String DATA_SHARED_ABSORPTION = "shared-absorption";
    private static final String DATA_SHARED_DEATH_COUNTER = "shared-death-counter";
    private static final String DATA_PLAYER_DEATH_SYNC = "player-death-sync";
    private static final double EPSILON = 1.0E-6D;

    private File dataFile;
    private FileConfiguration dataConfig;

    private double sharedHealth;
    private boolean sharedHealthInitialized;
    private boolean syncingHealth;
    private boolean massKillInProgress;

    private int sharedFoodLevel;
    private boolean sharedHungerInitialized;
    private boolean syncingHunger;
    private boolean syncingPotionEffects;
    private PotionEffect sharedRegenEffect;
    private PotionEffect sharedHealthBoostEffect;
    private PotionEffect sharedAbsorptionEffect;
    private double sharedAbsorption;
    private boolean sharedAbsorptionInitialized;

    private BukkitTask autosaveTask;

    private boolean dataDirty;
    private long sharedDeathCounter;
    private final Map<UUID, Long> playerDeathSync = new HashMap<>();

    private boolean cfgSharedHealth;
    private boolean cfgSharedHunger;
    private boolean cfgSharedAbsorption;
    private boolean cfgSharedPotions;
    private boolean cfgSharedDeath;
    private boolean cfgPreventStackedRegen;
    private boolean cfgSplitSprintJumpExhaustion;
    private boolean cfgDamageActionBar;
    private boolean cfgDamageHurtAnimation;
    private boolean cfgDamageSound;
    private boolean cfgHideVanillaDeathMessage;
    private boolean cfgHideVanillaJoinMessage;
    private boolean cfgHideVanillaQuitMessage;
    private boolean cfgBroadcastSharedDeath;
    private boolean cfgBroadcastJoin;
    private boolean cfgBroadcastQuit;
    private boolean cfgWipeInventoryOnSharedDeath;
    private boolean cfgWipeEnderChestOnSharedDeath;
    private boolean cfgClearDropsOnSharedDeath;
    private boolean cfgClearGroundItemsOnSharedDeath;
    private boolean cfgResetSharedHungerOnSharedDeath;
    private boolean cfgSyncOnlinePlayersOnEnable;
    private long cfgAutosaveIntervalTicks;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        reloadConfig();
        loadRuntimeConfig();
        loadSharedData();
        getServer().getPluginManager().registerEvents(this, this);
        if (cfgSyncOnlinePlayersOnEnable) {
            Bukkit.getScheduler().runTask(this, this::initializeFromOnlinePlayers);
        }
        if (cfgAutosaveIntervalTicks > 0L) {
            autosaveTask = Bukkit.getScheduler()
                    .runTaskTimer(this, this::saveSharedData, cfgAutosaveIntervalTicks, cfgAutosaveIntervalTicks);
        }
    }

    @Override
    public void onDisable() {
        if (autosaveTask != null) {
            autosaveTask.cancel();
            autosaveTask = null;
        }
        saveSharedData();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerDamage(EntityDamageEvent event) {
        Entity entity = event.getEntity();
        if (!(entity instanceof Player source)) {
            return;
        }

        if (syncingHealth || massKillInProgress) {
            return;
        }

        if (!cfgSharedHealth && !cfgSharedAbsorption) {
            return;
        }

        double rawDamage = event.getDamage();
        double finalDamage = event.getFinalDamage();
        if (rawDamage <= 0.0D && finalDamage <= 0.0D) {
            return;
        }

        double sourceHealthBeforeDamage = source.getHealth();
        double sourceAbsorptionBeforeDamage = source.getAbsorptionAmount();
        Bukkit.getScheduler().runTask(
                this,
                () -> syncSharedHealthFromDamagedPlayer(
                        source, sourceHealthBeforeDamage, sourceAbsorptionBeforeDamage));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerRegainHealth(EntityRegainHealthEvent event) {
        Entity entity = event.getEntity();
        if (!(entity instanceof Player player)) {
            return;
        }

        if (syncingHealth || massKillInProgress) {
            return;
        }

        if (!cfgSharedHealth) {
            return;
        }

        if (cfgPreventStackedRegen
                && isNonStackingSharedRegenReason(event.getRegainReason())
                && !isNaturalRegenController(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerRegainHealthMonitor(EntityRegainHealthEvent event) {
        Entity entity = event.getEntity();
        if (!(entity instanceof Player player)) {
            return;
        }

        if (syncingHealth || massKillInProgress) {
            return;
        }

        if (!cfgSharedHealth) {
            return;
        }

        EntityRegainHealthEvent.RegainReason reason = event.getRegainReason();
        if (cfgPreventStackedRegen && isNonStackingSharedRegenReason(reason) && !isNaturalRegenController(player)) {
            return;
        }

        Bukkit.getScheduler().runTask(this, () -> {
            if (!player.isOnline() || player.isDead()) {
                return;
            }

            ensureSharedHealthInitialized(player);
            double maxSharedHealth = resolveSharedHealthCap(player);
            double healedHealth = Math.min(player.getHealth(), maxSharedHealth);
            updateSharedHealth(healedHealth);
            applySharedHealthToAll(sharedHealth);

            if (cfgSharedHunger && reason == EntityRegainHealthEvent.RegainReason.SATIATED) {
                syncHungerFromPlayer(player);
            }
        });
    }

    private boolean isNonStackingSharedRegenReason(EntityRegainHealthEvent.RegainReason reason) {
        return reason == EntityRegainHealthEvent.RegainReason.SATIATED
                || reason == EntityRegainHealthEvent.RegainReason.MAGIC_REGEN
                || reason == EntityRegainHealthEvent.RegainReason.REGEN;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityPotionEffect(EntityPotionEffectEvent event) {
        Entity entity = event.getEntity();
        if (!(entity instanceof Player source)) {
            return;
        }

        if (syncingPotionEffects) {
            return;
        }

        if (!cfgSharedPotions) {
            return;
        }

        PotionEffect oldEffect = event.getOldEffect();
        PotionEffect newEffect = event.getNewEffect();
        boolean touchesSharedPotion = isSharedPotionType(oldEffect) || isSharedPotionType(newEffect);
        if (!touchesSharedPotion) {
            return;
        }

        Bukkit.getScheduler().runTask(this, () -> syncSharedPotionEffectsFromSource(source));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityResurrect(EntityResurrectEvent event) {
        Entity entity = event.getEntity();
        if (!(entity instanceof Player source)) {
            return;
        }

        if (syncingHealth || massKillInProgress) {
            return;
        }

        if (!cfgSharedHealth && !cfgSharedPotions && !cfgSharedAbsorption) {
            return;
        }

        Bukkit.getScheduler().runTask(this, () -> {
            if (!source.isOnline() || source.isDead()) {
                return;
            }

            if (cfgSharedHealth || cfgSharedAbsorption) {
                syncSharedHealthFromDamagedPlayer(source);
            }
            if (cfgSharedPotions) {
                syncSharedPotionEffectsFromSource(source);
            }
        });
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityExhaustion(EntityExhaustionEvent event) {
        Entity entity = event.getEntity();
        if (!(entity instanceof Player)) {
            return;
        }

        if (!cfgSharedHunger || !cfgSplitSprintJumpExhaustion) {
            return;
        }

        String reason = event.getExhaustionReason().name();
        if (reason.contains("SPRINT") || "JUMP".equals(reason)) {
            int connectedPlayers = Math.max(1, Bukkit.getOnlinePlayers().size());
            event.setExhaustion(event.getExhaustion() / connectedPlayers);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFoodLevelChange(FoodLevelChangeEvent event) {
        Entity entity = event.getEntity();
        if (!(entity instanceof Player player)) {
            return;
        }

        if (syncingHunger) {
            return;
        }

        if (!cfgSharedHunger) {
            return;
        }

        Bukkit.getScheduler().runTask(this, () -> syncHungerFromPlayer(player));
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerDeath(PlayerDeathEvent event) {
        if (cfgHideVanillaDeathMessage) {
            event.deathMessage(null);
        }

        if (!cfgSharedDeath) {
            return;
        }

        if (massKillInProgress) {
            if (cfgWipeInventoryOnSharedDeath) {
                wipePlayerInventory(event.getPlayer());
            }
            if (cfgClearDropsOnSharedDeath) {
                event.getDrops().clear();
            }
            return;
        }

        if (cfgBroadcastSharedDeath) {
            broadcastSharedKilledChat(event.getPlayer().getName());
        }
        if (cfgWipeInventoryOnSharedDeath) {
            wipeAllOnlineInventories();
        }
        if (cfgWipeEnderChestOnSharedDeath) {
            wipeAllOnlineEnderChests();
        }
        if (cfgClearDropsOnSharedDeath) {
            event.getDrops().clear();
        }
        if (cfgClearGroundItemsOnSharedDeath) {
            clearAllGroundItems();
            Bukkit.getScheduler().runTask(this, this::clearAllGroundItems);
        }

        recordSharedDeathForOnlinePlayers();
        if (cfgSharedHealth) {
            updateSharedHealth(0.0D);
        }
        if (cfgSharedAbsorption) {
            updateSharedAbsorption(0.0D);
        }
        if (cfgSharedHunger && cfgResetSharedHungerOnSharedDeath) {
            updateSharedHunger(20);
        }
        if (cfgSharedPotions) {
            setSharedPotionEffects(null, null, null);
        }
        killAllPlayers();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        if (cfgHideVanillaJoinMessage) {
            event.joinMessage(null);
        }
        if (cfgBroadcastJoin) {
            broadcastJoinChat(event.getPlayer().getName());
        }
        syncJoiningPlayerToSharedState(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        Player respawned = event.getPlayer();
        Bukkit.getScheduler().runTask(this, () -> {
            if (hasAnySharedSyncFeatureEnabled()) {
                syncPlayerToSharedState(respawned);
            }
            if (cfgSharedDeath) {
                markPlayerDeathSynced(respawned.getUniqueId(), sharedDeathCounter);
            }
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        if (cfgHideVanillaQuitMessage) {
            event.quitMessage(null);
        }
        if (cfgBroadcastQuit) {
            broadcastQuitChat(event.getPlayer().getName());
        }
        Bukkit.getScheduler().runTask(this, () -> {
            if (Bukkit.getOnlinePlayers().isEmpty()) {
                saveSharedData();
            }
        });
    }

    private void initializeFromOnlinePlayers() {
        Collection<? extends Player> onlinePlayers = Bukkit.getOnlinePlayers();
        if (onlinePlayers.isEmpty()) {
            if (cfgSharedHealth && !sharedHealthInitialized) {
                updateSharedHealth(20.0D);
            }

            if (cfgSharedHunger && !sharedHungerInitialized) {
                updateSharedHunger(20);
            }

            if (cfgSharedAbsorption && !sharedAbsorptionInitialized) {
                updateSharedAbsorption(0.0D);
            }

            saveSharedData();
            return;
        }

        if (cfgSharedHealth) {
            double lowestAliveHealth = findLowestAliveHealth(null);
            if (lowestAliveHealth >= 0.0D) {
                updateSharedHealth(lowestAliveHealth);
            } else if (!sharedHealthInitialized || sharedHealth <= 0.0D) {
                updateSharedHealth(resolvePlayerMaxHealth(onlinePlayers.iterator().next()));
            }
            applySharedHealthToAll(sharedHealth);
        }

        if (cfgSharedHunger) {
            Player lowestFoodPlayer = findPlayerWithLowestFood(null);
            if (lowestFoodPlayer != null) {
                updateSharedHunger(lowestFoodPlayer.getFoodLevel());
            } else if (!sharedHungerInitialized) {
                Player fallback = onlinePlayers.iterator().next();
                updateSharedHunger(fallback.getFoodLevel());
            }
            applySharedHungerToAll();
        }

        if (cfgSharedPotions) {
            setSharedPotionEffects(
                    findAnyPotionEffect(PotionEffectType.REGENERATION),
                    findAnyPotionEffect(PotionEffectType.HEALTH_BOOST),
                    findAnyPotionEffect(PotionEffectType.ABSORPTION));
            applySharedPotionEffects();
        }

        if (cfgSharedAbsorption) {
            double lowestAliveAbsorption = findLowestAliveAbsorption(null);
            if (lowestAliveAbsorption >= 0.0D) {
                updateSharedAbsorption(lowestAliveAbsorption);
            } else if (!sharedAbsorptionInitialized) {
                updateSharedAbsorption(0.0D);
            }
            applySharedAbsorptionToAll();
        }
    }

    private void syncPlayerToSharedState(Player player) {
        if (!player.isOnline()) {
            return;
        }

        if (cfgSharedPotions) {
            syncPlayerToSharedPotionEffects(player);
        }
        if (cfgSharedHealth) {
            syncPlayerToSharedHealth(player);
        }
        if (cfgSharedAbsorption) {
            syncPlayerToSharedAbsorption(player);
        }
        if (cfgSharedHunger) {
            syncPlayerToSharedHunger(player);
        }
    }

    private void syncJoiningPlayerToSharedState(Player player) {
        if (!player.isOnline()) {
            return;
        }

        if (cfgSharedDeath) {
            applyMissedSharedDeathStateIfNeeded(player);
        }
        syncPlayerToSharedState(player);
        if (cfgSharedDeath) {
            markPlayerDeathSynced(player.getUniqueId(), sharedDeathCounter);
        }
    }

    private void syncPlayerToSharedHealth(Player player) {
        double targetHealth = findLowestAliveHealth(player);
        if (targetHealth < 0.0D) {
            if (sharedHealthInitialized && sharedHealth > 0.0D) {
                targetHealth = sharedHealth;
            } else {
                targetHealth = resolvePlayerMaxHealth(player);
            }
        }

        updateSharedHealth(targetHealth);
        double clamped = clampHealthForPlayer(player, targetHealth);

        syncingHealth = true;
        try {
            if (clamped <= 0.0D) {
                player.setHealth(0.0D);
            } else if (!player.isDead()) {
                player.setHealth(clamped);
            }
        } finally {
            syncingHealth = false;
        }
    }

    private void syncPlayerToSharedHunger(Player player) {
        if (!cfgSharedHunger) {
            return;
        }

        ensureSharedHungerInitialized(player);

        int targetFood = sharedFoodLevel;

        Player lowestFoodPlayer = findPlayerWithLowestFood(player);
        if (lowestFoodPlayer != null) {
            targetFood = lowestFoodPlayer.getFoodLevel();
            updateSharedHunger(targetFood);
        }

        syncingHunger = true;
        try {
            player.setFoodLevel(clampFoodLevel(targetFood));
        } finally {
            syncingHunger = false;
        }
    }

    private void syncPlayerToSharedAbsorption(Player player) {
        if (!cfgSharedAbsorption) {
            return;
        }

        ensureSharedAbsorptionInitialized(player);

        double targetAbsorption = sharedAbsorption;
        double lowestAliveAbsorption = findLowestAliveAbsorption(player);
        if (lowestAliveAbsorption >= 0.0D) {
            targetAbsorption = lowestAliveAbsorption;
            updateSharedAbsorption(targetAbsorption);
        }

        player.setAbsorptionAmount(clampAbsorptionForPlayer(player, targetAbsorption));
    }

    private void syncHungerFromPlayer(Player player) {
        if (!player.isOnline()) {
            return;
        }

        if (!cfgSharedHunger) {
            return;
        }

        updateSharedHunger(player.getFoodLevel());
        applySharedHungerToAll();
    }

    private void syncSharedHealthFromDamagedPlayer(Player source) {
        syncSharedHealthFromDamagedPlayer(source, Double.NaN, Double.NaN);
    }

    private void syncSharedHealthFromDamagedPlayer(
            Player source, double sourceHealthBeforeDamage, double sourceAbsorptionBeforeDamage) {
        if (!source.isOnline()) {
            return;
        }

        if (!cfgSharedHealth && !cfgSharedAbsorption) {
            return;
        }

        if (massKillInProgress) {
            return;
        }

        if (cfgSharedHealth && (source.isDead() || source.getHealth() <= 0.0D)) {
            if (sharedHealthInitialized && sharedHealth <= EPSILON) {
                return;
            }
            updateSharedHealth(0.0D);
            if (cfgSharedAbsorption) {
                updateSharedAbsorption(0.0D);
            }
            killAllPlayers();
            return;
        }

        if (cfgSharedHealth) {
            ensureSharedHealthInitialized(source);
        }
        if (cfgSharedAbsorption) {
            ensureSharedAbsorptionInitialized(source);
        }

        double newSharedHealth = sharedHealth;
        double previousSharedHealth = sharedHealth;
        if (cfgSharedHealth) {
            double maxSharedHealth = resolveSharedHealthCap(source);
            newSharedHealth = Math.min(source.getHealth(), maxSharedHealth);
            updateSharedHealth(newSharedHealth);
        }

        double newSharedAbsorption = sharedAbsorption;
        double previousSharedAbsorption = sharedAbsorption;
        if (cfgSharedAbsorption) {
            double maxSharedAbsorption = resolveSharedAbsorptionCap(source);
            newSharedAbsorption = Math.min(source.getAbsorptionAmount(), maxSharedAbsorption);
            updateSharedAbsorption(newSharedAbsorption);
        }

        double sharedHealthLoss =
                cfgSharedHealth ? Math.max(0.0D, previousSharedHealth - newSharedHealth) : 0.0D;
        double sharedAbsorptionLoss =
                cfgSharedAbsorption ? Math.max(0.0D, previousSharedAbsorption - newSharedAbsorption) : 0.0D;

        double sharedLoss;
        if (cfgSharedHealth && cfgSharedAbsorption) {
            sharedLoss = computeSharedLoss(
                    source,
                    sourceHealthBeforeDamage,
                    sourceAbsorptionBeforeDamage,
                    previousSharedHealth,
                    previousSharedAbsorption,
                    newSharedHealth,
                    newSharedAbsorption);
        } else if (cfgSharedHealth) {
            if (!Double.isNaN(sourceHealthBeforeDamage)) {
                sharedLoss = Math.max(0.0D, sourceHealthBeforeDamage - source.getHealth());
            } else {
                sharedLoss = sharedHealthLoss;
            }
        } else {
            if (!Double.isNaN(sourceAbsorptionBeforeDamage)) {
                sharedLoss = Math.max(0.0D, sourceAbsorptionBeforeDamage - source.getAbsorptionAmount());
            } else {
                sharedLoss = sharedAbsorptionLoss;
            }
        }
        if (sharedLoss <= EPSILON) {
            return;
        }

        boolean absorptionOnlyLoss = sharedAbsorptionLoss > EPSILON && sharedHealthLoss <= EPSILON;
        sendSharedDamageActionBar(source.getName(), sharedLoss, absorptionOnlyLoss);
        applySharedStateToOthers(source, newSharedHealth, newSharedAbsorption, true);
    }

    private void syncSharedPotionEffectsFromSource(Player source) {
        if (!source.isOnline()) {
            return;
        }

        if (!cfgSharedPotions) {
            return;
        }

        setSharedPotionEffects(
                source.getPotionEffect(PotionEffectType.REGENERATION),
                source.getPotionEffect(PotionEffectType.HEALTH_BOOST),
                source.getPotionEffect(PotionEffectType.ABSORPTION));
        applySharedPotionEffects();
        if (cfgSharedAbsorption) {
            syncSharedAbsorptionFromPlayer(source);
        }
    }

    private void syncSharedAbsorptionFromPlayer(Player source) {
        if (!source.isOnline()) {
            return;
        }

        if (!cfgSharedAbsorption) {
            return;
        }

        ensureSharedAbsorptionInitialized(source);
        double maxSharedAbsorption = resolveSharedAbsorptionCap(source);
        double newSharedAbsorption = Math.min(source.getAbsorptionAmount(), maxSharedAbsorption);
        updateSharedAbsorption(newSharedAbsorption);
        applySharedAbsorptionToAll();
    }

    private double computeSharedLoss(
            Player source,
            double sourceHealthBeforeDamage,
            double sourceAbsorptionBeforeDamage,
            double previousSharedHealth,
            double previousSharedAbsorption,
            double newSharedHealth,
            double newSharedAbsorption) {
        if (!Double.isNaN(sourceHealthBeforeDamage) && !Double.isNaN(sourceAbsorptionBeforeDamage)) {
            double beforeTotal =
                    Math.max(0.0D, sourceHealthBeforeDamage) + Math.max(0.0D, sourceAbsorptionBeforeDamage);
            double afterTotal = Math.max(0.0D, source.getHealth()) + Math.max(0.0D, source.getAbsorptionAmount());
            return Math.max(0.0D, beforeTotal - afterTotal);
        }

        double previousSharedTotal = previousSharedHealth + previousSharedAbsorption;
        double newSharedTotal = newSharedHealth + newSharedAbsorption;
        return Math.max(0.0D, previousSharedTotal - newSharedTotal);
    }

    private void applySharedHealthToAll(double health) {
        if (!cfgSharedHealth) {
            return;
        }

        syncingHealth = true;
        try {
            for (Player online : Bukkit.getOnlinePlayers()) {
                if (online.isDead()) {
                    continue;
                }

                double clamped = clampHealthForPlayer(online, health);
                online.setHealth(Math.max(clamped, 0.0D));
            }
        } finally {
            syncingHealth = false;
        }
    }

    private void applySharedStateToOthers(
            Player source, double health, double absorptionAmount, boolean showDamageFeedback) {
        if (!cfgSharedHealth && !cfgSharedAbsorption) {
            return;
        }

        syncingHealth = cfgSharedHealth;
        try {
            for (Player online : Bukkit.getOnlinePlayers()) {
                if (online.isDead() || online.getUniqueId().equals(source.getUniqueId())) {
                    continue;
                }

                if (cfgSharedHealth) {
                    double clamped = clampHealthForPlayer(online, health);
                    if (clamped <= 0.0D) {
                        online.setHealth(0.0D);
                        continue;
                    }

                    online.setHealth(clamped);
                }

                if (cfgSharedAbsorption) {
                    online.setAbsorptionAmount(clampAbsorptionForPlayer(online, absorptionAmount));
                }
                if (showDamageFeedback) {
                    playDamageFeedback(online);
                }
            }
        } finally {
            syncingHealth = false;
        }
    }

    private void applySharedHungerToAll() {
        if (!cfgSharedHunger) {
            return;
        }

        syncingHunger = true;
        try {
            for (Player online : Bukkit.getOnlinePlayers()) {
                online.setFoodLevel(sharedFoodLevel);
            }
        } finally {
            syncingHunger = false;
        }
    }

    private void applySharedAbsorptionToAll() {
        if (!cfgSharedAbsorption) {
            return;
        }

        for (Player online : Bukkit.getOnlinePlayers()) {
            if (online.isDead()) {
                continue;
            }

            online.setAbsorptionAmount(clampAbsorptionForPlayer(online, sharedAbsorption));
        }
    }

    private void applySharedPotionEffects() {
        if (!cfgSharedPotions) {
            return;
        }

        syncingPotionEffects = true;
        try {
            for (Player online : Bukkit.getOnlinePlayers()) {
                applySharedPotionToPlayer(online, PotionEffectType.REGENERATION, sharedRegenEffect);
                applySharedPotionToPlayer(online, PotionEffectType.HEALTH_BOOST, sharedHealthBoostEffect);
                applySharedPotionToPlayer(online, PotionEffectType.ABSORPTION, sharedAbsorptionEffect);
            }
        } finally {
            syncingPotionEffects = false;
        }

        clampSharedHealthToCurrentCap();
        clampSharedAbsorptionToCurrentCap();
    }

    private void killAllPlayers() {
        massKillInProgress = true;
        syncingHealth = true;
        try {
            for (Player online : Bukkit.getOnlinePlayers()) {
                if (!online.isDead()) {
                    online.setHealth(0.0D);
                }
            }
        } finally {
            syncingHealth = false;
            massKillInProgress = false;
        }
    }

    private void applyMissedSharedDeathStateIfNeeded(Player player) {
        long syncedDeathCounter = getPlayerDeathSync(player.getUniqueId());
        if (syncedDeathCounter >= sharedDeathCounter) {
            return;
        }

        if (!player.hasPlayedBefore() && syncedDeathCounter == 0L) {
            return;
        }

        if (cfgWipeInventoryOnSharedDeath) {
            wipePlayerInventory(player);
        }
        if (cfgWipeEnderChestOnSharedDeath) {
            player.getEnderChest().clear();
        }
    }

    private void recordSharedDeathForOnlinePlayers() {
        sharedDeathCounter++;
        dataDirty = true;
        for (Player online : Bukkit.getOnlinePlayers()) {
            markPlayerDeathSynced(online.getUniqueId(), sharedDeathCounter);
        }
    }

    private long getPlayerDeathSync(UUID playerId) {
        return playerDeathSync.getOrDefault(playerId, 0L);
    }

    private void markPlayerDeathSynced(UUID playerId, long deathCounter) {
        Long previous = playerDeathSync.put(playerId, deathCounter);
        if (previous == null || previous != deathCounter) {
            dataDirty = true;
        }
    }

    private void wipePlayerInventory(Player player) {
        PlayerInventory inventory = player.getInventory();
        inventory.clear();
        inventory.setArmorContents(new ItemStack[] {null, null, null, null});
        inventory.setItemInOffHand(null);
    }

    private void wipeAllOnlineInventories() {
        for (Player online : Bukkit.getOnlinePlayers()) {
            wipePlayerInventory(online);
        }
    }

    private void wipeAllOnlineEnderChests() {
        for (Player online : Bukkit.getOnlinePlayers()) {
            online.getEnderChest().clear();
        }
    }

    private void clearAllGroundItems() {
        for (World world : Bukkit.getWorlds()) {
            for (Item item : world.getEntitiesByClass(Item.class)) {
                item.remove();
            }
        }
    }

    private void playDamageFeedback(Player player) {
        if (cfgDamageHurtAnimation) {
            player.sendHurtAnimation(0.0F);
        }
        if (cfgDamageSound) {
            player.playSound(player, Sound.ENTITY_PLAYER_HURT, 1.0F, 1.0F);
        }
    }

    private void sendSharedDamageActionBar(String playerName, double sharedDamage, boolean absorptionOnlyLoss) {
        if (!cfgDamageActionBar) {
            return;
        }

        String amountText = formatHeartsAmount(sharedDamage);
        NamedTextColor heartColor = absorptionOnlyLoss ? NamedTextColor.YELLOW : NamedTextColor.RED;
        Component message = Component.empty()
                .append(Component.text(playerName, NamedTextColor.YELLOW))
                .append(Component.text(" a perdu ", NamedTextColor.GRAY))
                .append(Component.text(amountText, NamedTextColor.WHITE))
                .append(Component.text("❤", heartColor));

        for (Player online : Bukkit.getOnlinePlayers()) {
            if (online.isDead()) {
                continue;
            }

            online.sendActionBar(message);
        }
    }

    private void broadcastSharedKilledChat(String playerName) {
        Component message = Component.empty()
                .append(Component.text(playerName, NamedTextColor.YELLOW))
                .append(Component.text(" s'est fait tuer."));

        Bukkit.broadcast(message);
    }

    private void broadcastJoinChat(String playerName) {
        Component message = Component.text(playerName + " s'est connecté.", NamedTextColor.GREEN);
        Bukkit.broadcast(message);
    }

    private void broadcastQuitChat(String playerName) {
        Component message = Component.text(playerName + " s'est déconnecté.", NamedTextColor.RED);
        Bukkit.broadcast(message);
    }

    private String formatHeartsAmount(double damageInHealthPoints) {
        double hearts = damageInHealthPoints / 2.0D;
        double displayed = Math.round(hearts * 2.0D) / 2.0D;
        if (Math.abs(displayed - Math.rint(displayed)) <= EPSILON) {
            return String.format(Locale.US, "%.0f", displayed);
        }

        return String.format(Locale.US, "%.1f", displayed);
    }

    private boolean isNaturalRegenController(Player player) {
        Player controller = null;
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (online.isDead()) {
                continue;
            }

            if (controller == null || online.getUniqueId().compareTo(controller.getUniqueId()) < 0) {
                controller = online;
            }
        }

        return controller != null && controller.getUniqueId().equals(player.getUniqueId());
    }

    private void ensureSharedHealthInitialized(Player referencePlayer) {
        if (sharedHealthInitialized) {
            return;
        }

        double lowestAlive = findLowestAliveHealth(null);
        if (lowestAlive >= 0.0D) {
            updateSharedHealth(lowestAlive);
            return;
        }

        if (referencePlayer != null) {
            updateSharedHealth(referencePlayer.getHealth());
            return;
        }

        updateSharedHealth(20.0D);
    }

    private void ensureSharedHungerInitialized(Player referencePlayer) {
        if (sharedHungerInitialized) {
            return;
        }

        Player lowestFoodPlayer = findPlayerWithLowestFood(null);
        if (lowestFoodPlayer != null) {
            updateSharedHunger(lowestFoodPlayer.getFoodLevel());
            return;
        }

        if (referencePlayer != null) {
            updateSharedHunger(referencePlayer.getFoodLevel());
            return;
        }

        updateSharedHunger(20);
    }

    private void ensureSharedAbsorptionInitialized(Player referencePlayer) {
        if (sharedAbsorptionInitialized) {
            return;
        }

        double lowestAliveAbsorption = findLowestAliveAbsorption(null);
        if (lowestAliveAbsorption >= 0.0D) {
            updateSharedAbsorption(lowestAliveAbsorption);
            return;
        }

        if (referencePlayer != null) {
            updateSharedAbsorption(referencePlayer.getAbsorptionAmount());
            return;
        }

        updateSharedAbsorption(0.0D);
    }

    private void setSharedPotionEffects(
            PotionEffect regenEffect, PotionEffect healthBoostEffect, PotionEffect absorptionEffect) {
        sharedRegenEffect = regenEffect == null ? null : copyPotionEffect(regenEffect);
        sharedHealthBoostEffect = healthBoostEffect == null ? null : copyPotionEffect(healthBoostEffect);
        sharedAbsorptionEffect = absorptionEffect == null ? null : copyPotionEffect(absorptionEffect);
    }

    private void syncPlayerToSharedPotionEffects(Player player) {
        if (!cfgSharedPotions) {
            return;
        }

        syncingPotionEffects = true;
        try {
            applySharedPotionToPlayer(player, PotionEffectType.REGENERATION, sharedRegenEffect);
            applySharedPotionToPlayer(player, PotionEffectType.HEALTH_BOOST, sharedHealthBoostEffect);
            applySharedPotionToPlayer(player, PotionEffectType.ABSORPTION, sharedAbsorptionEffect);
        } finally {
            syncingPotionEffects = false;
        }

        clampSharedHealthToCurrentCap();
        clampSharedAbsorptionToCurrentCap();
    }

    private double resolveSharedHealthCap(Player fallbackPlayer) {
        double minMaxHealth = Double.MAX_VALUE;
        for (Player online : Bukkit.getOnlinePlayers()) {
            double maxHealth = resolvePlayerMaxHealth(online);
            if (maxHealth < minMaxHealth) {
                minMaxHealth = maxHealth;
            }
        }

        if (minMaxHealth == Double.MAX_VALUE) {
            if (fallbackPlayer == null) {
                return 20.0D;
            }
            return resolvePlayerMaxHealth(fallbackPlayer);
        }

        return minMaxHealth;
    }

    private double resolveSharedAbsorptionCap(Player fallbackPlayer) {
        double minMaxAbsorption = Double.MAX_VALUE;
        for (Player online : Bukkit.getOnlinePlayers()) {
            double maxAbsorption = resolvePlayerMaxAbsorption(online);
            if (maxAbsorption < minMaxAbsorption) {
                minMaxAbsorption = maxAbsorption;
            }
        }

        if (minMaxAbsorption == Double.MAX_VALUE) {
            if (fallbackPlayer == null) {
                return 0.0D;
            }
            return resolvePlayerMaxAbsorption(fallbackPlayer);
        }

        return minMaxAbsorption;
    }

    private double findLowestAliveHealth(Player exclude) {
        double lowest = Double.MAX_VALUE;
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (exclude != null && online.getUniqueId().equals(exclude.getUniqueId())) {
                continue;
            }

            if (online.isDead()) {
                continue;
            }

            double health = online.getHealth();
            if (health > 0.0D && health < lowest) {
                lowest = health;
            }
        }

        return lowest == Double.MAX_VALUE ? -1.0D : lowest;
    }

    private double findLowestAliveAbsorption(Player exclude) {
        double lowest = Double.MAX_VALUE;
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (exclude != null && online.getUniqueId().equals(exclude.getUniqueId())) {
                continue;
            }

            if (online.isDead()) {
                continue;
            }

            double absorption = online.getAbsorptionAmount();
            if (absorption < lowest) {
                lowest = absorption;
            }
        }

        return lowest == Double.MAX_VALUE ? -1.0D : lowest;
    }

    private Player findPlayerWithLowestFood(Player exclude) {
        Player lowest = null;
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (exclude != null && online.getUniqueId().equals(exclude.getUniqueId())) {
                continue;
            }

            if (online.isDead()) {
                continue;
            }

            if (lowest == null) {
                lowest = online;
                continue;
            }

            if (online.getFoodLevel() < lowest.getFoodLevel()) {
                lowest = online;
            }
        }

        return lowest;
    }

    private PotionEffect findAnyPotionEffect(PotionEffectType type) {
        for (Player online : Bukkit.getOnlinePlayers()) {
            PotionEffect effect = online.getPotionEffect(type);
            if (effect != null) {
                return effect;
            }
        }

        return null;
    }

    private void applySharedPotionToPlayer(Player player, PotionEffectType type, PotionEffect effect) {
        if (effect == null) {
            player.removePotionEffect(type);
            return;
        }

        PotionEffect desired = copyPotionEffect(effect);
        PotionEffect current = player.getPotionEffect(type);
        if (current != null && samePotionEffect(current, desired)) {
            return;
        }

        player.removePotionEffect(type);
        player.addPotionEffect(desired);
    }

    private PotionEffect copyPotionEffect(PotionEffect effect) {
        return new PotionEffect(
                effect.getType(),
                effect.getDuration(),
                effect.getAmplifier(),
                effect.isAmbient(),
                effect.hasParticles(),
                effect.hasIcon());
    }

    private boolean samePotionEffect(PotionEffect first, PotionEffect second) {
        return first.getType() == second.getType()
                && first.getDuration() == second.getDuration()
                && first.getAmplifier() == second.getAmplifier()
                && first.isAmbient() == second.isAmbient()
                && first.hasParticles() == second.hasParticles()
                && first.hasIcon() == second.hasIcon();
    }

    private boolean isSharedPotionType(PotionEffect effect) {
        if (effect == null) {
            return false;
        }

        return effect.getType() == PotionEffectType.REGENERATION
                || effect.getType() == PotionEffectType.HEALTH_BOOST
                || effect.getType() == PotionEffectType.ABSORPTION;
    }

    private void clampSharedHealthToCurrentCap() {
        if (!cfgSharedHealth) {
            return;
        }

        if (!sharedHealthInitialized || sharedHealth <= 0.0D) {
            return;
        }

        double cap = resolveSharedHealthCap(null);
        if (sharedHealth > cap) {
            updateSharedHealth(cap);
            applySharedHealthToAll(cap);
        }
    }

    private void clampSharedAbsorptionToCurrentCap() {
        if (!cfgSharedAbsorption) {
            return;
        }

        if (!sharedAbsorptionInitialized || sharedAbsorption <= 0.0D) {
            return;
        }

        double cap = resolveSharedAbsorptionCap(null);
        if (sharedAbsorption > cap) {
            updateSharedAbsorption(cap);
            applySharedAbsorptionToAll();
        }
    }

    private void updateSharedHealth(double value) {
        double clamped = Math.max(0.0D, value);
        if (!sharedHealthInitialized || Math.abs(sharedHealth - clamped) > EPSILON) {
            dataDirty = true;
        }

        sharedHealth = clamped;
        sharedHealthInitialized = true;
    }

    private void updateSharedAbsorption(double value) {
        double clamped = Math.max(0.0D, value);
        if (!sharedAbsorptionInitialized || Math.abs(sharedAbsorption - clamped) > EPSILON) {
            dataDirty = true;
        }

        sharedAbsorption = clamped;
        sharedAbsorptionInitialized = true;
    }

    private void updateSharedHunger(int foodLevel) {
        int clampedFood = clampFoodLevel(foodLevel);

        if (!sharedHungerInitialized || sharedFoodLevel != clampedFood) {
            dataDirty = true;
        }

        sharedFoodLevel = clampedFood;
        sharedHungerInitialized = true;
    }

    private double clampHealthForPlayer(Player player, double health) {
        if (health <= 0.0D) {
            return 0.0D;
        }

        double max = resolvePlayerMaxHealth(player);
        return Math.min(health, max);

    }

    private double clampAbsorptionForPlayer(Player player, double absorption) {
        if (absorption <= 0.0D) {
            return 0.0D;
        }

        double max = resolvePlayerMaxAbsorption(player);
        return Math.min(absorption, max);

    }

    private int clampFoodLevel(int foodLevel) {
        return Math.max(0, Math.min(20, foodLevel));
    }

    private double resolvePlayerMaxHealth(Player player) {
        if (player.getAttribute(Attribute.MAX_HEALTH) == null) {
            return 20.0D;
        }

        return player.getAttribute(Attribute.MAX_HEALTH).getValue();
    }

    private double resolvePlayerMaxAbsorption(Player player) {
        if (player.getAttribute(Attribute.MAX_ABSORPTION) == null) {
            return Math.max(0.0D, player.getAbsorptionAmount());
        }

        return Math.max(0.0D, player.getAttribute(Attribute.MAX_ABSORPTION).getValue());
    }

    private void loadRuntimeConfig() {
        FileConfiguration config = getConfig();

        cfgSharedHealth = config.getBoolean("features.shared-health", true);
        cfgSharedHunger = config.getBoolean("features.shared-hunger", true);
        cfgSharedAbsorption = config.getBoolean("features.shared-absorption", true);
        cfgSharedPotions = config.getBoolean("features.shared-potions", true);
        cfgSharedDeath = config.getBoolean("features.shared-death", true);

        cfgPreventStackedRegen = config.getBoolean("health.prevent-stacked-regen", true);

        cfgSplitSprintJumpExhaustion = config.getBoolean("hunger.split-sprint-jump-exhaustion", true);

        cfgDamageActionBar = config.getBoolean("feedback.damage-action-bar", true);
        cfgDamageHurtAnimation = config.getBoolean("feedback.damage-hurt-animation", true);
        cfgDamageSound = config.getBoolean("feedback.damage-sound", true);

        cfgHideVanillaDeathMessage = config.getBoolean("messages.hide-vanilla-death-message", true);
        cfgHideVanillaJoinMessage = config.getBoolean("messages.hide-vanilla-join-message", true);
        cfgHideVanillaQuitMessage = config.getBoolean("messages.hide-vanilla-quit-message", true);
        cfgBroadcastSharedDeath = config.getBoolean("messages.broadcast-shared-death", true);
        cfgBroadcastJoin = config.getBoolean("messages.broadcast-join", true);
        cfgBroadcastQuit = config.getBoolean("messages.broadcast-quit", true);

        cfgWipeInventoryOnSharedDeath = config.getBoolean("shared-death.wipe-inventory", true);
        cfgWipeEnderChestOnSharedDeath = config.getBoolean("shared-death.wipe-ender-chest", true);
        cfgClearDropsOnSharedDeath = config.getBoolean("shared-death.clear-drops", true);
        cfgClearGroundItemsOnSharedDeath = config.getBoolean("shared-death.clear-ground-items", true);
        cfgResetSharedHungerOnSharedDeath = config.getBoolean("shared-death.reset-shared-hunger-to-full", true);

        cfgSyncOnlinePlayersOnEnable = config.getBoolean("startup.sync-online-players-on-enable", true);

        long autosaveIntervalSeconds = config.getLong("storage.autosave-interval-seconds", 60L);
        cfgAutosaveIntervalTicks = autosaveIntervalSeconds <= 0L ? 0L : autosaveIntervalSeconds * 20L;
    }

    private boolean hasAnySharedSyncFeatureEnabled() {
        return cfgSharedHealth || cfgSharedHunger || cfgSharedAbsorption || cfgSharedPotions;
    }

    private void loadSharedData() {
        dataFile = new File(getDataFolder(), DATA_FILE_NAME);
        if (!dataFile.exists()) {
            dataConfig = new YamlConfiguration();
            sharedHealthInitialized = false;
            sharedHungerInitialized = false;
            sharedAbsorptionInitialized = false;
            sharedDeathCounter = 0L;
            playerDeathSync.clear();
            dataDirty = false;
            return;
        }

        dataConfig = YamlConfiguration.loadConfiguration(dataFile);
        sharedHealthInitialized = dataConfig.getBoolean(DATA_HAS_HEALTH, false);
        sharedHealth = Math.max(0.0D, dataConfig.getDouble(DATA_SHARED_HEALTH, 20.0D));

        sharedHungerInitialized = dataConfig.getBoolean(DATA_HAS_HUNGER, false);
        sharedFoodLevel = clampFoodLevel(dataConfig.getInt(DATA_SHARED_FOOD, 20));

        sharedAbsorptionInitialized = dataConfig.getBoolean(DATA_HAS_ABSORPTION, false);
        sharedAbsorption = Math.max(0.0D, dataConfig.getDouble(DATA_SHARED_ABSORPTION, 0.0D));

        sharedDeathCounter = Math.max(0L, dataConfig.getLong(DATA_SHARED_DEATH_COUNTER, 0L));
        playerDeathSync.clear();
        ConfigurationSection deathSyncSection = dataConfig.getConfigurationSection(DATA_PLAYER_DEATH_SYNC);
        if (deathSyncSection != null) {
            for (String key : deathSyncSection.getKeys(false)) {
                try {
                    UUID playerId = UUID.fromString(key);
                    long syncedCounter = Math.max(0L, deathSyncSection.getLong(key, 0L));
                    playerDeathSync.put(playerId, syncedCounter);
                } catch (IllegalArgumentException exception) {
                    getLogger().warning("Ignoring invalid player uuid in death sync data: " + key);
                }
            }
        }
        dataDirty = false;
    }

    private void saveSharedData() {
        if (!dataDirty && dataFile.exists()) {
            return;
        }

        if (!getDataFolder().exists() && !getDataFolder().mkdirs()) {
            getLogger().warning("Could not create plugin data folder.");
            return;
        }

        if (dataConfig == null) {
            dataConfig = new YamlConfiguration();
        }

        dataConfig.set(DATA_HAS_HEALTH, sharedHealthInitialized);
        dataConfig.set(DATA_SHARED_HEALTH, sharedHealth);

        dataConfig.set(DATA_HAS_HUNGER, sharedHungerInitialized);
        dataConfig.set(DATA_SHARED_FOOD, sharedFoodLevel);
        dataConfig.set(DATA_HAS_ABSORPTION, sharedAbsorptionInitialized);
        dataConfig.set(DATA_SHARED_ABSORPTION, sharedAbsorption);
        dataConfig.set(DATA_SHARED_DEATH_COUNTER, sharedDeathCounter);

        dataConfig.set(DATA_PLAYER_DEATH_SYNC, null);
        for (Map.Entry<UUID, Long> entry : playerDeathSync.entrySet()) {
            dataConfig.set(DATA_PLAYER_DEATH_SYNC + "." + entry.getKey(), Math.max(0L, entry.getValue()));
        }

        try {
            dataConfig.save(dataFile);
            dataDirty = false;
        } catch (IOException exception) {
            getLogger().warning("Could not save shared health data: " + exception.getMessage());
        }
    }
}
