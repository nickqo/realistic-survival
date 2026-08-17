package cl.nico.realisticsurvival.api.time;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

/**
 * Abstraccion del "reloj" del servidor.
 * <p>
 * Ninguna otra clase del plugin debe hablar directamente con la API de RealisticSeasons:
 * todo el mundo le pide la hora/temperatura/clima a esta interfaz. La unica implementacion
 * real es {@link RSTimeHandler}, que hace de puente con RealisticSeasons. Esto permite:
 * <ul>
 *     <li>Desacoplar el resto del codigo (food, appliances, farming) de la API externa.</li>
 *     <li>Facilitar tests/mocking sin depender del plugin externo.</li>
 * </ul>
 */
public interface TimeProvider {

    /**
     * Dia in-game absoluto del mundo indicado. Es la unidad base de tiempo de todo el
     * plugin: la pudricion de alimentos, el hielo de los electrodomesticos y la humedad
     * del farmland se calculan siempre como diferencia entre dos "dias absolutos".
     *
     * @param world mundo a consultar
     * @return dia in-game absoluto (nunca negativo, monotonamente creciente)
     */
    long getCurrentDay(World world);

    /**
     * Temperatura ambiente (en grados) de una ubicacion, segun RealisticSeasons
     * (bioma + estacion + clima). Usada por farming (evaporacion / congelamiento de
     * farmland) y por food (velocidad de descomposicion fuera de un electrodomestico).
     *
     * @param location ubicacion a consultar
     * @return temperatura en grados Celsius
     */
    int getTemperature(Location location);

    /**
     * Temperatura percibida por un jugador (incluye equipo, efectos, bioma, etc. segun RS).
     *
     * @param player jugador a consultar
     * @return temperatura en grados Celsius
     */
    int getTemperature(Player player);

    /**
     * Indica si actualmente esta lloviendo/nevando en el mundo (usado para rehidratar
     * farmland expuesto al cielo).
     *
     * @param world mundo a consultar
     * @return true si hay precipitacion activa
     */
    boolean isRaining(World world);
}
