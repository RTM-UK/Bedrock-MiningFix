package com.RTM.bedrockminingfix;

import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.lang.reflect.Method;
import java.util.UUID;
import java.util.logging.Level;

public final class BedrockMiningFixPlugin extends JavaPlugin {
    private static final String FLOODGATE_PLUGIN_NAME = "floodgate";
    private static final String FLOODGATE_API_CLASS = "org.geysermc.floodgate.api.FloodgateApi";
    private static final String GET_INSTANCE_METHOD = "getInstance";
    private static final String IS_FLOODGATE_PLAYER_METHOD = "isFloodgatePlayer";

    private NamespacedKey markerKey;
    private NamespacedKey notifiedKey;
    private Object floodgateApiInstance;
    private Method isFloodgatePlayerMethod;

    private boolean enabledByConfig;
    private int requiredEfficiencyLevel;
    private int requiredHasteAmplifier;
    private int miningFatigueAmplifier;
    private int checkIntervalTicks;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        enabledByConfig = getConfig().getBoolean("enabled", true);
        if (!enabledByConfig) {
            getLogger().info("Plugin disabled in config.yml.");
            return;
        }

        requiredEfficiencyLevel = getConfig().getInt("required-efficiency-level", 7);
        requiredHasteAmplifier = getConfig().getInt("required-haste-amplifier", 1);
        miningFatigueAmplifier = getConfig().getInt("applied-mining-fatigue-amplifier", 0);
        checkIntervalTicks = Math.max(1, getConfig().getInt("check-interval-ticks", 20));

        markerKey = new NamespacedKey(this, "applied_mining_fatigue");
        notifiedKey = new NamespacedKey(this, "notified_requirements");

        if (!setupFloodgateApi()) {
            getLogger().severe("Floodgate was not detected. Disabling plugin.");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        getLogger().info("BedrockMiningFix enabled. Bedrock players with Efficiency 7 pickaxes and Haste II will receive Mining Fatigue I.");
        Bukkit.getScheduler().runTaskTimer(this, this::applyEffectsToPlayers, checkIntervalTicks, checkIntervalTicks);
    }

    private boolean setupFloodgateApi() {
        if (Bukkit.getPluginManager().getPlugin(FLOODGATE_PLUGIN_NAME) == null) {
            return false;
        }

        try {
            Class<?> floodgateApiClass = Class.forName(FLOODGATE_API_CLASS);
            Method getInstanceMethod = floodgateApiClass.getMethod(GET_INSTANCE_METHOD);
            floodgateApiInstance = getInstanceMethod.invoke(null);
            isFloodgatePlayerMethod = floodgateApiClass.getMethod(IS_FLOODGATE_PLAYER_METHOD, UUID.class);
            return true;
        } catch (ReflectiveOperationException exception) {
            getLogger().log(Level.WARNING, "Could not access the Floodgate API. Make sure Floodgate is installed and compatible.", exception);
            return false;
        }
    }

    private void applyEffectsToPlayers() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!isBedrockPlayer(player)) {
                clearMiningFatigue(player);
                clearConditionNotice(player);
                continue;
            }

            if (shouldApplyMiningFatigue(player)) {
                applyMiningFatigue(player);
                notifyWhenRequirementsMet(player);
            } else {
                clearMiningFatigue(player);
                clearConditionNotice(player);
            }
        }
    }

    private boolean isBedrockPlayer(Player player) {
        if (floodgateApiInstance == null || isFloodgatePlayerMethod == null) {
            return false;
        }

        try {
            return (boolean) isFloodgatePlayerMethod.invoke(floodgateApiInstance, player.getUniqueId());
        } catch (ReflectiveOperationException exception) {
            getLogger().log(Level.WARNING, "Unable to check Floodgate state for " + player.getName(), exception);
            return false;
        }
    }

    private boolean shouldApplyMiningFatigue(Player player) {
        ItemStack itemInHand = player.getInventory().getItemInMainHand();
        if (itemInHand == null || itemInHand.getType().isAir()) {
            return false;
        }

        if (!isPickaxe(itemInHand)) {
            return false;
        }

        if (!hasRequiredEfficiency(itemInHand)) {
            return false;
        }

        var hasteEffect = player.getPotionEffect(PotionEffectType.HASTE);
        return hasteEffect != null && hasteEffect.getAmplifier() >= requiredHasteAmplifier;
    }

    private boolean isPickaxe(ItemStack item) {
        String materialName = item.getType().name();
        return materialName.endsWith("_PICKAXE") && materialName.contains("PICKAXE");
    }

    private boolean hasRequiredEfficiency(ItemStack item) {
        if (!item.hasItemMeta()) {
            return false;
        }

        return item.getItemMeta().getEnchantLevel(Enchantment.EFFICIENCY) >= requiredEfficiencyLevel;
    }

    private void applyMiningFatigue(Player player) {
        boolean alreadyMarked = player.getPersistentDataContainer().getOrDefault(markerKey, PersistentDataType.BYTE, (byte) 0) == 1;
        if (alreadyMarked) {
            return;
        }

        player.addPotionEffect(new PotionEffect(PotionEffectType.MINING_FATIGUE, 40, miningFatigueAmplifier, false, false));
        player.getPersistentDataContainer().set(markerKey, PersistentDataType.BYTE, (byte) 1);
    }

    private void clearMiningFatigue(Player player) {
        byte marker = player.getPersistentDataContainer().getOrDefault(markerKey, PersistentDataType.BYTE, (byte) 0);
        if (marker != 1) {
            return;
        }

        player.removePotionEffect(PotionEffectType.MINING_FATIGUE);
        player.getPersistentDataContainer().remove(markerKey);
    }

    private void notifyWhenRequirementsMet(Player player) {
        byte notified = player.getPersistentDataContainer().getOrDefault(notifiedKey, PersistentDataType.BYTE, (byte) 0);
        if (notified == 1) {
            return;
        }

        player.sendMessage("You meet the Bedrock mining requirements: Efficiency 7 and Haste II.");
        player.getPersistentDataContainer().set(notifiedKey, PersistentDataType.BYTE, (byte) 1);
    }

    private void clearConditionNotice(Player player) {
        byte notified = player.getPersistentDataContainer().getOrDefault(notifiedKey, PersistentDataType.BYTE, (byte) 0);
        if (notified != 1) {
            return;
        }

        player.getPersistentDataContainer().remove(notifiedKey);
    }
}
