package cl.nico.realisticsurvival.api.time;

import me.casperge.realisticseasons.RealisticSeasons;
import me.casperge.realisticseasons.api.SeasonsAPI;
import me.casperge.realisticseasons.calendar.Calendar;
import me.casperge.realisticseasons.calendar.Date;
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
 * cargado y habilitado en el servidor.
 * <p>
 * Firmas verificadas contra el jar real de RealisticSeasons (v11.12.1) via javap:
 * <ul>
 *     <li>{@code SeasonsAPI.getInstance()} / {@code getDate(World)} / {@code getAirTemperature(Location)}
 *         / {@code getTemperature(Player)}</li>
 *     <li>{@code RealisticSeasons.getInstance().getTimeManager().getCalendar()} expone el
 *         {@link Calendar} configurado (largos de mes/anio custom), cuyo
 *         {@code getTotalDays(Date)} entrega el contador de "dias in-game absolutos" que
 *         pide {@link TimeProvider#getCurrentDay}.</li>
 * </ul>
 * RS no expone un metodo de clima en su API publica: el clima (lluvia/nieve) se controla
 * internamente pero se refleja en el clima vanilla del mundo, por lo que {@code isRaining}
 * se resuelve con {@link World#hasStorm()}.
 */
public final class RSTimeHandler implements TimeProvider {

    private final SeasonsAPI seasonsApi;
    private final Calendar calendar;

    public RSTimeHandler() {
        this.seasonsApi = SeasonsAPI.getInstance();
        this.calendar = RealisticSeasons.getInstance().getTimeManager().getCalendar();
    }

    @Override
    public long getCurrentDay(World world) {
        Date date = seasonsApi.getDate(world);
        return calendar.getTotalDays(date);
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
