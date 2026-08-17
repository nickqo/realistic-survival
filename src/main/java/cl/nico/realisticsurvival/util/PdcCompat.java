package cl.nico.realisticsurvival.util;

import org.bukkit.NamespacedKey;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

/**
 * Ayuda de compatibilidad para leer campos de "dia in-game" del PDC. A mitad de desarrollo,
 * estos campos pasaron de {@code PersistentDataType.LONG} (dia entero) a
 * {@code PersistentDataType.DOUBLE} (dia con fraccion horaria, ver
 * {@code api.time.TimeProvider#getCurrentDay}). El PDC no hace ninguna coercion automatica
 * entre tipos numericos distintos: cualquier bloque/item colocado ANTES de ese cambio revienta
 * con {@code IllegalArgumentException} ("found tag instance (LongTag) cannot store Double") la
 * primera vez que algo intenta leerlo con el tipo nuevo.
 * <p>
 * {@link #readDay} migra el dato de forma transparente en la lectura: si encuentra el tag
 * viejo (LONG) lo convierte a double sin perder el valor, y la proxima escritura ya lo deja
 * en el formato nuevo. Usar SIEMPRE para leer un campo de dia in-game desde PDC en vez de
 * {@code getOrDefault} directo con {@code PersistentDataType.DOUBLE}.
 */
public final class PdcCompat {

    private PdcCompat() {
    }

    public static double readDay(PersistentDataContainer pdc, NamespacedKey key, double fallback) {
        if (pdc.has(key, PersistentDataType.DOUBLE)) {
            return pdc.get(key, PersistentDataType.DOUBLE);
        }
        if (pdc.has(key, PersistentDataType.LONG)) {
            return pdc.get(key, PersistentDataType.LONG);
        }
        return fallback;
    }

    /** Igual que {@code pdc.has(key, DOUBLE)}, pero tambien cuenta el formato viejo (LONG). */
    public static boolean hasDay(PersistentDataContainer pdc, NamespacedKey key) {
        return pdc.has(key, PersistentDataType.DOUBLE) || pdc.has(key, PersistentDataType.LONG);
    }

    /**
     * Igual que {@link #readDay}, pero para el patron "leer, null si no existe todavia"
     * ({@code pdc.get(key, TYPE)}) en vez del patron "leer con fallback".
     */
    public static Double readDayOrNull(PersistentDataContainer pdc, NamespacedKey key) {
        if (pdc.has(key, PersistentDataType.DOUBLE)) {
            return pdc.get(key, PersistentDataType.DOUBLE);
        }
        if (pdc.has(key, PersistentDataType.LONG)) {
            Long legacy = pdc.get(key, PersistentDataType.LONG);
            return legacy == null ? null : legacy.doubleValue();
        }
        return null;
    }
}
