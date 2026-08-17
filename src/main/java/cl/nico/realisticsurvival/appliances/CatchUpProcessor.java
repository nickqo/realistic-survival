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
 * <b>Items que entran "viejos":</b> un item puede llegar al electrodomestico con su propio
 * watermark interno mas atrasado que el {@code lastCalcDay} del electrodomestico — ej. un
 * alimento que llevaba mucho tiempo quieto en el inventario del jugador sin que nada lo
 * recalculara (ver {@code inventory.InventoryListener}), y recien se toca al insertarlo.
 * Por eso, antes de aplicar el tramo frio/ambiente del periodo reciente, cada item se
 * "sincroniza" primero hasta {@code lastCalcDay} a velocidad AMBIENTE (nunca con el
 * multiplicador frio, porque no hay forma de saber si ese tramo previo estuvo protegido).
 * Si el item ya estaba sincronizado (caso normal: watermark == lastCalcDay), este paso no
 * hace nada. Sin este paso, un item "viejo" se pudriria instantaneamente al primer catch-up
 * (el multiplicador frio no alcanza a compensar un backlog de cientos de dias).
 * <p>
 * <b>Consumo fraccionario de hielo:</b> el hielo es un recurso discreto (no se puede gastar
 * "0.3 hielo"), pero los dias frios se calculan con precision de hora. En vez de redondear
 * hacia arriba en cada apertura (eso gastaria un hielo entero cada vez que se reabre el
 * electrodomestico, aunque sea a los pocos segundos), el progreso fraccionario se acumula
 * entre aperturas via {@code iceFractionProgress} y solo se consume un hielo entero cuando
 * el acumulado realmente cruza un dia completo — asi, mientras el suministro nunca se corte,
 * cada hielo se consume exactamente a la misma hora del dia en que se coloco el primero
 * (el acumulador es una suma continua desde ese instante, no algo atado al reloj de
 * RealisticSeasons).
 * <p>
 * <b>Reinicio del cronometro al quedarse sin hielo:</b> si el slot de combustible queda
 * vacio (sea porque ya estaba vacio al arrancar este catch-up, o porque se termino de
 * consumir recien ahora), el progreso fraccionario devuelto es siempre 0.0, nunca un
 * remanente. Sin esto, un hielo colocado despues de un corte de suministro heredaria la
 * fraccion "a medio consumir" del hielo anterior, y se gastaria antes de completar un dia
 * completo desde que se coloco — el cronometro solo debe empezar a correr desde el momento
 * en que hay hielo puesto de nuevo.
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
     * @param lastCalcDay dia in-game (con fraccion horaria) en que se calculo por ultima
     *                    vez (al cerrar la GUI)
     * @param currentDay dia in-game absoluto actual (con fraccion horaria)
     * @param iceFractionProgress dias frios acumulados que todavia no alcanzaron a
     *                    consumir un hielo entero (ver {@link #process} para el porque);
     *                    0.0 si nunca se acumulo nada.
     * @param isFreezer  true si es Congelador (frio = detiene pudricion + marca frozen),
     *                   false si es Refrigerador (frio = x3 duracion, ver
     *                   {@link FoodManager#FRIDGE_MULTIPLIER})
     * @return el nuevo {@code iceFractionProgress} (el sobrante que aun no llego a
     *         consumir un hielo entero) — el llamador debe persistirlo y pasarlo de vuelta
     *         en la proxima llamada. Siempre 0.0 si el slot de combustible termina vacio
     *         (ver Javadoc de la clase, "Reinicio del cronometro al quedarse sin hielo").
     */
    public double process(ItemStack[] contents, double lastCalcDay, double currentDay,
                           double iceFractionProgress, boolean isFreezer) {
        double elapsedDays = Math.max(0.0, currentDay - lastCalcDay);

        ItemStack fuel = contents[ApplianceGUI.FUEL_SLOT];
        double iceDaysAvailable = (fuel == null) ? 0 : fuel.getAmount();
        // coldDays queda fraccionario (protege exactamente el tiempo transcurrido, hasta
        // el tope de hielo disponible).
        double coldDays = Math.min(elapsedDays, iceDaysAvailable);
        double ambientDays = elapsedDays - coldDays;
        double coldMultiplier = isFreezer ? FoodManager.FREEZER_MULTIPLIER : FoodManager.FRIDGE_MULTIPLIER;

        for (int slot = 0; slot < ApplianceGUI.FUEL_SLOT; slot++) {
            ItemStack stack = contents[slot];
            if (stack == null || (!foodManager.isTrackable(stack) && !foodManager.isTracked(stack))) {
                continue;
            }

            // Sincroniza hasta lastCalcDay a velocidad ambiente (ver Javadoc de la clase);
            // no-op si el item ya estaba al dia.
            int synced = foodManager.calculateFreshness(stack, lastCalcDay, FoodManager.AMBIENT_MULTIPLIER);
            stack = transformIfRotten(stack, synced);

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

        // El hielo es un recurso discreto: no se puede consumir una fraccion. En vez de
        // redondear hacia arriba en CADA apertura (bug real: reabrir el electrodomestico
        // varias veces seguidas en pocos segundos reales gastaba un hielo entero cada vez,
        // aunque cada tramo individual fuera minusculo), el progreso fraccionario se
        // ACUMULA entre aperturas y solo se consume hielo entero cuando ese acumulado
        // realmente cruza un dia completo — igual que llenar un balde gota a gota.
        double totalProgress = iceFractionProgress + coldDays;
        int wholeIceConsumed = (int) Math.floor(totalProgress);
        double remainingFraction = totalProgress - wholeIceConsumed;

        if (fuel != null && wholeIceConsumed > 0) {
            int consumed = Math.min(fuel.getAmount(), wholeIceConsumed);
            int remaining = fuel.getAmount() - consumed;
            if (remaining <= 0) {
                contents[ApplianceGUI.FUEL_SLOT] = null;
            } else {
                fuel.setAmount(remaining);
            }
        }

        // Sin combustible al terminar (no habia desde el arranque de este catch-up, o se
        // termino de consumir recien ahora): el cronometro se reinicia del todo. El proximo
        // hielo que se coloque debe empezar a contar desde CERO en el instante exacto en
        // que se pone, no heredar una fraccion vieja de "cuanto faltaba" del hielo anterior.
        if (contents[ApplianceGUI.FUEL_SLOT] == null) {
            return 0.0;
        }

        return remainingFraction;
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
