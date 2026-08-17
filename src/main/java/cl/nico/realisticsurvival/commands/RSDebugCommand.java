package cl.nico.realisticsurvival.commands;

import cl.nico.realisticsurvival.api.time.TimeProvider;
import cl.nico.realisticsurvival.food.FoodManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Locale;

/**
 * Comando administrativo {@code /rsdebug}: herramientas para probar el sistema de
 * frescura sin depender de que pasen dias in-game reales (RS los mide segun su propio
 * calendario, que puede avanzar mucho mas lento que tiempo real — ver ARCHITECTURE.md).
 * Responsabilidad unica: parsear argumentos y delegar en {@link FoodManager} /
 * {@link TimeProvider} — no contiene logica de descomposicion propia (SRP).
 * <p>
 * Uso:
 * <ul>
 *     <li>{@code /rsdebug setfreshness <0-100>} — fuerza la frescura del item en mano.</li>
 *     <li>{@code /rsdebug setfrozen <true|false>} — fuerza el flag congelado del item en mano.</li>
 *     <li>{@code /rsdebug currentday} — muestra el dia in-game absoluto actual (segun RS),
 *         para verificar si el calendario esta avanzando durante una sesion de prueba.</li>
 *     <li>{@code /rsdebug lore <true|false>} — activa/desactiva (global, todo el servidor)
 *         una linea extra de Lore con el valor "crudo" (antes de redondear a la decena) de
 *         la ultima operacion que toco cada item — util para verificar un merge sospechoso
 *         sin adivinar si el redondeo esta ocultando algo.</li>
 * </ul>
 */
public final class RSDebugCommand implements CommandExecutor, TabCompleter {

    private final FoodManager foodManager;
    private final TimeProvider timeProvider;

    public RSDebugCommand(FoodManager foodManager, TimeProvider timeProvider) {
        this.foodManager = foodManager;
        this.timeProvider = timeProvider;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length < 1) {
            sender.sendMessage(usage());
            return true;
        }

        if (args[0].equalsIgnoreCase("currentday")) {
            currentDay(sender);
            return true;
        }

        if (args[0].equalsIgnoreCase("lore")) {
            toggleDebugLore(sender, args);
            return true;
        }

        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Este subcomando solo lo puede usar un jugador (opera sobre el item en su mano).", NamedTextColor.RED));
            return true;
        }

        if (args.length < 2) {
            sender.sendMessage(usage());
            return true;
        }

        ItemStack inHand = player.getInventory().getItemInMainHand();
        if (!foodManager.isTrackable(inHand) && !foodManager.isTracked(inHand)) {
            sender.sendMessage(Component.text("El item en tu mano no es un alimento gestionado por el plugin.", NamedTextColor.RED));
            return true;
        }
        if (inHand.getType() == Material.ROTTEN_FLESH) {
            // Podrido (0%) es un estado terminal: no tiene sentido "resetear" su frescura
            // o su flag congelado — para probar un alimento fresco de nuevo, usa otro item.
            sender.sendMessage(Component.text("Restos Podridos es un estado terminal, no se puede modificar.", NamedTextColor.RED));
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "setfreshness" -> setFreshness(sender, player, inHand, args[1]);
            case "setfrozen" -> setFrozen(sender, player, inHand, args[1]);
            default -> sender.sendMessage(Component.text("Subcomando desconocido: " + args[0], NamedTextColor.RED));
        }
        return true;
    }

    private Component usage() {
        return Component.text(
                "Uso: /rsdebug setfreshness <0-100> | setfrozen <true|false> | currentday | lore <true|false>",
                NamedTextColor.YELLOW);
    }

    /**
     * Activa/desactiva globalmente la linea de debug ("[debug] raw: 74.93%") en el Lore de
     * todos los alimentos gestionados por el plugin. Es un toggle de servidor, no por
     * jugador — items ya calculados antes de activar el toggle no se actualizan
     * retroactivamente hasta la proxima vez que se recalculen (consumir, click, merge, etc).
     */
    private void toggleDebugLore(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(Component.text("Uso: /rsdebug lore <true|false>", NamedTextColor.YELLOW));
            return;
        }
        boolean enabled = Boolean.parseBoolean(args[1]);
        foodManager.setDebugLoreEnabled(enabled);
        sender.sendMessage(Component.text(
                "Lore de debug (valor crudo antes de redondear): " + enabled
                        + ". Los items ya calculados se actualizan recien en su proxima interaccion.",
                NamedTextColor.GREEN));
    }

    /**
     * Muestra el dia in-game absoluto actual (mundo del jugador, o el primer mundo del
     * servidor si se ejecuta desde consola). Util para confirmar si el calendario de RS
     * esta avanzando durante una sesion de testing corta, o si hay que usar sus propios
     * comandos para adelantarlo.
     */
    private void currentDay(CommandSender sender) {
        World world = sender instanceof Player player ? player.getWorld() : Bukkit.getWorlds().get(0);
        double day = timeProvider.getCurrentDay(world);
        // 2 decimales: alcanza para notar el avance entre dos ejecuciones separadas por
        // unos minutos reales, y confirmar que el reloj efectivamente esta corriendo.
        sender.sendMessage(Component.text(
                String.format(Locale.ROOT, "Dia in-game absoluto actual (%s): %.2f", world.getName(), day),
                NamedTextColor.AQUA));
    }

    private void setFreshness(CommandSender sender, Player player, ItemStack item, String rawValue) {
        int value;
        try {
            value = Integer.parseInt(rawValue);
        } catch (NumberFormatException e) {
            sender.sendMessage(Component.text("Valor invalido: " + rawValue, NamedTextColor.RED));
            return;
        }

        // Se usa el dia actual real (via TimeProvider) para el watermark, no un valor
        // arbitrario: asi la pudricion sigue avanzando con normalidad desde "ahora" en
        // vez de saltar a 0% en la proxima interaccion por un timestamp viejo artificial.
        double currentDay = timeProvider.getCurrentDay(player.getWorld());
        foodManager.applyFreshness(item, value, currentDay);

        if (foodManager.getTier(value) == FoodManager.SpoilageTier.PODRIDO) {
            player.getInventory().setItemInMainHand(foodManager.transformToRotten(item));
        }

        sender.sendMessage(Component.text("Frescura del item en mano seteada a " + value + "%.", NamedTextColor.GREEN));
    }

    private void setFrozen(CommandSender sender, Player player, ItemStack item, String rawValue) {
        boolean frozen = Boolean.parseBoolean(rawValue);
        foodManager.setFrozen(item, frozen);
        sender.sendMessage(Component.text("Estado congelado del item en mano: " + frozen, NamedTextColor.GREEN));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 1) {
            return List.of("setfreshness", "setfrozen", "currentday", "lore");
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("setfreshness")) {
            return List.of("0", "10", "20", "30", "40", "50", "60", "70", "80", "90", "100");
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("setfrozen") || args[0].equalsIgnoreCase("lore"))) {
            return List.of("true", "false");
        }
        return List.of();
    }
}
