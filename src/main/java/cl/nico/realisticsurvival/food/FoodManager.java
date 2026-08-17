package cl.nico.realisticsurvival.food;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.FoodProperties;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Nucleo matematico del sistema de descomposicion de alimentos.
 * <p>
 * Responsabilidad unica: calcular y persistir la "frescura" (0-100, siempre en decenas) de
 * un {@link ItemStack} de comida, y reflejarla visualmente mediante los DataComponentTypes
 * nativos de 1.21 ({@code minecraft:max_damage} / {@code minecraft:damage}). NO escucha
 * eventos de Bukkit directamente (eso es responsabilidad de {@link ConsumeListener} y de
 * {@code inventory.InventoryListener}) — mantiene la logica de negocio separada de los
 * listeners (SRP). Es tambien la UNICA clase autorizada a tocar {@link DataComponentTypes}
 * y el {@link PersistentDataContainer} de items de comida: el resto del plugin pasa siempre
 * por los metodos publicos de esta clase.
 * <p>
 * Prohibido usar NBT antiguo (net.minecraft.server / NBTTagCompound / librerias NBT de
 * terceros): toda persistencia custom (frescura, timestamp, frozen) se hace via
 * {@link PersistentDataContainer}, y toda representacion visual via DataComponentTypes.
 * <p>
 * <b>Inicializacion perezosa:</b> el plugin no intercepta cada posible fuente de comida
 * (crafteo, horno, comercio, drops de mobs, etc.). En su lugar, cualquier item de comida
 * vanilla que aun no tenga el PDC de frescura se considera "recien creado" (100%) la
 * primera vez que {@link #calculateFreshness} lo toca — que ocurre naturalmente al
 * interactuar con el (consumir, hacer click, recoger del suelo).
 */
public final class FoodManager {

    /** Sin bonus de almacenamiento: decae a velocidad normal (temperatura ambiente). */
    public static final double AMBIENT_MULTIPLIER = 1.0;

    /** Refrigerador: multiplica x3 la duracion del alimento (seccion 4.3). */
    public static final double FRIDGE_MULTIPLIER = 3.0;

    /** Congelador: detiene la pudricion por completo (seccion 4.3). */
    public static final double FREEZER_MULTIPLIER = Double.POSITIVE_INFINITY;

    /** Dias in-game a temperatura ambiente para que un item pierda el flag {@code frozen}. */
    public static final int THAW_DAYS = 1;

    /** Dias que tarda en pudrirse por completo (100% -> 0%) un alimento no listado explicitamente. */
    private static final int DEFAULT_DECAY_DAYS = 8;

    /** Resolucion de la barra de daño visual (10 segmentos = 1 por cada decena de frescura). */
    private static final int DAMAGE_BAR_RESOLUTION = 10;

    /**
     * Tabla de duracion base (dias in-game hasta 0%) por alimento vanilla. Ajustable; los
     * alimentos no listados usan {@link #DEFAULT_DECAY_DAYS}.
     */
    private static final Map<Material, Integer> BASE_DECAY_DAYS = new EnumMap<>(Material.class);
    static {
        // Crudos: se pudren rapido
        BASE_DECAY_DAYS.put(Material.BEEF, 4);
        BASE_DECAY_DAYS.put(Material.PORKCHOP, 4);
        BASE_DECAY_DAYS.put(Material.CHICKEN, 3);
        BASE_DECAY_DAYS.put(Material.MUTTON, 4);
        BASE_DECAY_DAYS.put(Material.RABBIT, 4);
        BASE_DECAY_DAYS.put(Material.COD, 3);
        BASE_DECAY_DAYS.put(Material.SALMON, 3);
        // Cocidos: duran mas que su version cruda
        BASE_DECAY_DAYS.put(Material.COOKED_BEEF, 10);
        BASE_DECAY_DAYS.put(Material.COOKED_PORKCHOP, 10);
        BASE_DECAY_DAYS.put(Material.COOKED_CHICKEN, 8);
        BASE_DECAY_DAYS.put(Material.COOKED_MUTTON, 9);
        BASE_DECAY_DAYS.put(Material.COOKED_RABBIT, 8);
        BASE_DECAY_DAYS.put(Material.COOKED_COD, 7);
        BASE_DECAY_DAYS.put(Material.COOKED_SALMON, 7);
        // Vegetales / panaderia / varios
        BASE_DECAY_DAYS.put(Material.BREAD, 6);
        BASE_DECAY_DAYS.put(Material.APPLE, 8);
        BASE_DECAY_DAYS.put(Material.CARROT, 10);
        BASE_DECAY_DAYS.put(Material.POTATO, 10);
        BASE_DECAY_DAYS.put(Material.BAKED_POTATO, 8);
        BASE_DECAY_DAYS.put(Material.BEETROOT, 9);
        BASE_DECAY_DAYS.put(Material.MELON_SLICE, 5);
        BASE_DECAY_DAYS.put(Material.PUMPKIN_PIE, 6);
        BASE_DECAY_DAYS.put(Material.COOKIE, 12);
        BASE_DECAY_DAYS.put(Material.SWEET_BERRIES, 5);
        BASE_DECAY_DAYS.put(Material.GLOW_BERRIES, 5);
        BASE_DECAY_DAYS.put(Material.DRIED_KELP, 15);
        BASE_DECAY_DAYS.put(Material.CHORUS_FRUIT, 20);
    }

    private final NamespacedKey keyFreshness;
    private final NamespacedKey keyLastCalcDay;
    private final NamespacedKey keyFrozen;
    private final NamespacedKey keyThawStartDay;

    public FoodManager(Plugin plugin) {
        this.keyFreshness = new NamespacedKey(plugin, "freshness");
        this.keyLastCalcDay = new NamespacedKey(plugin, "last_calc_day");
        this.keyFrozen = new NamespacedKey(plugin, "frozen");
        this.keyThawStartDay = new NamespacedKey(plugin, "thaw_start_day");
    }

    /**
     * Fases de descomposicion. Cada tier agrupa decenas de frescura y define color de barra,
     * penalizaciones nutricionales y efectos de estado al consumir.
     */
    public enum SpoilageTier {
        /** 80/90/100% — Verde. Comida y saturacion vanilla completas. */
        FRESCO,
        /** 40/50/60/70% — Amarilla. -30/40% de alimento, baja saturacion. */
        PASADO,
        /** 10/20/30% — Roja. Recuperacion minima. Hambre I, posible Nauseas. */
        MAL_ESTADO,
        /** 0% — Sin barra. Item transformado a "Restos Podridos". Veneno I, Hambre II. */
        PODRIDO
    }

    /**
     * Un item es "trackeable" si es un alimento vanilla al que le aplica este sistema.
     */
    public boolean isTrackable(ItemStack item) {
        return item != null && !item.getType().isAir() && item.getType().isEdible();
    }

    /**
     * Un item ya esta siendo trackeado (tiene el PDC de frescura escrito).
     */
    public boolean isTracked(ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) {
            return false;
        }
        return item.getItemMeta().getPersistentDataContainer().has(keyFreshness, PersistentDataType.INTEGER);
    }

    /**
     * Lee la frescura actual (0-100) almacenada en el PDC del item, SIN recalcular el
     * paso del tiempo. Uso interno / debug. Devuelve 100 si el item no esta trackeado.
     */
    public int getStoredFreshness(ItemStack item) {
        if (!isTracked(item)) {
            return 100;
        }
        return item.getItemMeta().getPersistentDataContainer()
                .getOrDefault(keyFreshness, PersistentDataType.INTEGER, 100);
    }

    /**
     * Calculo pasivo (catch-up): resuelve la frescura actual del item en base al timestamp
     * (dia in-game) guardado en su PDC vs. el dia actual entregado por TimeProvider,
     * ajustando segun el almacenamiento. Se invoca al interactuar con el item (abrir
     * inventario, click, consumir), nunca en un loop periodico. Persiste el resultado.
     *
     * @param item              item de comida a evaluar (se muta in-place)
     * @param currentDay        dia in-game absoluto actual
     * @param storageMultiplier {@link #AMBIENT_MULTIPLIER}, {@link #FRIDGE_MULTIPLIER} o
     *                          {@link #FREEZER_MULTIPLIER} segun donde este guardado el item
     * @return frescura resultante, redondeada a la decena mas cercana. Si el resultado es 0
     *         (tier {@link SpoilageTier#PODRIDO}), el {@code item} recibido NO queda
     *         transformado automaticamente — {@link ItemStack#setType} esta deprecado en
     *         favor de crear un ItemStack nuevo, lo que rompe la mutacion "in place". El
     *         llamador debe invocar {@link #transformToRotten} explicitamente y usar la
     *         referencia que devuelve (ver ese metodo para el porque).
     */
    public int calculateFreshness(ItemStack item, long currentDay, double storageMultiplier) {
        if (item == null || item.getType().isAir()) {
            throw new IllegalArgumentException("item no puede ser null/aire");
        }

        // Inicializacion perezosa: primera vez que se toca este item, nace fresco (100%).
        if (!isTracked(item)) {
            applyFreshness(item, 100, currentDay);
            return 100;
        }

        boolean enteringFreezer = Double.isInfinite(storageMultiplier);

        if (enteringFreezer) {
            // Congelador: se detiene la pudricion por completo y se marca frozen=true.
            setFrozen(item, true);
            clearThawProgress(item);
            // El watermark se mueve a "ahora" para que, si sale del frio, la pudricion
            // continue desde este punto y no desde la ultima vez a temperatura ambiente.
            writeLastCalcDay(item, currentDay);
            return getStoredFreshness(item);
        }

        if (isFrozen(item)) {
            long thawStart = readOrInitThawStartDay(item, currentDay);
            long daysThawing = currentDay - thawStart;
            if (daysThawing < THAW_DAYS) {
                // Aun descongelandose: no pierde el estado ni empieza a pudrirse todavia.
                writeLastCalcDay(item, currentDay);
                return getStoredFreshness(item);
            }
            // Termino de descongelarse: libera el flag y la pudricion sigue desde ese instante.
            setFrozen(item, false);
            clearThawProgress(item);
            writeLastCalcDay(item, thawStart + THAW_DAYS);
        }

        int stored = getStoredFreshness(item);
        long lastCalcDay = readLastCalcDay(item, currentDay);
        long elapsedDays = Math.max(0, currentDay - lastCalcDay);

        double ratePerDay = 100.0 / totalDecayDays(item.getType());
        double raw = stored - (elapsedDays * ratePerDay / storageMultiplier);
        int rounded = roundToNearestTen(Math.max(0.0, Math.min(100.0, raw)));

        applyFreshness(item, rounded, currentDay);
        return rounded;
    }

    /**
     * Redondea un valor de frescura a la decena entera mas cercana (obligatorio en todo
     * el sistema). Ej: 74 -> 70, 76 -> 80.
     */
    public int roundToNearestTen(double freshness) {
        return (int) (Math.round(freshness / 10.0) * 10);
    }

    /**
     * Determina el {@link SpoilageTier} correspondiente a un valor de frescura (en decenas).
     */
    public SpoilageTier getTier(int freshness) {
        if (freshness <= 0) {
            return SpoilageTier.PODRIDO;
        }
        if (freshness <= 30) {
            return SpoilageTier.MAL_ESTADO;
        }
        if (freshness <= 70) {
            return SpoilageTier.PASADO;
        }
        return SpoilageTier.FRESCO;
    }

    /**
     * Escribe la frescura en el PDC del item y actualiza tanto la barra de daño visual
     * ({@code minecraft:max_damage} / {@code minecraft:damage}) como una linea de Lore en
     * texto plano ("Frescura: 70% (Pasado)") — la barra sola es sutil y facil de pasar por
     * alto al testear, el Lore la hace inequivoca. Si la frescura llega a 0 solo limpia la
     * barra visual (seccion 2: "Podrido: Sin barra") — NO transforma el Material: eso es
     * responsabilidad explicita de {@link #transformToRotten}, que el llamador debe invocar
     * cuando corresponda (ver {@link #calculateFreshness}).
     */
    public void applyFreshness(ItemStack item, int freshness, long currentDay) {
        int clamped = Math.max(0, Math.min(100, roundToNearestTen(freshness)));
        boolean frozen = isFrozen(item);

        item.editMeta(meta -> {
            PersistentDataContainer pdc = meta.getPersistentDataContainer();
            pdc.set(keyFreshness, PersistentDataType.INTEGER, clamped);
            pdc.set(keyLastCalcDay, PersistentDataType.LONG, currentDay);
            meta.lore(buildFreshnessLore(clamped, frozen));
        });

        if (clamped <= 0) {
            item.unsetData(DataComponentTypes.MAX_DAMAGE);
            item.unsetData(DataComponentTypes.DAMAGE);
            return;
        }

        int damage = (DAMAGE_BAR_RESOLUTION * (100 - clamped)) / 100;
        item.setData(DataComponentTypes.MAX_DAMAGE, DAMAGE_BAR_RESOLUTION);
        item.setData(DataComponentTypes.DAMAGE, damage);
    }

    /**
     * Construye el Lore de frescura/congelado. Centralizado aca para que
     * {@link #applyFreshness} y {@link #setFrozen} (que puede cambiar el estado congelado
     * sin pasar por applyFreshness, ver la rama "enteringFreezer" de
     * {@link #calculateFreshness}) siempre muestren texto consistente.
     */
    private List<Component> buildFreshnessLore(int freshness, boolean frozen) {
        SpoilageTier tier = getTier(freshness);
        NamedTextColor color = switch (tier) {
            case FRESCO -> NamedTextColor.GREEN;
            case PASADO -> NamedTextColor.YELLOW;
            case MAL_ESTADO -> NamedTextColor.RED;
            case PODRIDO -> NamedTextColor.DARK_GRAY;
        };
        String label = switch (tier) {
            case FRESCO -> "Fresco";
            case PASADO -> "Pasado";
            case MAL_ESTADO -> "Mal Estado";
            case PODRIDO -> "Podrido";
        };

        List<Component> lore = new ArrayList<>();
        lore.add(Component.text("Frescura: " + freshness + "% (" + label + ")", color)
                .decoration(TextDecoration.ITALIC, false));
        if (frozen) {
            lore.add(Component.text("❄ Congelado", NamedTextColor.AQUA)
                    .decoration(TextDecoration.ITALIC, false));
        }
        return lore;
    }

    /**
     * Transforma un item podrido (0%) en "Restos Podridos" (compostable): {@code
     * Material.ROTTEN_FLESH}, sin barra de daño visual (seccion 2) y frescura fija en 0.
     * <p>
     * {@link ItemStack#setType} esta deprecado en Paper 1.21 (permite saltarse validaciones
     * del item) — la reemplazo recomendada es construir un {@link ItemStack} nuevo en vez
     * de mutar el tipo in-place. Por eso este metodo SIEMPRE devuelve la referencia a usar
     * de ahora en adelante: si ya era {@code ROTTEN_FLESH} devuelve el mismo objeto
     * (idempotente), si no, devuelve uno nuevo. El llamador es responsable de reemplazar
     * su copia (slot de inventario, mano del jugador, array de contenidos, etc.) con el
     * valor devuelto.
     */
    public ItemStack transformToRotten(ItemStack item) {
        if (item.getType() == Material.ROTTEN_FLESH) {
            return item;
        }

        ItemStack rotten = new ItemStack(Material.ROTTEN_FLESH, item.getAmount());
        rotten.editMeta(meta -> {
            meta.displayName(Component.text("Restos Podridos", NamedTextColor.DARK_GRAY)
                    .decoration(TextDecoration.ITALIC, false));
            meta.lore(buildFreshnessLore(0, false));
            meta.getPersistentDataContainer().set(keyFreshness, PersistentDataType.INTEGER, 0);
        });
        return rotten;
    }

    /**
     * Nutricion/saturacion vanilla del item (segun su Material), leidas directamente del
     * componente nativo {@code minecraft:food} en vez de mantener una tabla propia.
     * Devuelve {@code null} si el Material no es comestible.
     */
    public FoodProperties getBaseFoodProperties(ItemStack item) {
        return item.getData(DataComponentTypes.FOOD);
    }

    /**
     * Aplica/remueve el flag custom {@code frozen} (item congelado) en el PDC del item, y
     * refresca el Lore para reflejarlo de inmediato (ver {@link #buildFreshnessLore}) — este
     * metodo se llama a veces sin pasar por {@link #applyFreshness} (ej. al entrar a un
     * Congelador), asi que es el unico punto que puede tocar el flag {@code frozen}.
     * Un item congelado no pierde frescura (ver appliances.CatchUpProcessor) pero al
     * cocinarse/consumirse sus valores nutricionales caen al 20%.
     */
    public void setFrozen(ItemStack item, boolean frozen) {
        item.editMeta(meta -> {
            PersistentDataContainer pdc = meta.getPersistentDataContainer();
            pdc.set(keyFrozen, PersistentDataType.BOOLEAN, frozen);
            int freshness = pdc.getOrDefault(keyFreshness, PersistentDataType.INTEGER, 100);
            meta.lore(buildFreshnessLore(freshness, frozen));
        });
    }

    public boolean isFrozen(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return false;
        }
        return item.getItemMeta().getPersistentDataContainer()
                .getOrDefault(keyFrozen, PersistentDataType.BOOLEAN, false);
    }

    private void clearThawProgress(ItemStack item) {
        item.editMeta(meta -> meta.getPersistentDataContainer().remove(keyThawStartDay));
    }

    private long readOrInitThawStartDay(ItemStack item, long currentDay) {
        PersistentDataContainer pdc = item.getItemMeta().getPersistentDataContainer();
        Long existing = pdc.get(keyThawStartDay, PersistentDataType.LONG);
        if (existing != null) {
            return existing;
        }
        item.editMeta(meta -> meta.getPersistentDataContainer().set(keyThawStartDay, PersistentDataType.LONG, currentDay));
        return currentDay;
    }

    private long readLastCalcDay(ItemStack item, long fallback) {
        return item.getItemMeta().getPersistentDataContainer()
                .getOrDefault(keyLastCalcDay, PersistentDataType.LONG, fallback);
    }

    private void writeLastCalcDay(ItemStack item, long day) {
        item.editMeta(meta -> meta.getPersistentDataContainer().set(keyLastCalcDay, PersistentDataType.LONG, day));
    }

    private int totalDecayDays(Material material) {
        return BASE_DECAY_DAYS.getOrDefault(material, DEFAULT_DECAY_DAYS);
    }
}
