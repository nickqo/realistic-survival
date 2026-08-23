package cl.nico.realisticsurvival.inventory;

import cl.nico.realisticsurvival.api.time.TimeProvider;
import cl.nico.realisticsurvival.appliances.ApplianceGUI;
import cl.nico.realisticsurvival.food.FoodManager;
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
 * o al abrir CUALQUIER inventario ajeno al del jugador (un cofre, un barril, etc. — ver
 * {@link #onInventoryOpen}). Esto evita que un alimento quede "viejo" (sin recalcular)
 * durante mucho tiempo real: si eso pasara y despues se metiera a un electrodomestico, el
 * backlog acumulado se cobraria de golpe en el proximo catch-up (bug real, ver
 * {@code appliances.CatchUpProcessor}). Un item dado por comando ({@code /give}) y jamas
 * tocado (ni un click, ni un inventario abierto, ni recogido del suelo) puede seguir sin
 * etiquetar hasta la primera interaccion real — limitacion conocida del enfoque perezoso.
 * <p>
 * <b>Excepcion documentada al "cero ticking activo":</b> Bukkit NO dispara ningun evento
 * de servidor cuando un jugador abre su propia pantalla de inventario (tecla E) — a
 * diferencia de un cofre/barril, es 100% client-side. Sin algun disparador externo, la
 * comida que el jugador lleva encima se veria "congelada" en pantalla hasta la proxima
 * interaccion real (click, recoger del suelo, reconectarse), aunque el tiempo in-game siga
 * corriendo. Por eso {@code RealisticSurvival} programa una tarea periodica MUY liviana
 * (ver {@link #refreshPlayerInventory}) que solo llama a este mismo catch-up para los
 * inventarios de jugadores conectados — no agrega logica de decaimiento nueva, ni toca
 * contenedores/electrodomesticos (esos siguen 100% event-driven).
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
            // cualquiera de los dos items (etiquetarlo si es nuevo, ponerlo al dia si ya
            // estaba trackeado pero viejo, o transformarlo a Restos Podridos si llego a 0%).
            double currentDay = timeProvider.getCurrentDay(event.getWhoClicked().getWorld());
            ItemStack refreshedCurrent = refreshFreshness(current, currentDay);
            ItemStack refreshedCursor = refreshFreshness(cursor, currentDay);

            boolean currentChanged = refreshedCurrent != current;
            boolean cursorChanged = refreshedCursor != cursor;
            if (currentChanged || cursorChanged) {
                // Solo cancelamos si algo realmente cambio (ej. se pudrio del todo) — un
                // click normal sin transformaciones sigue su curso vanilla intacto.
                event.setCancelled(true);
                if (currentChanged) {
                    event.setCurrentItem(refreshedCurrent);
                }
                if (cursorChanged) {
                    // Ver Javadoc de la clase / comentario en el merge de abajo: setCursor
                    // dentro del handler (sincronico) es lo correcto, no diferirlo.
                    event.getView().setCursor(refreshedCursor);
                }
            }
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

        double currentDay = timeProvider.getCurrentDay(event.getWhoClicked().getWorld());
        ItemStack[] merged = foodManager.mergeStacks(current, cursor, currentDay);

        event.setCurrentItem(merged[0]);

        // IMPORTANTE: usar event.getView().setCursor(...) directamente (event.setCursor()
        // delega ahi mismo pero esta deprecado) — exactamente el mismo mecanismo que
        // setCurrentItem usa para el slot via InventoryView#setItem — NO
        // HumanEntity#setItemOnCursor().
        // Ese ultimo escribe el cursor del jugador por otra via (NMS directo) que NO
        // actualiza el estado que trackea el InventoryView; cuando Bukkit resincroniza el
        // cliente al terminar de procesar el click, usa el cursor trackeado por la View, asi
        // que setItemOnCursor() quedaba pisado (bug de duplicacion real: el slot fusionaba
        // bien pero el cursor volvia a mostrar una copia extra del item original).
        // Diferirlo un tick via scheduler (el intento anterior) tampoco alcanzaba: dos
        // clicks rapidos podian procesarse ambos ANTES de que la tarea diferida del primero
        // corriera, asi que el segundo click leia un cursor todavia "viejo" y fusionaba de
        // nuevo — la misma duplicacion, solo que por una carrera distinta. Al ser sincronico
        // y usar el mismo mecanismo que ya funciona para el slot, ninguno de los dos
        // problemas aplica.
        event.getView().setCursor(merged[1]);
    }

    /**
     * Al conectarse, pone al dia cualquier alimento del inventario (etiqueta el que nunca
     * fue tocado, recalcula el que ya estaba trackeado pero viejo).
     */
    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Inventory inventory = event.getPlayer().getInventory();
        double currentDay = timeProvider.getCurrentDay(event.getPlayer().getWorld());
        refreshInventory(inventory, currentDay);
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

        double currentDay = timeProvider.getCurrentDay(event.getPlayer().getWorld());
        refreshInventory(inventory, currentDay);
    }

    /**
     * Punto de entrada publico para la tarea periodica de {@code RealisticSurvival} que
     * pone al dia el inventario propio de cada jugador conectado (ver el Javadoc de la
     * clase, seccion "Excepcion documentada al cero ticking activo"). Reutiliza el mismo
     * {@link #refreshInventory} que ya usan {@link #onJoin}/{@link #onInventoryOpen} — no
     * hay formula ni logica nueva, solo un disparador adicional.
     */
    public void refreshPlayerInventory(Player player) {
        double currentDay = timeProvider.getCurrentDay(player.getWorld());
        refreshInventory(player.getInventory(), currentDay);
    }

    /**
     * Recorre un inventario completo poniendo al dia (o transformando a Restos Podridos)
     * cada slot que corresponda, escribiendo de vuelta solo los que cambiaron.
     */
    private void refreshInventory(Inventory inventory, double currentDay) {
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            ItemStack original = inventory.getItem(slot);
            ItemStack refreshed = refreshFreshness(original, currentDay);
            if (refreshed != original) {
                inventory.setItem(slot, refreshed);
            }
        }
    }

    /**
     * Pone al dia la frescura de un item: lo etiqueta (100% fresco) si el sistema nunca lo
     * habia tocado, o lo recalcula normalmente (catch-up) si ya estaba trackeado — asi un
     * alimento nunca queda "viejo" por mucho tiempo real sin recalcularse. Si el resultado
     * es 0% (Podrido), lo transforma a Restos Podridos.
     *
     * @return la referencia a usar de ahora en adelante — puede ser una instancia NUEVA
     *         si se transformo a Restos Podridos (ver {@link FoodManager#transformToRotten},
     *         que no muta in-place). El llamador es responsable de escribirla de vuelta en
     *         el slot/cursor/mano de donde vino {@code item} si esta referencia cambio. Si
     *         {@code item} no es un alimento gestionado por el plugin, se devuelve tal cual.
     */
    private ItemStack refreshFreshness(ItemStack item, double currentDay) {
        if (item == null || (!foodManager.isTrackable(item) && !foodManager.isTracked(item))) {
            return item;
        }
        int freshness = foodManager.calculateFreshness(item, currentDay, FoodManager.AMBIENT_MULTIPLIER);
        if (foodManager.getTier(freshness) == FoodManager.SpoilageTier.PODRIDO) {
            return foodManager.transformToRotten(item);
        }
        return item;
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
        double currentDay = timeProvider.getCurrentDay(player.getWorld());

        for (int slot = 0; slot < inventory.getSize(); slot++) {
            ItemStack existing = inventory.getItem(slot);
            if (existing == null || existing.getAmount() >= existing.getMaxStackSize()) {
                continue;
            }
            if (!isMergeableFood(existing, ground)) {
                continue;
            }

            ItemStack[] merged = foodManager.mergeStacks(existing, ground, currentDay);
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
        event.getItem().setItemStack(refreshFreshness(ground, currentDay));
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
