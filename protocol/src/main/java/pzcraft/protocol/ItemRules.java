package pzcraft.protocol;

/** Rules both games must agree on about PZ items. */
public final class ItemRules {
    private ItemRules() {}

    /**
     * Item kinds (a script's type without its module, lower case: {@code weapon}, {@code clothing}...) that never stack.
     * Each carries state of its own: condition, visuals, contents, parts. PZ saves such an item whole when it goes into
     * Minecraft and loads it again when it comes back, so nothing about it is lost on the way.
     */
    public static boolean single(String kind) {
        return switch (kind) {
            case "weapon", "clothing", "container", "drainable", "key", "map", "moveable", "radio" -> true;
            default -> kind.contains("clock");
        };
    }
}
