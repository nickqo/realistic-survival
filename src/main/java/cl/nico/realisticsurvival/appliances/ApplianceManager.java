package cl.nico.realisticsurvival.appliances;

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
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;

/**
 * Maneja el ciclo de vida fisico/visual de los electrodomesticos (Refrigerador,
 * Congelador): colocacion/remocion del bloque {@code BARRIER} que provee la hitbox
 * solida, y spawn/despawn de la entidad {@link ItemDisplay} que renderiza el modelo 3D
 * (via CustomModelData de un Resource Pack) sobre esa barrera. Responsabilidad unica:
 * fisica + render + deteccion de la interaccion del jugador. NO contiene logica de
 * inventario (ver {@link ApplianceGUI}) ni de calculo de hielo/pudricion offline (ver
 * {@link CatchUpProcessor}) — cero ticking activo, todo reactivo a eventos.
 * <p>
 * <b>Nota importante:</b> {@code Material.BARRIER} es irrompible en supervivencia (dureza
 * -1), por lo que {@link BlockBreakEvent} nunca llega a dispararse ahi. Por eso "romper" el
 * electrodomestico en supervivencia se resuelve con un click izquierdo
 * ({@link Action#LEFT_CLICK_BLOCK}) + picota sobre la barrera en {@link #onInteract}. Pero
 * en modo CREATIVO, Minecraft SI permite romper bloques normalmente irrompibles como
 * Barrier con un solo click (sin picota) — eso SI dispara {@link BlockBreakEvent} (ver
 * {@link #onBlockBreak}), un camino totalmente distinto al de supervivencia. Si no se
 * escuchara ese evento, el bloque se rompe pero el {@link ItemDisplay}/PDC quedan huerfanos
 * (bug real: "bloque fantasma" flotando sin hitbox). Se deja romper libre en creativo (igual
 * que cualquier bloque vanilla ahi, sin picota) pero corriendo la misma limpieza.
 * <p>
 * <b>Compatibilidad sin Resource Pack:</b> el {@link ItemDisplay} usa como base el
 * {@link ApplianceType#getFallbackMaterial()} de cada tipo (Dropper para el Refrigerador,
 * Dispensador para el Congelador — visualmente distinguibles entre si incluso sin Resource
 * Pack) en vez de un lienzo neutro tipo {@code PAPER}. Sin el Resource Pack, el jugador ve
 * ese bloque vanilla (razonable, no roto); con el Resource Pack cargado, el
 * {@code CustomModelData} lo reemplaza por el modelo 3D real. El mecanismo (hitbox, GUI,
 * catch-up) funciona identico en ambos casos.
 * <p>
 * <b>Orientacion:</b> al colocarse, el {@link ItemDisplay} queda mirando hacia el jugador
 * que lo coloco, restringido a los 4 puntos cardinales (N/S/E/O — nunca diagonal ni
 * arriba/abajo), igual que un horno o dispensador vanilla. Ver {@link #resolvePlayerFacingAppliance}.
 */
public final class ApplianceManager implements Listener {

    /** Tipos de electrodomestico soportados (seccion 4). */
    public enum ApplianceType {
        /** Dropper: distinguible del Congelador (Dispensador) incluso sin Resource Pack. */
        FRIDGE(1_100_001, Material.DROPPER, "Refrigerador"),
        /** Dispensador: distinguible del Refrigerador (Dropper) incluso sin Resource Pack. */
        FREEZER(1_100_002, Material.DISPENSER, "Congelador");

        private final int customModelData;
        private final Material fallbackMaterial;
        private final String displayName;

        ApplianceType(int customModelData, Material fallbackMaterial, String displayName) {
            this.customModelData = customModelData;
            this.fallbackMaterial = fallbackMaterial;
            this.displayName = displayName;
        }

        public int getCustomModelData() {
            return customModelData;
        }

        /** Material vanilla usado como base del modelo cuando no hay Resource Pack activo. */
        public Material getFallbackMaterial() {
            return fallbackMaterial;
        }

        public String getDisplayName() {
            return displayName;
        }
    }

    /**
     * Rotacion (grados, eje Y) aplicada al {@link ItemDisplay} para cada punto cardinal.
     * Asume que el modelo del Material base (ver {@link ApplianceType#getFallbackMaterial()})
     * "mira" hacia el SUR por defecto en su orientacion sin rotar — es la convencion mas
     * comun en los modelos de bloque vanilla, pero es un valor best-effort: no hay forma de
     * verificarlo sin un cliente de Minecraft corriendo. Si en el juego el frente queda
     * girado, basta con correr todos los valores de este mapa en +90/-90/180 (ej. NORTH:0,
     * EAST:90, SOUTH:180, WEST:270).
     */
    private static final Map<BlockFace, Float> CARDINAL_Y_DEGREES = new EnumMap<>(Map.of(
            BlockFace.SOUTH, 0f,
            BlockFace.WEST, 90f,
            BlockFace.NORTH, 180f,
            BlockFace.EAST, 270f
    ));

    private final Plugin plugin;
    private final ApplianceGUI applianceGUI;
    private final NamespacedKey keyItemType;

    public ApplianceManager(Plugin plugin, ApplianceGUI applianceGUI) {
        this.plugin = plugin;
        this.applianceGUI = applianceGUI;
        this.keyItemType = new NamespacedKey(plugin, "appliance_item_type");
    }

    /**
     * Prefijo comun de las claves PDC (en el {@link org.bukkit.Chunk}) para un bloque de
     * electrodomestico, en base a sus coordenadas absolutas. Compartido con
     * {@link ApplianceGUI} para que ambas clases lean/escriban el mismo namespace de datos
     * sin acoplarse directamente entre si.
     */
    public static String applianceKeyPrefix(Location location) {
        return "appliance_" + location.getBlockX() + "_" + location.getBlockY() + "_" + location.getBlockZ();
    }

    /**
     * Crea el ItemStack "fisico" de un electrodomestico (para dar/dropear): el Material
     * base es {@link ApplianceType#getFallbackMaterial()} (se ve razonable aunque el
     * servidor/cliente no tenga el Resource Pack cargado) con el CustomModelData del tipo
     * encima, mas un marcador en el PDC para reconocerlo al colocarlo. Los valores de
     * CustomModelData son placeholder: deben ajustarse al Resource Pack real.
     */
    public ItemStack createApplianceItem(ApplianceType type) {
        ItemStack item = buildModelItem(type);
        item.editMeta(meta -> meta.getPersistentDataContainer().set(keyItemType, PersistentDataType.STRING, type.name()));
        return item;
    }

    private ItemStack buildModelItem(ApplianceType type) {
        ItemStack item = new ItemStack(type.getFallbackMaterial());
        item.setData(DataComponentTypes.CUSTOM_MODEL_DATA,
                CustomModelData.customModelData().addFloat(type.getCustomModelData()).build());
        item.editMeta(meta -> meta.displayName(Component.text(type.getDisplayName(), NamedTextColor.WHITE)
                .decoration(TextDecoration.ITALIC, false)));
        return item;
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            // Bukkit dispara este evento dos veces (mano principal y secundaria); nos
            // quedamos solo con una para no procesar todo dos veces.
            return;
        }

        Block clicked = event.getClickedBlock();
        if (clicked == null) {
            return;
        }

        if (event.getAction() == Action.RIGHT_CLICK_BLOCK
                && clicked.getType() == Material.BARRIER
                && isTrackedAppliance(clicked.getLocation())) {
            event.setCancelled(true);
            applianceGUI.open(event.getPlayer(), clicked.getLocation());
            return;
        }

        if (event.getAction() == Action.LEFT_CLICK_BLOCK
                && clicked.getType() == Material.BARRIER
                && isTrackedAppliance(clicked.getLocation())) {
            if (!isHoldingPickaxe(event.getItem())) {
                // Igual que un Dropper/Dispensador real: hace falta una picota. Sin una,
                // no pasa nada (no simulamos el tiempo de picado real para no necesitar
                // ticking activo: o se rompe con picota en un click, o no se rompe).
                return;
            }
            event.setCancelled(true);
            breakAppliance(event.getPlayer(), clicked);
            return;
        }

        if (event.getAction() == Action.RIGHT_CLICK_BLOCK) {
            ApplianceType type = readItemApplianceType(event.getItem());
            if (type != null) {
                event.setCancelled(true);
                placeAppliance(event.getPlayer(), event.getItem(), clicked, event.getBlockFace(), type);
            }
        }
    }

    private void placeAppliance(Player player, ItemStack handItem, Block clickedBlock, BlockFace face, ApplianceType type) {
        // Si el bloque clickeado es "reemplazable" (nieve en capas, pasto alto, agua,
        // etc.) se coloca ENCIMA de su propia posicion, igual que vanilla — si no,
        // se coloca adyacente segun la cara clickeada.
        Block target = clickedBlock.isReplaceable() ? clickedBlock : clickedBlock.getRelative(face);
        if (!target.isEmpty() && !target.isReplaceable()) {
            return;
        }

        target.setType(Material.BARRIER);
        Location center = target.getLocation().add(0.5, 0.5, 0.5);
        ItemDisplay display = spawnDisplay(center, type, resolvePlayerFacingAppliance(player));

        PersistentDataContainer chunkPdc = target.getChunk().getPersistentDataContainer();
        String prefix = applianceKeyPrefix(target.getLocation());
        chunkPdc.set(new NamespacedKey(plugin, prefix + "_type"), PersistentDataType.STRING, type.name());
        chunkPdc.set(new NamespacedKey(plugin, prefix + "_display_uuid"), PersistentDataType.STRING,
                display.getUniqueId().toString());

        if (player.getGameMode() != GameMode.CREATIVE) {
            handItem.setAmount(handItem.getAmount() - 1);
        }
    }

    /** Rotura manual en supervivencia (click izquierdo + picota, ver {@link #onInteract}). */
    private void breakAppliance(Player player, Block barrierBlock) {
        ApplianceType type = cleanupAppliance(barrierBlock);
        if (type != null && player.getGameMode() != GameMode.CREATIVE) {
            barrierBlock.getWorld().dropItemNaturally(
                    barrierBlock.getLocation().clone().add(0.5, 0.5, 0.5), createApplianceItem(type));
        }
    }

    /**
     * Rotura instantanea en modo creativo (ver Javadoc de la clase): Minecraft ya rompe el
     * Barrier solo, asi que aca solo hace falta la limpieza — sin dropear el item fisico,
     * igual que cualquier bloque roto en creativo vanilla.
     */
    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        if (block.getType() != Material.BARRIER || !isTrackedAppliance(block.getLocation())) {
            return;
        }
        event.setDropItems(false);
        cleanupAppliance(block);
    }

    /**
     * Limpieza compartida entre la rotura manual (supervivencia) y la instantanea
     * (creativo): despawnea el {@link ItemDisplay}, vuelca el inventario virtual como
     * drops, y borra el estado del PDC del chunk. NO decide si se devuelve el item fisico
     * — eso lo resuelve cada llamador segun el modo de juego.
     *
     * @return el {@link ApplianceType} que tenia el bloque, o {@code null} si por algun
     *         motivo ya no estaba trackeado.
     */
    private ApplianceType cleanupAppliance(Block barrierBlock) {
        Location location = barrierBlock.getLocation();
        ApplianceType type = readBlockApplianceType(location);

        applianceGUI.dropContentsAndClear(location, barrierBlock.getWorld());
        despawnDisplay(location);

        PersistentDataContainer chunkPdc = barrierBlock.getChunk().getPersistentDataContainer();
        String prefix = applianceKeyPrefix(location);
        chunkPdc.remove(new NamespacedKey(plugin, prefix + "_type"));
        chunkPdc.remove(new NamespacedKey(plugin, prefix + "_display_uuid"));

        barrierBlock.setType(Material.AIR);
        return type;
    }

    public boolean isTrackedAppliance(Location barrierLocation) {
        return readBlockApplianceType(barrierLocation) != null;
    }

    private boolean isHoldingPickaxe(ItemStack item) {
        return item != null && Tag.ITEMS_PICKAXES.isTagged(item.getType());
    }

    private ApplianceType readBlockApplianceType(Location barrierLocation) {
        PersistentDataContainer chunkPdc = barrierLocation.getChunk().getPersistentDataContainer();
        String prefix = applianceKeyPrefix(barrierLocation);
        String raw = chunkPdc.get(new NamespacedKey(plugin, prefix + "_type"), PersistentDataType.STRING);
        return parseType(raw);
    }

    private ApplianceType readItemApplianceType(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return null;
        }
        String raw = item.getItemMeta().getPersistentDataContainer().get(keyItemType, PersistentDataType.STRING);
        return parseType(raw);
    }

    private ApplianceType parseType(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return ApplianceType.valueOf(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private ItemDisplay spawnDisplay(Location center, ApplianceType type, BlockFace facing) {
        return center.getWorld().spawn(center, ItemDisplay.class, entity -> {
            entity.setItemStack(buildModelItem(type));
            entity.setBillboard(Display.Billboard.FIXED);
            entity.setPersistent(true);
            entity.setTransformation(cardinalTransformation(facing));
        });
    }

    /**
     * Punto cardinal (N/S/E/O, nunca diagonal ni arriba/abajo) hacia el que debe quedar
     * mirando el electrodomestico para encarar al jugador que lo coloca — el mismo
     * criterio que usa un horno/dispensador vanilla: el frente apunta hacia donde estaba
     * parado el jugador, es decir, el opuesto de hacia donde el jugador estaba mirando.
     */
    private BlockFace resolvePlayerFacingAppliance(Player player) {
        return playerCardinalLookDirection(player).getOppositeFace();
    }

    /**
     * Direccion cardinal hacia la que mira el jugador, redondeada al punto cardinal mas
     * cercano (yaw de Bukkit: 0=sur, 90=oeste, 180=norte, 270=este, sentido horario).
     */
    private BlockFace playerCardinalLookDirection(Player player) {
        float yaw = ((player.getLocation().getYaw() % 360) + 360) % 360;
        int index = Math.round(yaw / 90f) % 4;
        return switch (index) {
            case 0 -> BlockFace.SOUTH;
            case 1 -> BlockFace.WEST;
            case 2 -> BlockFace.NORTH;
            default -> BlockFace.EAST;
        };
    }

    private Transformation cardinalTransformation(BlockFace facing) {
        float degrees = CARDINAL_Y_DEGREES.getOrDefault(facing, 0f);
        return new Transformation(
                new Vector3f(0f, 0f, 0f),
                new AxisAngle4f((float) Math.toRadians(degrees), 0f, 1f, 0f),
                new Vector3f(1f, 1f, 1f),
                new AxisAngle4f(0f, 0f, 1f, 0f));
    }

    private void despawnDisplay(Location barrierLocation) {
        PersistentDataContainer chunkPdc = barrierLocation.getChunk().getPersistentDataContainer();
        NamespacedKey key = new NamespacedKey(plugin, applianceKeyPrefix(barrierLocation) + "_display_uuid");
        String raw = chunkPdc.get(key, PersistentDataType.STRING);
        if (raw == null) {
            return;
        }
        Entity entity = barrierLocation.getWorld().getEntity(UUID.fromString(raw));
        if (entity != null) {
            entity.remove();
        }
    }
}
