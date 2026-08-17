package cl.nico.realisticsurvival.api.time;

import me.casperge.realisticseasons.RealisticSeasons;
import me.casperge.realisticseasons.api.SeasonsAPI;
import me.casperge.realisticseasons.calendar.Calendar;
import me.casperge.realisticseasons.calendar.Date;
import me.casperge.realisticseasons.calendar.TimeManager;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

/**
 * Implementacion de {@link TimeProvider} que hace de puente con la API de RealisticSeasons
 * (RS). Es la UNICA clase del plugin autorizada a importar/llamar clases del paquete de RS
 * ({@code me.casperge.realisticseasons.*}).
 * <p>
 * RealisticSurvival declara a RealisticSeasons como "depend" (hard dependency) en
 * plugin.yml, por lo que al momento de construir esta clase el plugin de RS ya esta
 * cargado y habilitado en el servidor — pero "habilitado" NO implica que su
 * {@link TimeManager} ya este inicializado: RS lo arma de forma diferida (probablemente en
 * una tarea programada para el siguiente tick) y no de forma sincrona dentro de su propio
 * {@code onEnable}. Resolverlo en el constructor de esta clase produce una carrera real
 * (confirmado en produccion: {@code RealisticSeasons#getTimeManager()} devuelve null justo
 * al arrancar el servidor). Por eso {@link #calendar()} lo resuelve de forma perezosa, en
 * el primer uso real — momento en que el servidor ya lleva corriendo un buen rato (un
 * jugador tuvo que conectarse e interactuar con algo) y RS ya termino su inicializacion.
 * <p>
 * Firmas verificadas contra el jar real de RealisticSeasons (v11.12.1) via javap:
 * <ul>
 *     <li>{@code SeasonsAPI.getInstance()} / {@code getDate(World)} / {@code getAirTemperature(Location)}
 *         / {@code getTemperature(Player)}</li>
 *     <li>{@code RealisticSeasons.getInstance().getTimeManager().getCalendar()} expone el
 *         {@link Calendar} configurado (largos de mes/anio custom), cuyo
 *         {@code getTotalDays(Date)} entrega el contador de dias in-game absolutos.</li>
 *     <li>{@code SeasonsAPI.getHours/getMinutes/getSeconds(World)} entregan la hora del
 *         dia actual (asumido reloj estandar 24h/60min/60seg — best-effort, no verificable
 *         sin un cliente corriendo), combinada con {@code getTotalDays} para dar precision
 *         de hora a {@link TimeProvider#getCurrentDay}.</li>
 * </ul>
 * RS no expone un metodo de clima en su API publica: el clima (lluvia/nieve) se controla
 * internamente pero se refleja en el clima vanilla del mundo, por lo que {@code isRaining}
 * se resuelve con {@link World#hasStorm()}.
 */
public final class RSTimeHandler implements TimeProvider {

    private final SeasonsAPI seasonsApi;
    private Calendar calendar;

    public RSTimeHandler() {
        this.seasonsApi = SeasonsAPI.getInstance();
    }

    /**
     * Resuelve y cachea el {@link Calendar} de RS de forma perezosa (ver Javadoc de la
     * clase). Si RS todavia no esta listo (caso extremo: se llama antes de que cualquier
     * jugador haya interactuado con nada), falla con un mensaje claro en vez de un NPE
     * críptico.
     */
    private Calendar calendar() {
        if (calendar == null) {
            TimeManager timeManager = RealisticSeasons.getInstance().getTimeManager();
            if (timeManager == null) {
                throw new IllegalStateException(
                        "RealisticSeasons todavia no inicializo su TimeManager (puede pasar en los "
                                + "primeros instantes tras el arranque del servidor). Reintenta la accion.");
            }
            calendar = timeManager.getCalendar();
        }
        return calendar;
    }

    @Override
    public double getCurrentDay(World world) {
        Date date = seasonsApi.getDate(world);
        long wholeDays = calendar().getTotalDays(date);

        int hours = seasonsApi.getHours(world);
        int minutes = seasonsApi.getMinutes(world);
        int seconds = seasonsApi.getSeconds(world);
        double dayFraction = (hours + minutes / 60.0 + seconds / 3600.0) / 24.0;

        return wholeDays + dayFraction;
    }

    @Override
    public int getTemperature(Location location) {
        return seasonsApi.getAirTemperature(location);
    }

    @Override
    public int getTemperature(Player player) {
        return seasonsApi.getTemperature(player);
    }

    @Override
    public boolean isRaining(World world) {
        return world.hasStorm();
    }
}
