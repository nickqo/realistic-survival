package cl.nico.realisticsurvival.farming;

import cl.nico.realisticsurvival.api.time.TimeProvider;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Ageable;
import org.bukkit.block.data.type.Farmland;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockGrowEvent;
import org.bukkit.event.block.MoistureChangeEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Listener puro: anula la mecanica vanilla de hidratacion infinita (9x9) del Farmland y
 * resuelve, mediante catch-up, la lluvia, la reversion termica invernal y el estres
 * hidrico de los cultivos (secciones 5.1 y 5.3 del documento). No maneja las herramientas
 * de riego activo (ver {@link WateringManager}) — SRP.
 * <p>
 * Farmland no es un tile-entity (no tiene PDC propio), asi que el estado de "seco desde
 * el dia X" de cada bloque se guarda en el PDC del {@link org.bukkit.Chunk}, igual que en
 * {@code appliances}. Para no escanear cada chunk entero al cargarlo, se mantiene un
 * indice liviano ({@link #keyTrackedIndex}) con solo los bloques actualmente en estres
 * hidrico — el unico caso que realmente necesita revisar "dias transcurridos" despues de
 * un catch-up.
 */
public final class FarmingListener implements Listener {

    /** Dias in-game consecutivos con humedad 0 antes de que el cultivo retroceda/muera. */
    private static final int STRESS_DAYS_THRESHOLD = 3;

    private static final String DRY_SINCE_PREFIX = "dry_since_";

    private final Plugin plugin;
    private final TimeProvider timeProvider;
    private final NamespacedKey keyTrackedIndex;

    public FarmingListener(Plugin plugin, TimeProvider timeProvider) {
        this.plugin = plugin;
        this.timeProvider = timeProvider;
        this.keyTrackedIndex = new NamespacedKey(plugin, "tracked_dry_farmland");
    }

    @EventHandler(ignoreCancelled = true)
    public void onMoistureChange(MoistureChangeEvent event) {
        if (!(event.getBlock().getBlockData() instanceof Farmland current)
                || !(event.getNewState().getBlockData() instanceof Farmland next)) {
            return;
        }

        if (next.getMoisture() > current.getMoisture()) {
            // Vanilla intenta hidratar por cercania a agua (9x9 infinito): se anula. El
            // riego debe venir de lluvia, Regadera Manual o Aspersor.
            event.setCancelled(true);
            return;
        }

        if (next.getMoisture() == 0) {
            // Evaporacion natural vanilla que llega a 0: arranca el seguimiento de estres.
            trackDrySince(event.getBlock().getLocation(), timeProvider.getCurrentDay(event.getBlock().getWorld()));
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onCropGrow(BlockGrowEvent event) {
        Block soil = event.getBlock().getRelative(BlockFace.DOWN);
        if (!(soil.getBlockData() instanceof Farmland)) {
            return;
        }
        if (resolveFarmland(soil)) {
            // El cultivo ya fue penalizado (retrocedido/eliminado) por estres hidrico o
            // por reversion termica: no crece en este ciclo.
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        PersistentDataContainer chunkPdc = event.getChunk().getPersistentDataContainer();
        String raw = chunkPdc.get(keyTrackedIndex, PersistentDataType.STRING);
        if (raw == null || raw.isBlank()) {
            return;
        }

        for (String entry : raw.split(",")) {
            String[] parts = entry.split("_");
            if (parts.length != 3) {
                continue;
            }
            Block block = event.getWorld().getBlockAt(
                    Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
            if (block.getBlockData() instanceof Farmland) {
                resolveFarmland(block);
            } else {
                // El bloque ya no es Farmland (fue reemplazado por otro medio): se limpia.
                untrack(block.getLocation());
            }
        }
    }

    /**
     * Catch-up de un bloque de Farmland: aplica lluvia, reversion termica invernal o
     * avance del estres hidrico segun corresponda.
     *
     * @return true si el cultivo de encima fue penalizado (retrocedido o destruido) en
     *         esta pasada.
     */
    private boolean resolveFarmland(Block farmlandBlock) {
        Farmland data = (Farmland) farmlandBlock.getBlockData();
        double currentDay = timeProvider.getCurrentDay(farmlandBlock.getWorld());

        boolean skyExposed = farmlandBlock.getRelative(BlockFace.UP).getLightFromSky() >= 15;
        boolean raining = timeProvider.isRaining(farmlandBlock.getWorld());

        if (raining && skyExposed) {
            data.setMoisture(data.getMaximumMoisture());
            farmlandBlock.setBlockData(data);
            untrack(farmlandBlock.getLocation());
            return false;
        }

        int airTemperature = timeProvider.getTemperature(farmlandBlock.getLocation());
        if (data.getMoisture() > 0 && airTemperature < 0) {
            // Evaporacion termica invernal: la tierra humeda se revierte a Dirt y el
            // cultivo se destruye (seccion 5.1).
            destroyCrop(farmlandBlock);
            farmlandBlock.setType(Material.DIRT);
            untrack(farmlandBlock.getLocation());
            return true;
        }

        if (data.getMoisture() > 0) {
            // Se rehidrato por otra via (regadera/aspersor): ya no esta en estres.
            untrack(farmlandBlock.getLocation());
            return false;
        }

        double drySince = readDrySince(farmlandBlock.getLocation(), currentDay);
        double daysDry = currentDay - drySince;
        if (daysDry < STRESS_DAYS_THRESHOLD) {
            return false;
        }

        boolean penalized = regressOrKillCrop(farmlandBlock);
        // Se reinicia el contador: si sigue seco, la proxima penalizacion ocurre tras otro
        // umbral completo de dias (no todas de una vez).
        trackDrySince(farmlandBlock.getLocation(), currentDay);
        return penalized;
    }

    private boolean regressOrKillCrop(Block farmlandBlock) {
        Block cropBlock = farmlandBlock.getRelative(BlockFace.UP);
        if (!(cropBlock.getBlockData() instanceof Ageable ageable)) {
            return false;
        }
        if (ageable.getAge() > 0) {
            ageable.setAge(ageable.getAge() - 1);
            cropBlock.setBlockData(ageable);
        } else {
            cropBlock.setType(Material.DEAD_BUSH);
        }
        return true;
    }

    private void destroyCrop(Block farmlandBlock) {
        Block cropBlock = farmlandBlock.getRelative(BlockFace.UP);
        if (cropBlock.getBlockData() instanceof Ageable) {
            cropBlock.setType(Material.DEAD_BUSH);
        }
    }

    private void trackDrySince(Location farmlandLocation, double day) {
        PersistentDataContainer chunkPdc = farmlandLocation.getChunk().getPersistentDataContainer();
        NamespacedKey key = new NamespacedKey(plugin, DRY_SINCE_PREFIX + coordKey(farmlandLocation));
        if (!chunkPdc.has(key, PersistentDataType.DOUBLE)) {
            chunkPdc.set(key, PersistentDataType.DOUBLE, day);
        }
        addToIndex(farmlandLocation);
    }

    private double readDrySince(Location farmlandLocation, double fallback) {
        PersistentDataContainer chunkPdc = farmlandLocation.getChunk().getPersistentDataContainer();
        NamespacedKey key = new NamespacedKey(plugin, DRY_SINCE_PREFIX + coordKey(farmlandLocation));
        return chunkPdc.getOrDefault(key, PersistentDataType.DOUBLE, fallback);
    }

    private void untrack(Location farmlandLocation) {
        PersistentDataContainer chunkPdc = farmlandLocation.getChunk().getPersistentDataContainer();
        chunkPdc.remove(new NamespacedKey(plugin, DRY_SINCE_PREFIX + coordKey(farmlandLocation)));
        removeFromIndex(farmlandLocation);
    }

    private void addToIndex(Location location) {
        PersistentDataContainer chunkPdc = location.getChunk().getPersistentDataContainer();
        String entry = coordKey(location);
        String raw = chunkPdc.get(keyTrackedIndex, PersistentDataType.STRING);
        if (raw == null || raw.isBlank()) {
            chunkPdc.set(keyTrackedIndex, PersistentDataType.STRING, entry);
            return;
        }
        if (!Arrays.asList(raw.split(",")).contains(entry)) {
            chunkPdc.set(keyTrackedIndex, PersistentDataType.STRING, raw + "," + entry);
        }
    }

    private void removeFromIndex(Location location) {
        PersistentDataContainer chunkPdc = location.getChunk().getPersistentDataContainer();
        String raw = chunkPdc.get(keyTrackedIndex, PersistentDataType.STRING);
        if (raw == null) {
            return;
        }
        List<String> remaining = new ArrayList<>(Arrays.asList(raw.split(",")));
        remaining.remove(coordKey(location));
        if (remaining.isEmpty()) {
            chunkPdc.remove(keyTrackedIndex);
        } else {
            chunkPdc.set(keyTrackedIndex, PersistentDataType.STRING, String.join(",", remaining));
        }
    }

    private String coordKey(Location location) {
        return location.getBlockX() + "_" + location.getBlockY() + "_" + location.getBlockZ();
    }
}
