package cl.nico.realisticsurvival.food;

import cl.nico.realisticsurvival.api.time.TimeProvider;
import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.FurnaceSmeltEvent;
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
 * <b>Fogata excluida a proposito:</b> {@code CampfireStartEvent} no tiene forma de
 * interceptar/reemplazar el resultado (no expone {@code getResult()}/{@code setResult()}
 * como {@link FurnaceSmeltEvent} — el item de salida lo resuelve la receta internamente), y
 * Bukkit no dispara ningun evento cuando la fogata termina de cocinar y expulsa el item.
 * Sin un punto de intercepcion real, no hay forma limpia de aplicar este bono ahi sin recurrir
 * a polling (ticking activo, prohibido por el diseño) — limitacion conocida de la API.
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
        applyCookingBonus(event.getSource(), event.getResult(), event::setResult, world);
    }

    /**
     * Calcula la frescura actual (con catch-up) de la fuente cruda y la traslada al
     * resultado cocinado con el bono de {@link FoodManager#COOKING_FRESHNESS_BONUS}. No hace
     * nada si el resultado no es un alimento rastreado por el plugin (ej. cocinar arena a
     * vidrio, o mineral a lingote) ni si la fuente no lo es.
     */
    private void applyCookingBonus(ItemStack source, ItemStack result, Consumer<ItemStack> applyResult, World world) {
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

        ItemStack cooked = result.clone();
        foodManager.applyFreshness(cooked, newFreshness, currentDay);
        applyResult.accept(cooked);
    }
}
