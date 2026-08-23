package cl.nico.realisticsurvival.appliances;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Directional;
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

/**
 * Maneja el ciclo de vida fisico de los electrodomesticos (Refrigerador, Congelador):
 * colocacion/remocion del bloque real ({@code Dropper} para el Refrigerador,
 * {@code Dispenser} para el Congelador — ya distinguibles entre si con su textura vanilla,
 * sin necesitar Resource Pack) y deteccion de la interaccion del jugador. Responsabilidad
 * unica: fisica + deteccion de interaccion. NO contiene logica de inventario (ver
 * {@link ApplianceGUI}) ni de calculo de hielo/pudricion offline (ver {@link CatchUpProcessor})
 * — cero ticking activo, todo reactivo a eventos.
 * <p>
 * <b>Por que un bloque real y no un Barrier + entidad superpuesta:</b> la version anterior
 * usaba {@code Material.BARRIER} (para la hitbox) con un {@code ItemDisplay} renderizado
 * encima (para el modelo visual), lo que obligaba a: simular manualmente la rotura en
 * supervivencia (Barrier es irrompible, {@link BlockBreakEvent} nunca llegaba a dispararse
 * ahi) por un lado, y a escuchar por separado la rotura instantanea real de creativo por
 * otro; mantener sincronizados dos objetos independientes (bloque + entidad) via UUID en el
 * PDC del chunk; y depender de un Resource Pack para verse bien (con un fallback al Material
 * base sin el). Usar directamente {@code Dropper}/{@code Dispenser} elimina las tres cosas:
 * son bloques con dureza normal (rotura vanilla real, un solo {@link #onBlockBreak} cubre
 * survival y creativo por igual), tienen su propio {@code Directional} nativo para la
 * orientacion (nada de matrices de transformacion a mano), y su apariencia SIEMPRE es
 * correcta (es la textura vanilla real del bloque, no una aproximacion).
 * <p>
 * <b>Distincion de un Dropper/Dispenser vanilla comun:</b> el bloque en si no lleva ninguna
 * marca visual — lo que lo distingue de un Dropper/Dispenser cualquiera que un jugador haya
 * colocado por su cuenta es unicamente la entrada en el PDC del {@link org.bukkit.Chunk}
 * (ver {@link #isTrackedAppliance}). Sin esa entrada, el bloque se comporta 100% vanilla
 * (abre su propia GUI de Dropper/Dispenser real, no la nuestra).
 * <p>
 * <b>Limitacion conocida:</b> la colocacion escribe el bloque directamente
 * ({@code Block#setType}) sin pasar por el pipeline normal de {@code BlockPlaceEvent} (igual
 * que la version anterior con Barrier) — un plugin de proteccion de terreno (WorldGuard y
 * similares) no vera este placement como un evento cancelable estandar.
 * <p>
 * <b>Orientacion:</b> al colocarse, el bloque queda mirando hacia el jugador que lo coloco,
 * restringido a los 4 puntos cardinales (N/S/E/O — nunca arriba/abajo, a diferencia de un
 * Dropper/Dispenser vanilla real que si puede apuntar verticalmente): un electrodomestico
 * que "mirara" hacia arriba/abajo no tendria una cara practica para hacerle click. Ver
 * {@link #resolvePlayerFacingAppliance}.
 */
public final class ApplianceManager implements Listener {

    /** Tipos de electrodomestico soportados (seccion 4). */
    public enum ApplianceType {
        /** Dropper: distinguible del Congelador (Dispensador) por su textura vanilla real. */
        FRIDGE(Material.DROPPER, "Refrigerador"),
        /** Dispensador: distinguible del Refrigerador (Dropper) por su textura vanilla real. */
        FREEZER(Material.DISPENSER, "Congelador");

        private final Material material;
        private final String displayName;

        ApplianceType(Material material, String displayName) {
            this.material = material;
            this.displayName = displayName;
        }

        /** Material real (bloque colocado Y item fisico) de este tipo de electrodomestico. */
        public Material getMaterial() {
            return material;
        }

        public String getDisplayName() {
            return displayName;
        }
    }

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
     * Crea el ItemStack "fisico" de un electrodomestico (para dar/dropear): un
     * {@link ApplianceType#getMaterial()} real con nombre propio y un marcador en el PDC
     * para reconocerlo al colocarlo (ver {@link #placeAppliance}).
     */
    public ItemStack createApplianceItem(ApplianceType type) {
        ItemStack item = new ItemStack(type.getMaterial());
        item.editMeta(meta -> {
            meta.displayName(Component.text(type.getDisplayName(), NamedTextColor.WHITE)
                    .decoration(TextDecoration.ITALIC, false));
            meta.getPersistentDataContainer().set(keyItemType, PersistentDataType.STRING, type.name());
        });
        return item;
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            // Bukkit dispara este evento dos veces (mano principal y secundaria); nos
            // quedamos solo con una para no procesar todo dos veces.
            return;
        }
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }

        Block clicked = event.getClickedBlock();
        if (clicked == null) {
            return;
        }

        if (isApplianceMaterial(clicked.getType()) && isTrackedAppliance(clicked.getLocation())) {
            // Es un electrodomestico nuestro: se abre la GUI virtual en vez de la GUI real
            // de Dropper/Dispenser vanilla.
            event.setCancelled(true);
            applianceGUI.open(event.getPlayer(), clicked.getLocation());
            return;
        }

        ApplianceType type = readItemApplianceType(event.getItem());
        if (type != null) {
            event.setCancelled(true);
            placeAppliance(event.getPlayer(), event.getItem(), clicked, event.getBlockFace(), type);
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

        target.setType(type.getMaterial());
        if (target.getBlockData() instanceof Directional directional) {
            directional.setFacing(resolvePlayerFacingAppliance(player));
            target.setBlockData(directional);
        }

        PersistentDataContainer chunkPdc = target.getChunk().getPersistentDataContainer();
        String prefix = applianceKeyPrefix(target.getLocation());
        chunkPdc.set(new NamespacedKey(plugin, prefix + "_type"), PersistentDataType.STRING, type.name());

        if (player.getGameMode() != GameMode.CREATIVE) {
            handItem.setAmount(handItem.getAmount() - 1);
        }
    }

    /**
     * Rotura del electrodomestico. A diferencia de la version anterior (Barrier, irrompible
     * en supervivencia), Dropper/Dispenser tienen dureza normal — un solo handler cubre
     * survival (rotura vanilla con la herramienta correcta) y creativo (instantanea) por
     * igual, sin necesitar caminos separados.
     */
    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        if (!isApplianceMaterial(block.getType()) || !isTrackedAppliance(block.getLocation())) {
            return;
        }

        Player player = event.getPlayer();
        // Se calcula ANTES de cleanupAppliance porque ese metodo deja el bloque en AIR, y
        // Block#isPreferredTool necesita el bloque/BlockData original para decidir — mismo
        // criterio que usa vanilla para saber si un Dropper/Dispenser suelta item al
        // romperse (picota) o no.
        boolean shouldDrop = player.getGameMode() != GameMode.CREATIVE
                && block.isPreferredTool(player.getInventory().getItemInMainHand());

        ApplianceType type = cleanupAppliance(block);
        // Controlamos el drop nosotros (item con nombre/PDC propio, no un Dropper/Dispenser
        // en blanco): se cancela el drop vanilla incondicionalmente y se decide aparte.
        event.setDropItems(false);
        if (shouldDrop && type != null) {
            block.getWorld().dropItemNaturally(
                    block.getLocation().clone().add(0.5, 0.5, 0.5), createApplianceItem(type));
        }
    }

    /**
     * Limpieza al romper: vuelca el inventario virtual como drops y borra el estado del PDC
     * del chunk. NO decide si se devuelve el item fisico — eso lo resuelve
     * {@link #onBlockBreak} segun el modo de juego y la herramienta usada.
     *
     * @return el {@link ApplianceType} que tenia el bloque, o {@code null} si por algun
     *         motivo ya no estaba trackeado.
     */
    private ApplianceType cleanupAppliance(Block block) {
        Location location = block.getLocation();
        ApplianceType type = readBlockApplianceType(location);

        applianceGUI.dropContentsAndClear(location, block.getWorld());

        PersistentDataContainer chunkPdc = block.getChunk().getPersistentDataContainer();
        chunkPdc.remove(new NamespacedKey(plugin, applianceKeyPrefix(location) + "_type"));

        block.setType(Material.AIR);
        return type;
    }

    public boolean isTrackedAppliance(Location location) {
        return readBlockApplianceType(location) != null;
    }

    private boolean isApplianceMaterial(Material material) {
        for (ApplianceType type : ApplianceType.values()) {
            if (type.getMaterial() == material) {
                return true;
            }
        }
        return false;
    }

    private ApplianceType readBlockApplianceType(Location location) {
        PersistentDataContainer chunkPdc = location.getChunk().getPersistentDataContainer();
        String raw = chunkPdc.get(new NamespacedKey(plugin, applianceKeyPrefix(location) + "_type"), PersistentDataType.STRING);
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

    /**
     * Punto cardinal (N/S/E/O, nunca arriba/abajo) hacia el que debe quedar mirando el
     * electrodomestico para encarar al jugador que lo coloca — el mismo criterio que usa un
     * horno vanilla: el frente apunta hacia donde estaba parado el jugador, es decir, el
     * opuesto de hacia donde el jugador estaba mirando.
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
}
