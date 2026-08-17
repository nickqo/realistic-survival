package cl.nico.realisticsurvival;

import cl.nico.realisticsurvival.api.time.RSTimeHandler;
import cl.nico.realisticsurvival.api.time.TimeProvider;
import cl.nico.realisticsurvival.appliances.ApplianceGUI;
import cl.nico.realisticsurvival.appliances.ApplianceManager;
import cl.nico.realisticsurvival.appliances.CatchUpProcessor;
import cl.nico.realisticsurvival.commands.RSDebugCommand;
import cl.nico.realisticsurvival.commands.RSGiveCommand;
import cl.nico.realisticsurvival.farming.FarmingListener;
import cl.nico.realisticsurvival.farming.WateringManager;
import cl.nico.realisticsurvival.food.ConsumeListener;
import cl.nico.realisticsurvival.food.FoodManager;
import cl.nico.realisticsurvival.inventory.InventoryListener;
import cl.nico.realisticsurvival.recipes.RecipeManager;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Clase principal del plugin. Unica responsabilidad: bootstrap — construir el grafo de
 * dependencias (inyeccion manual por constructor) y registrar listeners/comandos. NO debe
 * contener logica de negocio: toda la matematica y las reacciones a eventos viven en los
 * paquetes {@code api.time}, {@code food}, {@code inventory}, {@code appliances} y
 * {@code farming} (ver ARCHITECTURE.md).
 * <p>
 * RealisticSeasons es una dependencia fuerte (hard dependency, ver plugin.yml -> depend):
 * el servidor no llega a cargar este plugin si RealisticSeasons no esta presente y
 * habilitado.
 */
public final class RealisticSurvival extends JavaPlugin {

    private TimeProvider timeProvider;
    private FoodManager foodManager;

    @Override
    public void onEnable() {
        if (!isRealisticSeasonsPresent()) {
            // No deberia ocurrir dado el hard dependency en plugin.yml, pero se valida
            // explicitamente para fallar de forma clara en vez de con NPEs en cascada.
            getLogger().severe("RealisticSeasons no esta presente/habilitado. Deshabilitando RealisticSurvival.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // --- api/time ---
        this.timeProvider = new RSTimeHandler();

        // --- food ---
        this.foodManager = new FoodManager(this);

        // --- appliances ---
        CatchUpProcessor catchUpProcessor = new CatchUpProcessor(foodManager);
        ApplianceGUI applianceGUI = new ApplianceGUI(this, catchUpProcessor, timeProvider);
        ApplianceManager applianceManager = new ApplianceManager(this, applianceGUI);

        // --- farming ---
        FarmingListener farmingListener = new FarmingListener(this, timeProvider);
        WateringManager wateringManager = new WateringManager(this, timeProvider);

        // --- inventory ---
        InventoryListener inventoryListener = new InventoryListener(foodManager, timeProvider);

        // --- listeners ---
        PluginManager pluginManager = getServer().getPluginManager();
        pluginManager.registerEvents(new ConsumeListener(foodManager, timeProvider), this);
        pluginManager.registerEvents(inventoryListener, this);
        pluginManager.registerEvents(applianceManager, this);
        pluginManager.registerEvents(applianceGUI, this);
        pluginManager.registerEvents(farmingListener, this);
        pluginManager.registerEvents(wateringManager, this);

        // --- comandos ---
        RSGiveCommand giveCommand = new RSGiveCommand(applianceManager, wateringManager);
        PluginCommand rsgive = getCommand("rsgive");
        if (rsgive != null) {
            rsgive.setExecutor(giveCommand);
            rsgive.setTabCompleter(giveCommand);
        } else {
            getLogger().warning("No se pudo registrar /rsgive: revisa plugin.yml.");
        }

        RSDebugCommand debugCommand = new RSDebugCommand(foodManager, timeProvider);
        PluginCommand rsdebug = getCommand("rsdebug");
        if (rsdebug != null) {
            rsdebug.setExecutor(debugCommand);
            rsdebug.setTabCompleter(debugCommand);
        } else {
            getLogger().warning("No se pudo registrar /rsdebug: revisa plugin.yml.");
        }

        // --- recetas ---
        RecipeManager recipeManager = new RecipeManager(this, applianceManager, wateringManager);
        recipeManager.registerAll();
        pluginManager.registerEvents(recipeManager, this);

        getLogger().info("RealisticSurvival habilitado.");
    }

    @Override
    public void onDisable() {
        // Cero ticking activo: no hay tareas/schedulers que detener. Cualquier estado
        // pendiente ya vive persistido en PDC (bloques/chunks) y DataComponentTypes (items).
        getLogger().info("RealisticSurvival deshabilitado.");
    }

    private boolean isRealisticSeasonsPresent() {
        return getServer().getPluginManager().getPlugin("RealisticSeasons") != null;
    }

    public TimeProvider getTimeProvider() {
        return timeProvider;
    }

    public FoodManager getFoodManager() {
        return foodManager;
    }
}
