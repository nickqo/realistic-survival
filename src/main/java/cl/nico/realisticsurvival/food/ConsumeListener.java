package cl.nico.realisticsurvival.food;

import cl.nico.realisticsurvival.api.time.TimeProvider;
import cl.nico.realisticsurvival.food.FoodManager.SpoilageTier;
import io.papermc.paper.datacomponent.item.FoodProperties;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

/**
 * Listener puro: intercepta {@link PlayerItemConsumeEvent}, delega el calculo de frescura
 * a {@link FoodManager} y aplica las consecuencias de gameplay (nutricion/saturacion segun
 * tier, efectos de pocion, penalizacion por item congelado). No contiene logica matematica
 * propia de descomposicion (SRP) — esa responsabilidad vive integramente en
 * {@link FoodManager}; esta clase solo orquesta el evento y traduce tiers a gameplay.
 * <p>
 * Se toma control total del consumo (se cancela el evento vanilla) porque la nutricion real
 * depende de la frescura calculada en el momento, no del valor fijo del item.
 */
public final class ConsumeListener implements Listener {

    private static final float TIER_FACTOR_PASADO = 0.65f;
    private static final float TIER_FACTOR_MAL_ESTADO = 0.15f;
    private static final float FROZEN_PENALTY_FACTOR = 0.2f;

    private final FoodManager foodManager;
    private final TimeProvider timeProvider;

    public ConsumeListener(FoodManager foodManager, TimeProvider timeProvider) {
        this.foodManager = foodManager;
        this.timeProvider = timeProvider;
    }

    @EventHandler(ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent event) {
        if (!foodManager.isTrackable(event.getItem())) {
            // No es un alimento gestionado por este sistema (pociones, leche, etc.).
            return;
        }

        Player player = event.getPlayer();
        event.setCancelled(true);

        // Se opera sobre el stack real de la mano (no sobre event.getItem(), que es solo
        // informativo) para poder escribir de vuelta el resultado (frescura/transformacion)
        // de forma consistente.
        EquipmentSlot hand = event.getHand();
        boolean offHand = hand == EquipmentSlot.OFF_HAND;
        ItemStack handStack = offHand
                ? player.getInventory().getItemInOffHand()
                : player.getInventory().getItemInMainHand();

        double currentDay = timeProvider.getCurrentDay(player.getWorld());
        int freshness = foodManager.calculateFreshness(handStack, currentDay, FoodManager.AMBIENT_MULTIPLIER);
        SpoilageTier tier = foodManager.getTier(freshness);
        boolean frozen = foodManager.isFrozen(handStack);

        applyNutrition(player, handStack, tier, frozen);
        applyTierEffects(tier, player);
        consumeOne(player, hand, handStack, tier);
    }

    /**
     * Calcula la nutricion/saturacion a partir de los valores vanilla del propio item
     * (componente {@code minecraft:food}), escalados segun el tier y la penalizacion de
     * item congelado (seccion 4.3: cae al 20% si se consume/cocina estando frozen).
     */
    private void applyNutrition(Player player, ItemStack item, SpoilageTier tier, boolean frozen) {
        FoodProperties base = foodManager.getBaseFoodProperties(item);
        if (base == null) {
            return;
        }

        float factor = switch (tier) {
            case FRESCO -> 1.0f;
            case PASADO -> TIER_FACTOR_PASADO;
            case MAL_ESTADO -> TIER_FACTOR_MAL_ESTADO;
            // Nutricion nula: si aca el tier es PODRIDO, consumeOne ya transforma el
            // stack restante a Restos Podridos (Material.ROTTEN_FLESH).
            case PODRIDO -> 0.0f;
        };
        if (frozen) {
            factor *= FROZEN_PENALTY_FACTOR;
        }

        int nutritionGain = Math.round(base.nutrition() * factor);
        float saturationGain = base.saturation() * factor;

        int newFoodLevel = Math.min(20, player.getFoodLevel() + nutritionGain);
        player.setFoodLevel(newFoodLevel);
        player.setSaturation(Math.min(newFoodLevel, player.getSaturation() + saturationGain));
    }

    /**
     * Traduce un {@link SpoilageTier} a los efectos de pocion que describe la seccion 2
     * del documento de arquitectura.
     */
    private void applyTierEffects(SpoilageTier tier, Player player) {
        switch (tier) {
            case FRESCO, PASADO -> {
                // Sin penalizaciones adicionales mas alla del ajuste de nutricion.
            }
            case MAL_ESTADO -> {
                player.addPotionEffect(new PotionEffect(PotionEffectType.HUNGER, 20 * 10, 0));
                if (Math.random() < 0.25) {
                    player.addPotionEffect(new PotionEffect(PotionEffectType.NAUSEA, 20 * 5, 0));
                }
            }
            case PODRIDO -> {
                player.addPotionEffect(new PotionEffect(PotionEffectType.POISON, 20 * 4, 0));
                player.addPotionEffect(new PotionEffect(PotionEffectType.HUNGER, 20 * 15, 1));
            }
        }
    }

    /**
     * Descuenta manualmente una unidad del item que el jugador tiene en la mano usada (ya
     * que al cancelar {@link PlayerItemConsumeEvent} Minecraft no lo hace por nosotros), y
     * si el remanente quedo podrido, lo transforma. Siempre escribe el resultado de vuelta
     * en la mano del jugador explicitamente — no se asume que {@code handStack} sea una
     * referencia "viva" al slot real.
     */
    private void consumeOne(Player player, EquipmentSlot hand, ItemStack handStack, SpoilageTier tier) {
        boolean offHand = hand == EquipmentSlot.OFF_HAND;
        int remaining = handStack.getAmount() - 1;

        ItemStack result = null;
        if (remaining > 0) {
            handStack.setAmount(remaining);
            result = tier == SpoilageTier.PODRIDO ? foodManager.transformToRotten(handStack) : handStack;
        }

        if (offHand) {
            player.getInventory().setItemInOffHand(result);
        } else {
            player.getInventory().setItemInMainHand(result);
        }
    }
}
