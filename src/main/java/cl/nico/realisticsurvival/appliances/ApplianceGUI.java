package cl.nico.realisticsurvival.appliances;

import cl.nico.realisticsurvival.api.time.TimeProvider;
import cl.nico.realisticsurvival.appliances.ApplianceManager.ApplianceType;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

/**
 * Interfaz virtual (GUI) de 9 slots para los electrodomesticos, con la misma grilla 3x3
 * que un Dropper/Dispensador vanilla ({@link InventoryType#DISPENSER}) — Slot 8 (esquina
 * inferior derecha) reservado para Hielo / Hielo Azul, ver {@link #FUEL_SLOT}.
 * Responsabilidad unica: abrir/cerrar/serializar el inventario virtual asociado a una
 * ubicacion, persistiendolo en el PDC del {@link org.bukkit.Chunk} (asociado a las
 * coordenadas del bloque) — NO calcula pudricion ni consumo de hielo (ver
 * {@link CatchUpProcessor}), NO maneja el bloque/entidad fisica (ver {@link ApplianceManager}).
 */
public final class ApplianceGUI implements Listener {

    public static final int SIZE = 9;
    public static final int FUEL_SLOT = 8;

    private final Plugin plugin;
    private final CatchUpProcessor catchUpProcessor;
    private final TimeProvider timeProvider;

    public ApplianceGUI(Plugin plugin, CatchUpProcessor catchUpProcessor, TimeProvider timeProvider) {
        this.plugin = plugin;
        this.catchUpProcessor = catchUpProcessor;
        this.timeProvider = timeProvider;
    }

    /**
     * Abre el inventario virtual de un electrodomestico para un jugador. Antes de mostrarlo,
     * dispara el calculo pasivo (catch-up) de hielo/pudricion correspondiente al tiempo
     * transcurrido desde el ultimo calculo, y persiste el resultado de inmediato (asi el
     * estado queda consistente aunque el jugador se desconecte sin cerrar la GUI).
     */
    public void open(Player player, Location applianceLocation) {
        ApplianceType type = readType(applianceLocation);
        if (type == null) {
            return;
        }

        double currentDay = timeProvider.getCurrentDay(applianceLocation.getWorld());
        double lastCalcDay = readLastCalcDay(applianceLocation, currentDay);
        double iceFractionProgress = readIceFractionProgress(applianceLocation);
        ItemStack[] contents = deserializeContents(applianceLocation);

        double remainingIceFraction = catchUpProcessor.process(
                contents, lastCalcDay, currentDay, iceFractionProgress, type == ApplianceType.FREEZER);
        serializeContents(applianceLocation, contents, currentDay);
        writeIceFractionProgress(applianceLocation, remainingIceFraction);

        ApplianceHolder holder = new ApplianceHolder(applianceLocation);
        // InventoryType.DISPENSER da la misma grilla 3x3 (9 slots) que un Dropper/
        // Dispensador vanilla, en vez de la fila de 9 generica.
        Inventory inventory = Bukkit.createInventory(holder, InventoryType.DISPENSER, Component.text(type.getDisplayName()));
        inventory.setContents(contents);
        holder.inventory = inventory;

        player.openInventory(inventory);
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getInventory().getHolder() instanceof ApplianceHolder holder)) {
            return;
        }

        // Sin ticking: al cerrar se guarda el estado y no vuelve a correr nada hasta la
        // proxima apertura (que disparara un nuevo catch-up).
        double currentDay = timeProvider.getCurrentDay(holder.location.getWorld());
        serializeContents(holder.location, event.getInventory().getContents(), currentDay);
    }

    /**
     * Indica si un {@link Inventory} es el de un electrodomestico gestionado por esta
     * clase. Usado por {@code inventory.InventoryListener} para NO aplicar su catch-up
     * ambiente generico (el electrodomestico ya corrio su propio catch-up con el
     * multiplicador frio correspondiente en {@link #open}) sobre este inventario en
     * particular al abrirse.
     */
    public boolean isManagedByAppliance(Inventory inventory) {
        return inventory.getHolder() instanceof ApplianceHolder;
    }

    /**
     * Vuelca el inventario de un electrodomestico como drops en el mundo y limpia su
     * estado del PDC del chunk. Usado por {@link ApplianceManager} al romper el bloque.
     */
    public void dropContentsAndClear(Location applianceLocation, World world) {
        ItemStack[] contents = deserializeContents(applianceLocation);
        for (ItemStack stack : contents) {
            if (stack != null && !stack.getType().isAir()) {
                world.dropItemNaturally(applianceLocation.clone().add(0.5, 0.5, 0.5), stack);
            }
        }

        PersistentDataContainer chunkPdc = applianceLocation.getChunk().getPersistentDataContainer();
        String prefix = ApplianceManager.applianceKeyPrefix(applianceLocation);
        chunkPdc.remove(new NamespacedKey(plugin, prefix + "_contents"));
        chunkPdc.remove(new NamespacedKey(plugin, prefix + "_last_day"));
        chunkPdc.remove(new NamespacedKey(plugin, prefix + "_ice_fraction"));
    }

    private ApplianceType readType(Location applianceLocation) {
        PersistentDataContainer chunkPdc = applianceLocation.getChunk().getPersistentDataContainer();
        String prefix = ApplianceManager.applianceKeyPrefix(applianceLocation);
        String raw = chunkPdc.get(new NamespacedKey(plugin, prefix + "_type"), PersistentDataType.STRING);
        if (raw == null) {
            return null;
        }
        try {
            return ApplianceType.valueOf(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private double readLastCalcDay(Location applianceLocation, double fallback) {
        PersistentDataContainer chunkPdc = applianceLocation.getChunk().getPersistentDataContainer();
        String prefix = ApplianceManager.applianceKeyPrefix(applianceLocation);
        return chunkPdc.getOrDefault(new NamespacedKey(plugin, prefix + "_last_day"), PersistentDataType.DOUBLE, fallback);
    }

    /**
     * Dias frios acumulados que todavia no alcanzaron a consumir un hielo entero (ver
     * {@link CatchUpProcessor#process} para el porque de este acumulador).
     */
    private double readIceFractionProgress(Location applianceLocation) {
        PersistentDataContainer chunkPdc = applianceLocation.getChunk().getPersistentDataContainer();
        String prefix = ApplianceManager.applianceKeyPrefix(applianceLocation);
        return chunkPdc.getOrDefault(new NamespacedKey(plugin, prefix + "_ice_fraction"), PersistentDataType.DOUBLE, 0.0);
    }

    private void writeIceFractionProgress(Location applianceLocation, double fraction) {
        PersistentDataContainer chunkPdc = applianceLocation.getChunk().getPersistentDataContainer();
        String prefix = ApplianceManager.applianceKeyPrefix(applianceLocation);
        chunkPdc.set(new NamespacedKey(plugin, prefix + "_ice_fraction"), PersistentDataType.DOUBLE, fraction);
    }

    /**
     * Reconstruye el contenido (9 slots) del inventario virtual a partir de lo guardado
     * en el PDC del chunk para la ubicacion dada. La serializacion usa
     * {@link ItemStack#serializeItemsAsBytes} / {@link ItemStack#deserializeItemsFromBytes}
     * (API nativa de Bukkit, no NBT crudo ni librerias de terceros).
     */
    private ItemStack[] deserializeContents(Location applianceLocation) {
        PersistentDataContainer chunkPdc = applianceLocation.getChunk().getPersistentDataContainer();
        String prefix = ApplianceManager.applianceKeyPrefix(applianceLocation);
        byte[] raw = chunkPdc.get(new NamespacedKey(plugin, prefix + "_contents"), PersistentDataType.BYTE_ARRAY);
        if (raw == null) {
            return new ItemStack[SIZE];
        }

        ItemStack[] stored = ItemStack.deserializeItemsFromBytes(raw);
        ItemStack[] contents = new ItemStack[SIZE];
        System.arraycopy(stored, 0, contents, 0, Math.min(SIZE, stored.length));
        return contents;
    }

    private void serializeContents(Location applianceLocation, ItemStack[] contents, double currentDay) {
        PersistentDataContainer chunkPdc = applianceLocation.getChunk().getPersistentDataContainer();
        String prefix = ApplianceManager.applianceKeyPrefix(applianceLocation);
        chunkPdc.set(new NamespacedKey(plugin, prefix + "_contents"), PersistentDataType.BYTE_ARRAY,
                ItemStack.serializeItemsAsBytes(contents));
        chunkPdc.set(new NamespacedKey(plugin, prefix + "_last_day"), PersistentDataType.DOUBLE, currentDay);
    }

    /** Marca liviana que ata un inventario virtual abierto a su ubicacion fisica. */
    private static final class ApplianceHolder implements InventoryHolder {
        private final Location location;
        private Inventory inventory;

        private ApplianceHolder(Location location) {
            this.location = location;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
