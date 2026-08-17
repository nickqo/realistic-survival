package cl.nico.realisticsurvival.commands;

import cl.nico.realisticsurvival.appliances.ApplianceManager;
import cl.nico.realisticsurvival.appliances.ApplianceManager.ApplianceType;
import cl.nico.realisticsurvival.farming.WateringManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Comando administrativo {@code /rsgive}: entrega los items custom del plugin
 * (electrodomesticos y herramientas de riego), ya que todavia no existen recetas de
 * crafteo para ellos. Responsabilidad unica: parsear argumentos y entregar el item — la
 * creacion real de cada {@link ItemStack} vive en {@link ApplianceManager} /
 * {@link WateringManager} (SRP); este comando no sabe nada de CustomModelData ni PDC.
 * <p>
 * Uso: {@code /rsgive <fridge|freezer|wateringcan|sprinkler> [jugador]}
 */
public final class RSGiveCommand implements CommandExecutor, TabCompleter {

    private enum GiveType {
        FRIDGE, FREEZER, WATERINGCAN, SPRINKLER
    }

    private final ApplianceManager applianceManager;
    private final WateringManager wateringManager;

    public RSGiveCommand(ApplianceManager applianceManager, WateringManager wateringManager) {
        this.applianceManager = applianceManager;
        this.wateringManager = wateringManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length < 1) {
            sender.sendMessage(usageMessage());
            return true;
        }

        GiveType type = parseType(args[0]);
        if (type == null) {
            sender.sendMessage(Component.text("Tipo desconocido: " + args[0], NamedTextColor.RED));
            sender.sendMessage(usageMessage());
            return true;
        }

        Player target = resolveTarget(sender, args);
        if (target == null) {
            return true;
        }

        ItemStack item = createItem(type);
        target.getInventory().addItem(item).values()
                .forEach(overflow -> target.getWorld().dropItemNaturally(target.getLocation(), overflow));

        sender.sendMessage(Component.text(
                "Entregado " + type.name().toLowerCase(Locale.ROOT) + " a " + target.getName() + ".",
                NamedTextColor.GREEN));
        return true;
    }

    private Player resolveTarget(CommandSender sender, String[] args) {
        if (args.length >= 2) {
            Player player = Bukkit.getPlayerExact(args[1]);
            if (player == null) {
                sender.sendMessage(Component.text("Jugador no encontrado o desconectado: " + args[1], NamedTextColor.RED));
            }
            return player;
        }
        if (sender instanceof Player player) {
            return player;
        }
        sender.sendMessage(Component.text("Desde la consola debes indicar un jugador: /rsgive <tipo> <jugador>", NamedTextColor.RED));
        return null;
    }

    private ItemStack createItem(GiveType type) {
        return switch (type) {
            case FRIDGE -> applianceManager.createApplianceItem(ApplianceType.FRIDGE);
            case FREEZER -> applianceManager.createApplianceItem(ApplianceType.FREEZER);
            case WATERINGCAN -> wateringManager.createWateringCan();
            case SPRINKLER -> wateringManager.createSprinklerItem();
        };
    }

    private GiveType parseType(String raw) {
        try {
            return GiveType.valueOf(raw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private Component usageMessage() {
        return Component.text("Uso: /rsgive <fridge|freezer|wateringcan|sprinkler> [jugador]", NamedTextColor.YELLOW);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            return Arrays.stream(GiveType.values())
                    .map(giveType -> giveType.name().toLowerCase(Locale.ROOT))
                    .filter(name -> name.startsWith(prefix))
                    .collect(Collectors.toList());
        }
        if (args.length == 2) {
            String prefix = args[1].toLowerCase(Locale.ROOT);
            return Bukkit.getOnlinePlayers().stream()
                    .map(Player::getName)
                    .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(prefix))
                    .collect(Collectors.toList());
        }
        return List.of();
    }
}
