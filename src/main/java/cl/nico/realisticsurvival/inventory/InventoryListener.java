package cl.nico.realisticsurvival.inventory;

import cl.nico.realisticsurvival.api.time.TimeProvider;
import cl.nico.realisticsurvival.food.FoodManager;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/**
 * Listener puro encargado del "stacking" (fusion) de alimentos con distinta frescura.
 * <p>
 * En 1.21, Minecraft no agrupa items cuyos Componentes/PDC difieren, por lo que un stack
 * de comida "fresca" y otro "pasada" del mismo tipo no se fusionan de forma vanilla (caen
 * en un swap o quedan separados). Esta clase intercepta {@link InventoryClickEvent} y
 * {@link EntityPickupItemEvent}, calcula el promedio ponderado de frescura via
 * {@link FoodManager}, y reescribe manualmente el ItemStack resultante. No contiene la
 * formula matematica de descomposicion en si (vive en FoodManager) — solo orquesta el
 * evento y el promedio ponderado propio del stacking (SRP, seccion 3 del documento).
 * <p>
 * <b>Inicializacion perezosa fuera de merges:</b> ademas de los merges, esta clase
 * aprovecha cualquier click/pickup/conexion para "etiquetar" (100% fresco) alimentos que
 * todavia no fueron tocados por el sistema — asi se ven el Lore/barra apenas el jugador
 * interactua con ellos, sin depender de que justo intente fusionar dos stacks. Un item
 * dado por comando ({@code /give}) y jamas tocado (ni un click, ni recogido del suelo, ni
 * una reconexion) puede seguir sin etiquetar hasta la primera interaccion real — es una
 * limitacion conocida del enfoque perezoso (ver seccion 1 del documento de arquitectura).
 */
public final class InventoryListener implements Listener {

    private final FoodManager foodManager;
    private final TimeProvider timeProvider;

    public InventoryListener(FoodManager foodManager, TimeProvider timeProvider) {
        this.foodManager = foodManager;
        this.timeProvider = timeProvider;
    }

    @EventHandler(ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        ItemStack cursor = event.getCursor();
        ItemStack current = event.getCurrentItem();

        if (!isMergeableFood(current, cursor)) {
            // No hay fusion que hacer, pero igual aprovechamos el click para etiquetar
            // cualquiera de los dos items si todavia no fue tocado por el sistema.
            long currentDay = timeProvider.getCurrentDay(event.getWhoClicked().getWorld());
            lazyTagIfNeeded(current, currentDay);
            lazyTagIfNeeded(cursor, currentDay);
            return;
        }

        // Solo intervenimos en los clicks que vanilla usaria para apilar/intercambiar;
        // el resto (clicks en otros slots, shift-click a otro inventario, etc.) sigue igual.
        InventoryAction action = event.getAction();
        if (action != InventoryAction.PLACE_ALL
                && action != InventoryAction.PLACE_SOME
                && action != InventoryAction.SWAP_WITH_CURSOR) {
            return;
        }

        event.setCancelled(true);

        long currentDay = timeProvider.getCurrentDay(event.getWhoClicked().getWorld());
        ItemStack[] merged = mergeStacks(current, cursor, currentDay);

        event.setCurrentItem(merged[0]);
        event.getWhoClicked().setItemOnCursor(merged[1]);
    }

    /**
     * Al conectarse, etiqueta cualquier alimento del inventario que todavia no haya sido
     * tocado por el sistema (ej. items dados por comando mientras el jugador estaba
     * offline, o de una sesion anterior a instalar el plugin).
     */
    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Inventory inventory = event.getPlayer().getInventory();
        long currentDay = timeProvider.getCurrentDay(event.getPlayer().getWorld());
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            lazyTagIfNeeded(inventory.getItem(slot), currentDay);
        }
    }

    /**
     * Si el item es un alimento gestionado por el sistema pero todavia no fue trackeado
     * (ej. recien dado por {@code /give}), lo marca como 100% fresco "ahora" para que su
     * Lore/barra sean visibles de inmediato. Muta {@code item} in-place.
     */
    private void lazyTagIfNeeded(ItemStack item, long currentDay) {
        if (item != null && foodManager.isTrackable(item) && !foodManager.isTracked(item)) {
            foodManager.calculateFreshness(item, currentDay, FoodManager.AMBIENT_MULTIPLIER);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityPickupItem(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }

        ItemStack ground = event.getItem().getItemStack();
        if (!foodManager.isTrackable(ground) && !foodManager.isTracked(ground)) {
            return;
        }

        Inventory inventory = player.getInventory();
        long currentDay = timeProvider.getCurrentDay(player.getWorld());

        for (int slot = 0; slot < inventory.getSize(); slot++) {
            ItemStack existing = inventory.getItem(slot);
            if (existing == null || existing.getAmount() >= existing.getMaxStackSize()) {
                continue;
            }
            if (!isMergeableFood(existing, ground)) {
                continue;
            }

            ItemStack[] merged = mergeStacks(existing, ground, currentDay);
            inventory.setItem(slot, merged[0]);

            event.setCancelled(true);
            if (merged[1] == null) {
                event.getItem().remove();
            } else {
                event.getItem().setItemStack(merged[1]);
            }
            // Un merge por evento alcanza: si queda remanente en el suelo, el propio
            // motor de Minecraft vuelve a disparar el evento en el siguiente acercamiento
            // (cálculo pasivo, sin necesidad de un loop propio).
            return;
        }
        // Sin slot con frescura distinta a fusionar: si existe un stack con frescura
        // IGUAL, isSimilar ya permite que vanilla lo apile solo, sin intervencion nuestra.
        // Igual etiquetamos el item del suelo si todavia no fue tocado (ej. drop de un
        // mob recien matado), para que se vea su frescura apenas entre al inventario.
        if (!foodManager.isTracked(ground)) {
            foodManager.calculateFreshness(ground, currentDay, FoodManager.AMBIENT_MULTIPLIER);
            event.getItem().setItemStack(ground);
        }
    }

    /**
     * Fusiona dos stacks de comida en uno solo, aplicando el promedio ponderado por
     * cantidad (seccion 3):
     * {@code Nueva_Frescura = ((Cant_A * Frescura_A) + (Cant_B * Frescura_B)) / (Cant_A + Cant_B)},
     * redondeado a la decena mas cercana. Antes de promediar, ambos stacks se llevan al
     * dia actual (catch-up) para no mezclar datos obsoletos.
     *
     * @return arreglo de 2 posiciones: [0] = stack resultante para el slot original
     *         (hasta el maximo apilable), [1] = remanente que no entro (o {@code null} si
     *         todo entro en el slot).
     */
    private ItemStack[] mergeStacks(ItemStack a, ItemStack b, long currentDay) {
        int freshnessA = foodManager.calculateFreshness(a, currentDay, FoodManager.AMBIENT_MULTIPLIER);
        int freshnessB = foodManager.calculateFreshness(b, currentDay, FoodManager.AMBIENT_MULTIPLIER);

        int amountA = a.getAmount();
        int amountB = b.getAmount();
        int totalAmount = amountA + amountB;

        double weighted = ((double) amountA * freshnessA + (double) amountB * freshnessB) / totalAmount;
        int newFreshness = foodManager.roundToNearestTen(weighted);
        // Valor crudo (antes de redondear) como entero de punto fijo x100, solo para el
        // Lore de debug de FoodManager — ej. 74.9275% se guarda como 7493 (redondeado al
        // centesimo), nunca como decimal real.
        long rawTimes100 = Math.round(weighted * 100);

        ItemStack merged = a.clone();
        int maxStack = merged.getMaxStackSize();
        int slotAmount = Math.min(totalAmount, maxStack);
        merged.setAmount(slotAmount);
        foodManager.applyFreshness(merged, newFreshness, currentDay, rawTimes100);
        if (foodManager.getTier(newFreshness) == FoodManager.SpoilageTier.PODRIDO) {
            // ItemStack#setType esta deprecado: transformToRotten devuelve una referencia
            // nueva en vez de mutar "merged" in-place, hay que recapturarla.
            merged = foodManager.transformToRotten(merged);
        }

        int leftoverAmount = totalAmount - slotAmount;
        ItemStack leftover = null;
        if (leftoverAmount > 0) {
            leftover = merged.clone();
            leftover.setAmount(leftoverAmount);
        }

        return new ItemStack[] { merged, leftover };
    }

    /**
     * Determina si dos ItemStacks de comida son "fusionables": mismo Material, ambos
     * gestionados por este sistema, y con datos (frescura/estado) distintos — si ya son
     * {@code isSimilar}, vanilla los apila solo y no hace falta intervenir.
     */
    private boolean isMergeableFood(ItemStack a, ItemStack b) {
        if (a == null || b == null || a.getType().isAir() || b.getType().isAir()) {
            return false;
        }
        if (a.getType() != b.getType()) {
            return false;
        }
        if (!foodManager.isTrackable(a) && !foodManager.isTracked(a)) {
            return false;
        }
        return !a.isSimilar(b);
    }
}
