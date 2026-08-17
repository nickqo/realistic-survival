package cl.nico.realisticsurvival.recipes;

import cl.nico.realisticsurvival.appliances.ApplianceManager;
import cl.nico.realisticsurvival.appliances.ApplianceManager.ApplianceType;
import cl.nico.realisticsurvival.farming.WateringManager;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;

/**
 * Registra las recetas de crafteo de los items custom del plugin (electrodomesticos y
 * herramientas de riego) y las "descubre" automaticamente para los jugadores al conectarse
 * (para que aparezcan en el libro de recetas). Responsabilidad unica: crafteo — no crea los
 * {@link ItemStack} resultado, eso vive en {@link ApplianceManager} / {@link WateringManager}
 * (SRP); este listener tampoco sabe nada de PDC ni CustomModelData.
 * <p>
 * Progresion de dificultad pensada para acompañar lo que ya exige el resto del plugin:
 * <ul>
 *     <li><b>Regadera Manual:</b> barata y temprana — sin ella, la agricultura bajo la
 *         nueva mecanica de riego activo (seccion 5) castiga demasiado desde el dia 1.</li>
 *     <li><b>Refrigerador:</b> tier "hierro + redstone basica": usa un Dropper vanilla
 *         (7 Piedra + 1 Redstone) como pieza central rodeado de Lingotes de Hierro.</li>
 *     <li><b>Aspersor:</b> mismo tier que el Refrigerador pero automatizado: usa un
 *         Dispensador (que ademas exige un Arco, un paso de progresion extra sobre el
 *         Dropper) en vez del Dropper.</li>
 *     <li><b>Congelador:</b> mas caro que el Refrigerador porque detiene la pudricion POR
 *         COMPLETO (el Refrigerador solo la retrasa x3, ver {@code FoodManager#FRIDGE_MULTIPLIER}).
 *         Usa Hielo Compacto, que obliga a conseguir un pico con Toque de Seda — un paso de
 *         progresion genuino, no trivial.</li>
 * </ul>
 */
public final class RecipeManager implements Listener {

    private final Plugin plugin;
    private final ApplianceManager applianceManager;
    private final WateringManager wateringManager;
    private final List<NamespacedKey> recipeKeys = new ArrayList<>();

    public RecipeManager(Plugin plugin, ApplianceManager applianceManager, WateringManager wateringManager) {
        this.plugin = plugin;
        this.applianceManager = applianceManager;
        this.wateringManager = wateringManager;
    }

    /** Registra todas las recetas en el servidor. Llamar una unica vez desde {@code onEnable}. */
    public void registerAll() {
        registerWateringCan();
        registerFridge();
        registerSprinkler();
        registerFreezer();
    }

    /**
     * Regadera Manual — 2 Lingote de Hierro + Botella de Vidrio + Palo.
     * <pre>
     * I I
     * G S
     * </pre>
     */
    private void registerWateringCan() {
        ShapedRecipe recipe = newRecipe("watering_can", wateringManager.createWateringCan());
        recipe.shape(
                "II",
                "GS"
        );
        recipe.setIngredient('I', Material.IRON_INGOT);
        recipe.setIngredient('G', Material.GLASS_BOTTLE);
        recipe.setIngredient('S', Material.STICK);
        register(recipe);
    }

    /**
     * Refrigerador — Dropper rodeado de 8 Lingotes de Hierro.
     * <pre>
     * I I I
     * I D I
     * I I I
     * </pre>
     */
    private void registerFridge() {
        ShapedRecipe recipe = newRecipe("fridge", applianceManager.createApplianceItem(ApplianceType.FRIDGE));
        recipe.shape(
                "III",
                "IDI",
                "III"
        );
        recipe.setIngredient('I', Material.IRON_INGOT);
        recipe.setIngredient('D', Material.DROPPER);
        register(recipe);
    }

    /**
     * Congelador — Dispensador rodeado de 8 Hielo Compacto (exige Toque de Seda).
     * <pre>
     * P P P
     * P D P
     * P P P
     * </pre>
     */
    private void registerFreezer() {
        ShapedRecipe recipe = newRecipe("freezer", applianceManager.createApplianceItem(ApplianceType.FREEZER));
        recipe.shape(
                "PPP",
                "PDP",
                "PPP"
        );
        recipe.setIngredient('P', Material.PACKED_ICE);
        recipe.setIngredient('D', Material.DISPENSER);
        register(recipe);
    }

    /**
     * Aspersor — Dispensador (redstone arriba, hierro a los costados, cubo de agua abajo).
     * El cubo de agua deja el Balde vacio al craftear (comportamiento vanilla estandar de
     * items-contenedor, automatico — no requiere codigo extra).
     * <pre>
     *   R
     * I D I
     *   W
     * </pre>
     */
    private void registerSprinkler() {
        ShapedRecipe recipe = newRecipe("sprinkler", wateringManager.createSprinklerItem());
        recipe.shape(
                " R ",
                "IDI",
                " W "
        );
        recipe.setIngredient('R', Material.REDSTONE);
        recipe.setIngredient('I', Material.IRON_INGOT);
        recipe.setIngredient('D', Material.DISPENSER);
        recipe.setIngredient('W', Material.WATER_BUCKET);
        register(recipe);
    }

    private ShapedRecipe newRecipe(String id, ItemStack result) {
        return new ShapedRecipe(new NamespacedKey(plugin, id), result);
    }

    private void register(ShapedRecipe recipe) {
        // removeRecipe primero evita el IllegalStateException de Bukkit si el plugin se
        // recarga (/reload) y la receta ya estaba registrada.
        Bukkit.removeRecipe(recipe.getKey());
        Bukkit.addRecipe(recipe);
        recipeKeys.add(recipe.getKey());
    }

    /** Muestra las recetas custom en el libro de recetas apenas el jugador se conecta. */
    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        event.getPlayer().discoverRecipes(recipeKeys);
    }
}
