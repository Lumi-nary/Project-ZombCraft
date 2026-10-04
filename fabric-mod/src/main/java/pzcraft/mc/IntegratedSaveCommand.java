package pzcraft.mc;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

/**
 * {@code /save-all [flush]} on the integrated server too (vanilla registers it for dedicated servers only), so the
 * Minecraft world (blocks, Steve's inventory) can be written out before PZ or a tool restarts the hidden Minecraft.
 * Without it the world was only saved by the autosave and when PZ paused.
 */
final class IntegratedSaveCommand {
    private IntegratedSaveCommand() {}

    static void init() {
        CommandRegistrationCallback.EVENT.register((dispatcher, context, selection) -> {
            if (selection == Commands.CommandSelection.DEDICATED) return; // vanilla has its own there
            dispatcher.register(Commands.literal("save-all").requires(Commands.hasPermission(Commands.LEVEL_OWNERS))
                    .executes(c -> save(c.getSource(), false))
                    .then(Commands.literal("flush").executes(c -> save(c.getSource(), true))));
        });
    }

    private static int save(CommandSourceStack source, boolean flush) {
        source.sendSuccess(() -> Component.translatable("commands.save.saving"), false);
        boolean saved = source.getServer().saveEverything(true, flush, true);
        source.sendSuccess(() -> Component.translatable(saved ? "commands.save.success" : "commands.save.failed"), true);
        PzCraftClient.LOG.info("Minecraft world saved (save-all{}): {}", flush ? " flush" : "", saved);
        return saved ? 1 : 0;
    }
}

