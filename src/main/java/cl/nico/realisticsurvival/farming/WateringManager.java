package cl.nico.realisticsurvival.farming;

import cl.nico.realisticsurvival.api.time.TimeProvider;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.CustomModelData;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.type.Farmland;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.UUID;

/**
 * Logica de las herramientas de riego activo: la Regadera Manual (se recarga en una
 * fuente de agua, hidrata un radio 3x3 al usarse) y el Aspersor (bloque custom
 * Barrier + ItemDisplay que hidrata 5x5 de forma pasiva via catch-up, siguiendo la misma
 * filosofia que los electrodomesticos de {@code appliances}). Responsabilidad unica:
 * riego activo. No anula eventos vanilla de Farmland (eso es de {@link FarmingListener}).
 * <p>
 * Nota de alcance: a diferencia del Refrigerador/Congelador, el Aspersor no expone una GUI
 * de 9 slots — su "carga" es un contador simple de dias de agua (cada Cubo de Agua carga
 * {@link #CHARGES_PER_BUCKET} dias), suficiente para la mecanica pasiva de la seccion 5.2
 * sin duplicar toda la maquinaria de {@code appliances.ApplianceGUI}.
 */
public final class WateringManager implements Listener {

    /** Radio de hidratacion de la Regadera Manual (3x3 => radio 1). */
    public static final int WATERING_CAN_RADIUS = 1;

    /** Radio de hidratacion pasiva del Aspersor (5x5 => radio 2). */
    public static final int SPRINKLER_RADIUS = 2;

    /**
     * Inclina el modelo del Aspersor -90° en el eje X para que su "frente" (que por
     * defecto mira hacia un costado, igual que el Dispensador del Congelador) quede
     * apuntando hacia arriba. Al igual que {@code ApplianceManager#CARDINAL_Y_DEGREES}, es
     * un valor best-effort: si en el juego no apunta hacia arriba, ajustar el signo del
     * angulo (+90 en vez de -90).
     */
    private static final Transformation FACING_UP_TRANSFORMATION = new Transformation(
            new Vector3f(0f, 0f, 0f),
            new AxisAngle4f((float) Math.toRadians(-90), 1f, 0f, 0f),
            new Vector3f(1f, 1f, 1f),
            new AxisAngle4f(0f, 0f, 1f, 0f));

    private static final int WATERING_CAN_MAX_CHARGES = 3;
    private static final int CHARGES_PER_BUCKET = 8;

    private final Plugin plugin;
    private final TimeProvider timeProvider;
    private final NamespacedKey keyCanMarker;
    private final NamespacedKey keyCanCharges;
    private final NamespacedKey keySprinklerMarker;

    public WateringManager(Plugin plugin, TimeProvider timeProvider) {
        this.plugin = plugin;
        this.timeProvider = timeProvider;
        this.keyCanMarker = new NamespacedKey(plugin, "watering_can");
        this.keyCanCharges = new NamespacedKey(plugin, "watering_can_charges");
        this.keySprinklerMarker = new NamespacedKey(plugin, "sprinkler_item");
    }

    /**
     * Material base de la Regadera Manual sin Resource Pack: una botella de vidrio se
     * reconoce como "recipiente pequeño" en vez de un simple palo.
     */
    private static final Material WATERING_CAN_FALLBACK = Material.GLASS_BOTTLE;

    /**
     * Material base del Aspersor sin Resource Pack: un dispensador se reconoce como
     * "dispositivo mecanico" en vez de un lienzo neutro tipo {@code PAPER}.
     */
    private static final Material SPRINKLER_FALLBACK = Material.DISPENSER;

    private static Component itemName(String name) {
        return Component.text(name, NamedTextColor.WHITE).decoration(TextDecoration.ITALIC, false);
    }

    /** Crea el ItemStack de la Regadera Manual. CustomModelData es placeholder. */
    public ItemStack createWateringCan() {
        ItemStack item = new ItemStack(WATERING_CAN_FALLBACK);
        item.setData(DataComponentTypes.CUSTOM_MODEL_DATA, CustomModelData.customModelData().addFloat(1_200_001).build());
        item.editMeta(meta -> {
            meta.displayName(itemName("Regadera Manual"));
            PersistentDataContainer pdc = meta.getPersistentDataContainer();
            pdc.set(keyCanMarker, PersistentDataType.BOOLEAN, true);
            pdc.set(keyCanCharges, PersistentDataType.INTEGER, WATERING_CAN_MAX_CHARGES);
        });
        return item;
    }

    /** Crea el ItemStack "fisico" del Aspersor. CustomModelData es placeholder. */
    public ItemStack createSprinklerItem() {
        ItemStack item = new ItemStack(SPRINKLER_FALLBACK);
        item.setData(DataComponentTypes.CUSTOM_MODEL_DATA, CustomModelData.customModelData().addFloat(1_200_002).build());
        item.editMeta(meta -> {
            meta.displayName(itemName("Aspersor"));
            meta.getPersistentDataContainer().set(keySprinklerMarker, PersistentDataType.BOOLEAN, true);
        });
        return item;
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }

        ItemStack item = event.getItem();
        if (isWateringCan(item)) {
            handleWateringCanUse(event, item);
            return;
        }

        Block clicked = event.getClickedBlock();
        if (clicked == null || event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }

        if (clicked.getType() == Material.BARRIER && isTrackedSprinkler(clicked.getLocation())) {
            event.setCancelled(true);
            handleSprinklerInteract(event.getPlayer(), item, clicked.getLocation());
            return;
        }

        if (isSprinklerItem(item)) {
            event.setCancelled(true);
            placeSprinkler(event.getPlayer(), item, clicked, event.getBlockFace());
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreakSprinkler(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || event.getAction() != Action.LEFT_CLICK_BLOCK) {
            return;
        }
        Block clicked = event.getClickedBlock();
        if (clicked == null || clicked.getType() != Material.BARRIER || !isTrackedSprinkler(clicked.getLocation())) {
            return;
        }
        if (!isHoldingPickaxe(event.getItem())) {
            // Igual que un Dispensador real: hace falta una picota. Sin ticking activo no
            // se simula el tiempo de picado: o se rompe con picota en un click, o nada.
            return;
        }
        event.setCancelled(true);
        breakSprinkler(event.getPlayer(), clicked);
    }

    private boolean isHoldingPickaxe(ItemStack item) {
        return item != null && Tag.ITEMS_PICKAXES.isTagged(item.getType());
    }

    private void handleWateringCanUse(PlayerInteractEvent event, ItemStack can) {
        Block clicked = event.getClickedBlock();
        if (clicked == null || event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }

        if (clicked.getType() == Material.WATER) {
            event.setCancelled(true);
            can.editMeta(meta -> meta.getPersistentDataContainer()
                    .set(keyCanCharges, PersistentDataType.INTEGER, WATERING_CAN_MAX_CHARGES));
            return;
        }

        Block target = clicked.getBlockData() instanceof Farmland ? clicked : clicked.getRelative(BlockFace.DOWN);
        if (!(target.getBlockData() instanceof Farmland)) {
            return;
        }

        int charges = getCanCharges(can);
        if (charges <= 0) {
            return;
        }

        event.setCancelled(true);
        hydrateArea(target.getLocation(), WATERING_CAN_RADIUS);
        can.editMeta(meta -> meta.getPersistentDataContainer().set(keyCanCharges, PersistentDataType.INTEGER, charges - 1));
    }

    /**
     * Hidrata todos los bloques de Farmland en un radio cuadrado alrededor del centro,
     * al nivel de humedad maximo (7).
     */
    private void hydrateArea(Location center, int radius) {
        Block centerBlock = center.getBlock();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                Block block = centerBlock.getRelative(dx, 0, dz);
                if (block.getBlockData() instanceof Farmland farmland) {
                    farmland.setMoisture(farmland.getMaximumMoisture());
                    block.setBlockData(farmland);
                }
            }
        }
    }

    private void placeSprinkler(Player player, ItemStack handItem, Block clickedBlock, BlockFace face) {
        // Si el bloque clickeado es "reemplazable" (nieve en capas, pasto alto, agua,
        // etc.) se coloca ENCIMA de su propia posicion, igual que vanilla — si no,
        // se coloca adyacente segun la cara clickeada.
        Block target = clickedBlock.isReplaceable() ? clickedBlock : clickedBlock.getRelative(face);
        if (!target.isEmpty() && !target.isReplaceable()) {
            return;
        }

        target.setType(Material.BARRIER);
        Location center = target.getLocation().add(0.5, 0.5, 0.5);
        ItemDisplay display = target.getWorld().spawn(center, ItemDisplay.class, entity -> {
            entity.setItemStack(createSprinklerItem());
            entity.setBillboard(Display.Billboard.FIXED);
            entity.setPersistent(true);
            // El Aspersor siempre mira hacia arriba, sin importar hacia donde miraba el
            // jugador al colocarlo (a diferencia del Refrigerador/Congelador).
            entity.setTransformation(FACING_UP_TRANSFORMATION);
        });

        PersistentDataContainer chunkPdc = target.getChunk().getPersistentDataContainer();
        String prefix = sprinklerKeyPrefix(target.getLocation());
        double currentDay = timeProvider.getCurrentDay(target.getWorld());
        chunkPdc.set(new NamespacedKey(plugin, prefix + "_charges"), PersistentDataType.INTEGER, 0);
        chunkPdc.set(new NamespacedKey(plugin, prefix + "_last_day"), PersistentDataType.DOUBLE, currentDay);
        chunkPdc.set(new NamespacedKey(plugin, prefix + "_display_uuid"), PersistentDataType.STRING,
                display.getUniqueId().toString());

        if (player.getGameMode() != GameMode.CREATIVE) {
            handItem.setAmount(handItem.getAmount() - 1);
        }
    }

    /**
     * Rompe un Aspersor colocado: remueve la Barrera, la entidad {@link ItemDisplay} y el
     * estado guardado en el PDC del chunk, y devuelve el item (con la carga de agua
     * pendiente perdida — simplificacion deliberada, no se convierte de vuelta a Cubos de
     * Agua).
     */
    private void breakSprinkler(Player player, Block barrierBlock) {
        Location location = barrierBlock.getLocation();
        despawnSprinklerDisplay(location);

        PersistentDataContainer chunkPdc = barrierBlock.getChunk().getPersistentDataContainer();
        String prefix = sprinklerKeyPrefix(location);
        chunkPdc.remove(new NamespacedKey(plugin, prefix + "_charges"));
        chunkPdc.remove(new NamespacedKey(plugin, prefix + "_last_day"));
        chunkPdc.remove(new NamespacedKey(plugin, prefix + "_charge_fraction"));
        chunkPdc.remove(new NamespacedKey(plugin, prefix + "_display_uuid"));

        barrierBlock.setType(Material.AIR);

        if (player.getGameMode() != GameMode.CREATIVE) {
            barrierBlock.getWorld().dropItemNaturally(location.clone().add(0.5, 0.5, 0.5), createSprinklerItem());
        }
    }

    private void despawnSprinklerDisplay(Location barrierLocation) {
        PersistentDataContainer chunkPdc = barrierLocation.getChunk().getPersistentDataContainer();
        NamespacedKey key = new NamespacedKey(plugin, sprinklerKeyPrefix(barrierLocation) + "_display_uuid");
        String raw = chunkPdc.get(key, PersistentDataType.STRING);
        if (raw == null) {
            return;
        }
        Entity entity = barrierLocation.getWorld().getEntity(UUID.fromString(raw));
        if (entity != null) {
            entity.remove();
        }
    }

    private void handleSprinklerInteract(Player player, ItemStack handItem, Location sprinklerLocation) {
        if (handItem != null && handItem.getType() == Material.WATER_BUCKET) {
            refillSprinkler(sprinklerLocation);
            if (player.getGameMode() != GameMode.CREATIVE) {
                handItem.setAmount(handItem.getAmount() - 1);
                player.getInventory().addItem(new ItemStack(Material.BUCKET));
            }
        }
        // Catch-up siempre, con o sin recarga: refleja el riego pendiente hasta "ahora".
        catchUpSprinkler(sprinklerLocation, true);
    }

    public boolean isTrackedSprinkler(Location barrierLocation) {
        PersistentDataContainer chunkPdc = barrierLocation.getChunk().getPersistentDataContainer();
        NamespacedKey key = new NamespacedKey(plugin, sprinklerKeyPrefix(barrierLocation) + "_charges");
        return chunkPdc.has(key, PersistentDataType.INTEGER);
    }

    /** Añade {@link #CHARGES_PER_BUCKET} dias de carga al Aspersor (1 Cubo de Agua). */
    public void refillSprinkler(Location sprinklerLocation) {
        PersistentDataContainer chunkPdc = sprinklerLocation.getChunk().getPersistentDataContainer();
        NamespacedKey chargesKey = new NamespacedKey(plugin, sprinklerKeyPrefix(sprinklerLocation) + "_charges");
        int current = chunkPdc.getOrDefault(chargesKey, PersistentDataType.INTEGER, 0);
        chunkPdc.set(chargesKey, PersistentDataType.INTEGER, current + CHARGES_PER_BUCKET);
    }

    /**
     * Calculo pasivo (catch-up) del Aspersor: se invoca al interactuar con el, nunca en
     * background. Por cada dia in-game transcurrido con carga disponible, hidrata el area
     * de 5x5 ({@link #SPRINKLER_RADIUS}) y descuenta la carga correspondiente.
     * <p>
     * La carga es un recurso discreto (dias enteros), pero el tiempo se mide con precision
     * de hora — asi que, igual que el hielo de los electrodomesticos
     * ({@code CatchUpProcessor}), el progreso fraccionario se ACUMULA entre aperturas
     * ({@code _charge_fraction}) y solo se consume una carga entera cuando ese acumulado
     * cruza un dia completo. Redondear hacia arriba en cada apertura individual gastaria
     * una carga entera cada vez que se reabre el Aspersor, aunque sea a los pocos segundos.
     */
    public void catchUpSprinkler(Location sprinklerLocation, boolean applyHydration) {
        PersistentDataContainer chunkPdc = sprinklerLocation.getChunk().getPersistentDataContainer();
        String prefix = sprinklerKeyPrefix(sprinklerLocation);
        NamespacedKey chargesKey = new NamespacedKey(plugin, prefix + "_charges");
        NamespacedKey lastDayKey = new NamespacedKey(plugin, prefix + "_last_day");
        NamespacedKey fractionKey = new NamespacedKey(plugin, prefix + "_charge_fraction");

        double currentDay = timeProvider.getCurrentDay(sprinklerLocation.getWorld());
        double lastDay = chunkPdc.getOrDefault(lastDayKey, PersistentDataType.DOUBLE, currentDay);
        int charges = chunkPdc.getOrDefault(chargesKey, PersistentDataType.INTEGER, 0);
        double fractionProgress = chunkPdc.getOrDefault(fractionKey, PersistentDataType.DOUBLE, 0.0);

        double elapsedDays = Math.max(0.0, currentDay - lastDay);
        double coldDays = Math.min(elapsedDays, charges);
        double totalProgress = fractionProgress + coldDays;
        int usedDays = Math.min(charges, (int) Math.floor(totalProgress));
        double remainingFraction = totalProgress - usedDays;

        if (applyHydration && usedDays > 0) {
            hydrateArea(sprinklerLocation, SPRINKLER_RADIUS);
        }

        chunkPdc.set(chargesKey, PersistentDataType.INTEGER, charges - usedDays);
        chunkPdc.set(lastDayKey, PersistentDataType.DOUBLE, currentDay);
        chunkPdc.set(fractionKey, PersistentDataType.DOUBLE, remainingFraction);
    }

    private int getCanCharges(ItemStack can) {
        if (!can.hasItemMeta()) {
            return 0;
        }
        return can.getItemMeta().getPersistentDataContainer().getOrDefault(keyCanCharges, PersistentDataType.INTEGER, 0);
    }

    private boolean isWateringCan(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return false;
        }
        return item.getItemMeta().getPersistentDataContainer().getOrDefault(keyCanMarker, PersistentDataType.BOOLEAN, false);
    }

    private boolean isSprinklerItem(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return false;
        }
        return item.getItemMeta().getPersistentDataContainer().getOrDefault(keySprinklerMarker, PersistentDataType.BOOLEAN, false);
    }

    private String sprinklerKeyPrefix(Location location) {
        return "sprinkler_" + location.getBlockX() + "_" + location.getBlockY() + "_" + location.getBlockZ();
    }
}
