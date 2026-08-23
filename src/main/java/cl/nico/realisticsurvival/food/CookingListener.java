package cl.nico.realisticsurvival.food;

import cl.nico.realisticsurvival.api.time.TimeProvider;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Furnace;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.FurnaceSmeltEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.FurnaceInventory;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.function.Consumer;

/**
 * Responsabilidad unica: cuando un alimento crudo se cocina en Horno/Ahumador/Horno de Lava
 * ({@link FurnaceSmeltEvent}, el mismo evento para los tres), el resultado NO debe nacer
 * "fresco" al 100% — vanilla no sabe nada de nuestra frescura, asi que sin esta clase el
 * item cocinado quedaria sin trackear y se inicializaria perezosamente al 100% la primera
 * vez que algo lo tocara (ver {@link FoodManager#calculateFreshness}), lavando por completo
 * cualquier deterioro que tuviera crudo. En cambio, cocinar suma
 * {@link FoodManager#COOKING_FRESHNESS_BONUS} puntos sobre la frescura ACTUAL de la fuente
 * (sin pasar de 100%): una carne cruda casi podrida sigue casi podrida despues de cocinarse,
 * solo un poco mejor — cocinar no es una cura magica de frescura.
 * <p>
 * <b>Fogata excluida a proposito (y bloqueada, ver {@link #onCampfirePlace}):</b>
 * {@code CampfireStartEvent} no tiene forma de interceptar/reemplazar el resultado (no
 * expone {@code getResult()}/{@code setResult()} como {@link FurnaceSmeltEvent} — el item
 * de salida lo resuelve la receta internamente), y Bukkit no dispara ningun evento cuando
 * la fogata termina de cocinar y expulsa el item. Sin un punto de intercepcion real, no hay
 * forma limpia de aplicar este bono ahi sin recurrir a polling (ticking activo, prohibido
 * por el diseño) — limitacion conocida de la API. Dejarla cocinar sin control seria un
 * exploit real (lavar cualquier alimento casi podrido a 100% gratis), asi que mientras no
 * exista un hook confiable, directamente se bloquea colocar comida rastreada en la fogata.
 * <p>
 * <b>Shift-click junta todos los sub-stacks (ver {@link #onGatherIntoFurnace}):</b> distinta
 * frescura = distinto Lore/PDC = vanilla no considera dos stacks "similares", asi que el
 * shift-click normal solo mueve UN sub-stack por click, dejando el resto sin cocinar. Al
 * detectar un shift-click de un ingrediente valido hacia un Horno/Ahumador/Horno de Lava, se
 * cancela el movimiento vanilla y se juntan TODOS los sub-stacks de ese mismo alimento del
 * inventario de origen en uno solo (mismo promedio ponderado que cualquier otro merge, ver
 * {@link FoodManager#mergeStacks}) directo en el slot de ingrediente — un solo click cocina
 * todo lo que tengas de ese tipo, sin importar cuantas frescuras distintas haya.
 * <p>
 * <b>Resultados identicos para que vanilla los apile solo (ver {@link #applyCookingBonus}):</b>
 * ese mismo problema de "distinto PDC = no se apilan" existe tambien del lado de SALIDA, y
 * ahi no hay forma de intervenir con un click porque el propio Horno combina cada resultado
 * nuevo con lo que ya haya en su slot de salida de forma completamente interna (sin ningun
 * evento que avisarnos). Si dos resultados cocinados con la MISMA frescura redondeada
 * quedaran con {@code keyLastCalcDay} (el timestamp interno, escrito con el "ahora" de cada
 * ciclo de cocinado) ligeramente distinto entre si, vanilla los veria como items diferentes
 * y jamas lograria combinarlos — el horno quedaria trabado despues del primer item cocinado,
 * esperando un slot de salida "compatible" que nunca llega (bug real reportado: "el segundo
 * item no se cocina"). Por eso, si ya hay un resultado pendiente en el slot de salida con la
 * misma frescura redondeada que el que se esta por producir, se reusa su timestamp EXACTO en
 * vez del "ahora" en vivo — dejando el nuevo resultado PDC-identico al anterior, para que
 * vanilla los apile solo sin que nosotros tengamos que tocar el slot de salida.
 * <p>
 * No contiene logica de decaimiento propia: solo lee/escribe frescura via los metodos
 * publicos de {@link FoodManager} (SRP, mismo patron que {@code inventory.InventoryListener}
 * y {@link ConsumeListener}).
 */
public final class CookingListener implements Listener {

    private final FoodManager foodManager;
    private final TimeProvider timeProvider;

    public CookingListener(FoodManager foodManager, TimeProvider timeProvider) {
        this.foodManager = foodManager;
        this.timeProvider = timeProvider;
    }

    /** Horno, Ahumador y Horno de Lava disparan el mismo evento en Bukkit. */
    @EventHandler(ignoreCancelled = true)
    public void onFurnaceSmelt(FurnaceSmeltEvent event) {
        World world = event.getBlock().getWorld();
        ItemStack existingResult = (event.getBlock().getState() instanceof Furnace furnace)
                ? furnace.getInventory().getResult() : null;
        applyCookingBonus(event.getSource(), event.getResult(), event::setResult, world, existingResult);
    }

    /**
     * Bloquea colocar comida rastreada por el plugin sobre una Fogata/Fogata de Alma (ver
     * Javadoc de la clase, "Fogata excluida a proposito") — cancela la interaccion antes de
     * que el item llegue a colocarse, cerrando el exploit de "cocinar para lavar frescura"
     * mientras no exista forma de corregir el resultado ahi.
     */
    @EventHandler(ignoreCancelled = true)
    public void onCampfirePlace(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        Block block = event.getClickedBlock();
        if (block == null || (block.getType() != Material.CAMPFIRE && block.getType() != Material.SOUL_CAMPFIRE)) {
            return;
        }

        ItemStack item = event.getItem();
        if (item == null || (!foodManager.isTrackable(item) && !foodManager.isTracked(item))) {
            return;
        }

        event.setCancelled(true);
        event.getPlayer().sendActionBar(Component.text(
                "La Fogata no puede cocinar este alimento por ahora — usa un Horno/Ahumador/Horno de Lava.",
                NamedTextColor.RED));
    }

    /**
     * Ver Javadoc de la clase, "Shift-click junta todos los sub-stacks". Solo interviene si
     * el click es shift-click, el inventario superior es un Horno/Ahumador/Horno de Lava, el
     * click ocurrio en el inventario DE ORIGEN (no dentro del propio horno — sacar items no
     * nos interesa), y el item es un ingrediente valido segun la receta de ESE horno
     * ({@link FurnaceInventory#canSmelt}, evita interferir si el jugador shift-clickea algo
     * que no tiene receta, ej. Restos Podridos).
     */
    @EventHandler(ignoreCancelled = true)
    public void onGatherIntoFurnace(InventoryClickEvent event) {
        ClickType click = event.getClick();
        if (click != ClickType.SHIFT_LEFT && click != ClickType.SHIFT_RIGHT) {
            return;
        }
        if (!(event.getView().getTopInventory() instanceof FurnaceInventory furnace)) {
            return;
        }
        Inventory sourceInventory = event.getClickedInventory();
        if (sourceInventory == null || sourceInventory.equals(furnace)) {
            return;
        }

        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || (!foodManager.isTrackable(clicked) && !foodManager.isTracked(clicked))) {
            return;
        }
        if (!furnace.canSmelt(clicked)) {
            return;
        }

        event.setCancelled(true);

        double currentDay = timeProvider.getCurrentDay(event.getWhoClicked().getWorld());
        Material type = clicked.getType();
        ItemStack existingIngredient = furnace.getSmelting();
        ItemStack accumulated = (existingIngredient == null || existingIngredient.getType().isAir())
                ? null : existingIngredient;

        for (int slot = 0; slot < sourceInventory.getSize(); slot++) {
            ItemStack stack = sourceInventory.getItem(slot);
            if (stack == null || stack.getType() != type) {
                continue;
            }
            if (!foodManager.isTrackable(stack) && !foodManager.isTracked(stack)) {
                continue;
            }

            if (accumulated == null) {
                accumulated = stack.clone();
                sourceInventory.setItem(slot, null);
                continue;
            }
            if (accumulated.getAmount() >= accumulated.getMaxStackSize()) {
                // El slot de ingrediente ya llego al maximo apilable: el resto se queda en
                // el inventario, igual que un shift-click normal que no alcanza a mover todo.
                break;
            }

            ItemStack[] merged = foodManager.mergeStacks(accumulated, stack, currentDay);
            accumulated = merged[0];
            sourceInventory.setItem(slot, merged[1]);
        }

        furnace.setSmelting(accumulated);
    }

    /**
     * Calcula la frescura actual (con catch-up) de la fuente cruda y la traslada al
     * resultado cocinado con el bono de {@link FoodManager#COOKING_FRESHNESS_BONUS}. No hace
     * nada si el resultado no es un alimento rastreado por el plugin (ej. cocinar arena a
     * vidrio, o mineral a lingote) ni si la fuente no lo es.
     *
     * @param existingResult lo que ya haya en el slot de salida del horno ANTES de este
     *                       ciclo (o {@code null} si esta vacio) — ver Javadoc de la clase,
     *                       "Resultados identicos para que vanilla los apile solo".
     */
    private void applyCookingBonus(ItemStack source, ItemStack result, Consumer<ItemStack> applyResult,
                                    World world, ItemStack existingResult) {
        if (result == null || !foodManager.isTrackable(result)) {
            return;
        }
        if (source == null || (!foodManager.isTrackable(source) && !foodManager.isTracked(source))) {
            return;
        }

        double currentDay = timeProvider.getCurrentDay(world);
        int sourceFreshness = foodManager.calculateFreshness(source, currentDay, FoodManager.AMBIENT_MULTIPLIER);
        if (foodManager.getTier(sourceFreshness) == FoodManager.SpoilageTier.PODRIDO) {
            // Defensivo: no deberia poder llegar un ingrediente ya Podrido a un horno (se
            // transforma a Restos Podridos, que no tiene receta de cocinado vanilla), pero
            // si pasara, no le regalamos el bono.
            return;
        }

        int newFreshness = Math.min(100, sourceFreshness + FoodManager.COOKING_FRESHNESS_BONUS);

        // Si el slot de salida ya tiene un resultado del mismo tipo Y la misma frescura
        // redondeada, reusamos su timestamp interno EXACTO en vez del "ahora" en vivo, para
        // que el nuevo resultado quede PDC-identico y vanilla los apile solo (ver Javadoc).
        double watermark = currentDay;
        if (existingResult != null && !existingResult.getType().isAir()
                && existingResult.getType() == result.getType()
                && foodManager.getStoredFreshness(existingResult) == newFreshness) {
            watermark = foodManager.getLastCalcDay(existingResult, currentDay);
        }

        ItemStack cooked = result.clone();
        foodManager.applyFreshness(cooked, newFreshness, watermark);
        applyResult.accept(cooked);
    }
}
