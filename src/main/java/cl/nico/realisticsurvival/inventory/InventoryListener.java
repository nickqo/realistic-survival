package cl.nico.realisticsurvival.inventory;

import cl.nico.realisticsurvival.api.time.TimeProvider;
import cl.nico.realisticsurvival.appliances.ApplianceGUI;
import cl.nico.realisticsurvival.food.FoodManager;
import org.bukkit.Bukkit;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

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
 * <b>Catch-up fuera de merges:</b> ademas de los merges, esta clase recalcula la frescura
 * de cualquier alimento tocado por un click, recogido del suelo, o presente al conectarse
 * o al abrir CUALQUIER inventario (el propio, un cofre, un barril, etc. — ver
 * {@link #onInventoryOpen}). Esto evita que un alimento quede "viejo" (sin recalcular)
 * durante mucho tiempo real: si eso pasara y despues se metiera a un electrodomestico, el
 * backlog acumulado se cobraria de golpe en el proximo catch-up (bug real, ver
 * {@code appliances.CatchUpProcessor}). Un item dado por comando ({@code /give}) y jamas
 * tocado (ni un click, ni un inventario abierto, ni recogido del suelo) puede seguir sin
 * etiquetar hasta la primera interaccion real — limitacion conocida del enfoque perezoso.
 */
public final class InventoryListener implements Listener {

    private final Plugin plugin;
    private final FoodManager foodManager;
    private final TimeProvider timeProvider;
    private final ApplianceGUI applianceGUI;

    public InventoryListener(Plugin plugin, FoodManager foodManager, TimeProvider timeProvider, ApplianceGUI applianceGUI) {
        this.plugin = plugin;
        this.foodManager = foodManager;
        this.timeProvider = timeProvider;
        this.applianceGUI = applianceGUI;
    }

    @EventHandler(ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        ItemStack cursor = event.getCursor();
        ItemStack current = event.getCurrentItem();

        if (!isMergeableFood(current, cursor)) {
            // No hay fusion que hacer, pero igual aprovechamos el click para recalcular
            // cualquiera de los dos items (etiquetarlo si es nuevo, o ponerlo al dia si
            // ya estaba trackeado pero viejo).
            long currentDay = timeProvider.getCurrentDay(event.getWhoClicked().getWorld());
            refreshFreshness(current, currentDay);
            refreshFreshness(cursor, currentDay);
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

        // IMPORTANTE: ni event.setCursor() (deprecado desde Bukkit 1.5.2, documentado como
        // causante de estas mismas inconsistencias) ni getWhoClicked().setItemOnCursor()
        // llamados DENTRO del handler son confiables aca: con el evento cancelado, Bukkit
        // resincroniza el cursor del cliente usando el valor ORIGINAL trackeado por el
        // propio evento despues de que este handler termina, pisando cualquier cambio de
        // cursor hecho en el mismo tick — devolviendo el item original del slot al cursor
        // (bug de duplicacion real: terminabas con el slot fusionado Y el cursor con una
        // copia extra del original). La forma correcta, documentada por Paper, es cancelar
        // el evento y aplicar el cambio de cursor en el tick siguiente via el scheduler.
        HumanEntity clicker = event.getWhoClicked();
        ItemStack newCursor = merged[1];
        Bukkit.getScheduler().runTask(plugin, () -> clicker.setItemOnCursor(newCursor));
    }

    /**
     * Al conectarse, pone al dia cualquier alimento del inventario (etiqueta el que nunca
     * fue tocado, recalcula el que ya estaba trackeado pero viejo).
     */
    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Inventory inventory = event.getPlayer().getInventory();
        long currentDay = timeProvider.getCurrentDay(event.getPlayer().getWorld());
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            refreshFreshness(inventory.getItem(slot), currentDay);
        }
    }

    /**
     * Catch-up al abrir CUALQUIER inventario (el propio del jugador, un cofre, un barril,
     * etc., a temperatura ambiente): pone al dia toda la comida contenida, para que
     * "vaya cambiando" visiblemente al revisar el inventario en vez de quedar congelada
     * hasta la proxima fusion o consumo. Se excluyen los inventarios de electrodomesticos
     * ({@link ApplianceGUI#isManagedByAppliance}): esos ya corrieron su propio catch-up
     * con el multiplicador frio correspondiente en {@code ApplianceGUI#open}, antes de
     * llegar a abrirse — aplicar ademas un catch-up ambiente generico aca los pisaria.
     */
    @EventHandler(ignoreCancelled = true)
    public void onInventoryOpen(InventoryOpenEvent event) {
        Inventory inventory = event.getInventory();
        if (applianceGUI.isManagedByAppliance(inventory)) {
            return;
        }

        long currentDay = timeProvider.getCurrentDay(event.getPlayer().getWorld());
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            refreshFreshness(inventory.getItem(slot), currentDay);
        }
    }

    /**
     * Pone al dia la frescura de un item: lo etiqueta (100% fresco) si el sistema nunca lo
     * habia tocado, o lo recalcula normalmente (catch-up) si ya estaba trackeado — asi un
     * alimento nunca queda "viejo" por mucho tiempo real sin recalcularse. Muta
     * {@code item} in-place; no hace nada si no es un alimento gestionado por el plugin.
     */
    private void refreshFreshness(ItemStack item, long currentDay) {
        if (item != null && (foodManager.isTrackable(item) || foodManager.isTracked(item))) {
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
        // Igual ponemos al dia el item del suelo (ej. drop de un mob recien matado, o uno
        // que llevaba tiempo tirado), para que se vea su frescura apenas entre al inventario.
        refreshFreshness(ground, currentDay);
        event.getItem().setItemStack(ground);
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
