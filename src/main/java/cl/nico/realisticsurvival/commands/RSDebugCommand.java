package cl.nico.realisticsurvival.commands;

import cl.nico.realisticsurvival.api.time.TimeProvider;
import cl.nico.realisticsurvival.food.FoodManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Locale;

/**
 * Comando administrativo {@code /rsdebug}: fuerza el estado de frescura/congelado del
 * item en la mano del jugador, sin depender de que pasen dias in-game reales (RS los
 * mide segun su propio calendario, que suele avanzar mucho mas lento que tiempo real —
 * ver ARCHITECTURE.md). Pensado exclusivamente para probar la parte VISUAL/de gameplay
 * (barra de daño, Lore, penalizaciones al comer) de forma inmediata. Responsabilidad
 * unica: parsear argumentos y delegar en {@link FoodManager} — no contiene logica de
 * descomposicion propia (SRP).
 * <p>
 * Uso: {@code /rsdebug setfreshness <0-100>} / {@code /rsdebug setfrozen <true|false>}
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
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Solo un jugador puede usar este comando (opera sobre el item en su mano).", NamedTextColor.RED));
            return true;
        }

        if (args.length < 2) {
            sender.sendMessage(Component.text("Uso: /rsdebug setfreshness <0-100> | /rsdebug setfrozen <true|false>", NamedTextColor.YELLOW));
            return true;
        }

        ItemStack inHand = player.getInventory().getItemInMainHand();
        if (!foodManager.isTrackable(inHand) && !foodManager.isTracked(inHand)) {
            sender.sendMessage(Component.text("El item en tu mano no es un alimento gestionado por el plugin.", NamedTextColor.RED));
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "setfreshness" -> setFreshness(sender, player, inHand, args[1]);
            case "setfrozen" -> setFrozen(sender, player, inHand, args[1]);
            default -> sender.sendMessage(Component.text("Subcomando desconocido: " + args[0], NamedTextColor.RED));
        }
        return true;
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
        long currentDay = timeProvider.getCurrentDay(player.getWorld());
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
            return List.of("setfreshness", "setfrozen");
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("setfreshness")) {
            return List.of("0", "10", "20", "30", "40", "50", "60", "70", "80", "90", "100");
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("setfrozen")) {
            return List.of("true", "false");
        }
        return List.of();
    }
}
