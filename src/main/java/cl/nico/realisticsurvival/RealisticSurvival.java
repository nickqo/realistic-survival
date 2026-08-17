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
import cl.nico.realisticsurvival.food.CookingListener;
import cl.nico.realisticsurvival.food.FoodManager;
import cl.nico.realisticsurvival.inventory.InventoryListener;
import cl.nico.realisticsurvival.recipes.RecipeManager;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

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
 * <p>
 * <b>Excepcion unica al "cero ticking activo":</b> ver el Javadoc de
 * {@link InventoryListener#refreshPlayerInventory} para el porque. {@link #playerRefreshTask}
 * es la UNICA tarea periodica de todo el plugin — no calcula nada nuevo, solo re-dispara el
 * mismo catch-up de siempre para los inventarios de jugadores conectados, cada
 * {@link #PLAYER_REFRESH_PERIOD_TICKS} ticks.
 */
public final class RealisticSurvival extends JavaPlugin {

    /** ~5 segundos reales (20 ticks/seg). Frecuencia de la unica tarea periodica del plugin. */
    private static final long PLAYER_REFRESH_PERIOD_TICKS = 100L;

    private TimeProvider timeProvider;
    private FoodManager foodManager;
    private BukkitTask playerRefreshTask;

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
        InventoryListener inventoryListener = new InventoryListener(this, foodManager, timeProvider, applianceGUI);

        // --- listeners ---
        PluginManager pluginManager = getServer().getPluginManager();
        pluginManager.registerEvents(new ConsumeListener(foodManager, timeProvider), this);
        pluginManager.registerEvents(new CookingListener(foodManager, timeProvider), this);
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

        // --- unica tarea periodica del plugin (ver Javadoc de la clase) ---
        // Bukkit no dispara ningun evento cuando un jugador abre su propia pantalla de
        // inventario (tecla E), asi que sin esto la comida cargada se veria "congelada"
        // hasta la proxima interaccion real. No agrega calculo nuevo: solo vuelve a llamar
        // al mismo catch-up de InventoryListener para cada jugador conectado.
        this.playerRefreshTask = Bukkit.getScheduler().runTaskTimer(this, () -> {
            for (Player player : Bukkit.getOnlinePlayers()) {
                inventoryListener.refreshPlayerInventory(player);
            }
        }, PLAYER_REFRESH_PERIOD_TICKS, PLAYER_REFRESH_PERIOD_TICKS);

        getLogger().info("RealisticSurvival habilitado.");
    }

    @Override
    public void onDisable() {
        // Unica tarea a detener: el refresco periodico del inventario propio de jugadores
        // (ver onEnable). El resto del plugin sigue siendo 100% catch-up/event-driven; el
        // estado pendiente ya vive persistido en PDC (bloques/chunks) y DataComponentTypes
        // (items), no depende de que esta tarea haya corrido para quedar consistente.
        if (playerRefreshTask != null) {
            playerRefreshTask.cancel();
            playerRefreshTask = null;
        }
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
