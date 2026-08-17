package cl.nico.realisticsurvival.appliances;

import cl.nico.realisticsurvival.food.FoodManager;
import org.bukkit.inventory.ItemStack;

/**
 * Motor de calculo pasivo (catch-up logic) para electrodomesticos (Refrigerador,
 * Congelador). Responsabilidad unica: dado el tiempo transcurrido desde el ultimo calculo,
 * resolver matematicamente:
 * <ul>
 *     <li>Cuanta cantidad de Hielo/Hielo Azul ({@link ApplianceGUI#FUEL_SLOT}) se consumio
 *         (1 unidad de hielo = 1 dia in-game de frio).</li>
 *     <li>Si el hielo se agoto antes de que pasara todo el tiempo, cuanto tiempo el
 *         contenido estuvo "frio" vs. a "temperatura ambiente", aplicando la pudricion
 *         combinada de ambos periodos (via {@link FoodManager}).</li>
 * </ul>
 * No hay ningun {@code BukkitRunnable} periodico: este procesador solo se ejecuta cuando
 * {@link ApplianceGUI#open} lo invoca, nunca en background.
 * <p>
 * Nota de diseño: mientras el electrodomestico esta cerrado es el UNICO momento en que su
 * contenido puede cambiar (no hay forma de tocarlo sin abrir la GUI), por lo que el
 * "ultimo dia calculado" del electrodomestico coincide siempre con el watermark interno de
 * cada item que contiene — esto permite tratar el periodo frio/ambiente como un tramo unico
 * y consistente para todos los slots, sin arrastrar datos obsoletos.
 */
public final class CatchUpProcessor {

    private final FoodManager foodManager;

    public CatchUpProcessor(FoodManager foodManager) {
        this.foodManager = foodManager;
    }

    /**
     * Procesa el contenido de un electrodomestico (mutando {@code contents} in-place):
     * consume hielo segun los dias transcurridos y aplica la pudricion correspondiente a
     * cada slot de comida, combinando tramo frio + tramo a temperatura ambiente si el
     * hielo se agoto en el camino.
     *
     * @param contents   contenido actual (tamaño {@link ApplianceGUI#SIZE}, slot
     *                   {@link ApplianceGUI#FUEL_SLOT} = combustible de hielo)
     * @param lastCalcDay dia in-game en que se calculo por ultima vez (al cerrar la GUI)
     * @param currentDay dia in-game absoluto actual
     * @param isFreezer  true si es Congelador (frio = detiene pudricion + marca frozen),
     *                   false si es Refrigerador (frio = x3 duracion, ver
     *                   {@link FoodManager#FRIDGE_MULTIPLIER})
     */
    public void process(ItemStack[] contents, long lastCalcDay, long currentDay, boolean isFreezer) {
        long elapsedDays = currentDay - lastCalcDay;
        if (elapsedDays <= 0) {
            return;
        }

        ItemStack fuel = contents[ApplianceGUI.FUEL_SLOT];
        long iceDaysAvailable = (fuel == null) ? 0 : fuel.getAmount();
        long coldDays = Math.min(elapsedDays, iceDaysAvailable);
        long ambientDays = elapsedDays - coldDays;
        double coldMultiplier = isFreezer ? FoodManager.FREEZER_MULTIPLIER : FoodManager.FRIDGE_MULTIPLIER;

        for (int slot = 0; slot < ApplianceGUI.FUEL_SLOT; slot++) {
            ItemStack stack = contents[slot];
            if (stack == null || (!foodManager.isTrackable(stack) && !foodManager.isTracked(stack))) {
                continue;
            }
            if (coldDays > 0) {
                int fresh = foodManager.calculateFreshness(stack, lastCalcDay + coldDays, coldMultiplier);
                stack = transformIfRotten(stack, fresh);
            }
            if (ambientDays > 0) {
                // Si el tramo frio era de Congelador, calculateFreshness ya resuelve el
                // descongelamiento progresivo (FoodManager#THAW_DAYS) antes de retomar
                // la pudricion normal dentro de este mismo tramo ambiente.
                int fresh = foodManager.calculateFreshness(stack, currentDay, FoodManager.AMBIENT_MULTIPLIER);
                stack = transformIfRotten(stack, fresh);
            }
            contents[slot] = stack;
        }

        if (fuel != null && coldDays > 0) {
            int consumed = (int) Math.min(fuel.getAmount(), coldDays);
            int remaining = fuel.getAmount() - consumed;
            if (remaining <= 0) {
                contents[ApplianceGUI.FUEL_SLOT] = null;
            } else {
                fuel.setAmount(remaining);
            }
        }
    }

    /**
     * Si la frescura calculada llego a 0%, transforma el stack a Restos Podridos. Necesario
     * porque {@link FoodManager#transformToRotten} devuelve una referencia nueva en vez de
     * mutar el ItemStack in-place ({@link ItemStack#setType} esta deprecado en Paper 1.21).
     */
    private ItemStack transformIfRotten(ItemStack stack, int freshness) {
        if (foodManager.getTier(freshness) == FoodManager.SpoilageTier.PODRIDO) {
            return foodManager.transformToRotten(stack);
        }
        return stack;
    }
}
