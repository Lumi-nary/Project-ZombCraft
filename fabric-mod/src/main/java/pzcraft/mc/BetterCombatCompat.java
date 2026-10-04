package pzcraft.mc;

/**
 * Better Combat is optional. Its first-person mode uses PlayerAnimationLib's separate arm/model pass;
 * EntitySceneExporter captures that pass as camera-space hand geometry. Set in memory only, without changing the owner's
 * configuration file. Vanilla hand rendering alone stays static because Better Combat replaces vanilla swing progress.
 */
final class BetterCombatCompat {
    private static boolean done;
    private static Class<?> animationState;
    private static java.lang.reflect.Method getManager, setFirstPerson, isActive, getMode, getTransition;
    private static boolean animationChecked, animationFailed;

    private static java.lang.reflect.Field armsField;
    private static Object clientConfig;
    private static boolean armsApplied, armsValue;

    private BetterCombatCompat() {}

    /** Better Combat itself shows no arms in first person, only the swinging weapon; {@code bc.arms=1} in the tuning file brings them back. */
    static void applyArms() {
        if (armsField == null || clientConfig == null) return;
        boolean want = Tuning.get("bc.arms", 0) > 0;
        if (armsApplied && want == armsValue) return;
        try { armsField.setBoolean(clientConfig, want); armsApplied = true; armsValue = want; }
        catch (ReflectiveOperationException e) { armsField = null; }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    static void tick() {
        if (done) return;
        try {
            Class<?> mod = Class.forName("net.bettercombat.client.BetterCombatClientMod");
            Object config = mod.getField("config").get(null);
            if (config == null) return;                       // not loaded yet: try again next frame
            Class<?> tri = Class.forName("net.bettercombat.config.TriStateAuto");
            var field = config.getClass().getField("firstPersonAnimations");
            field.set(config, Enum.valueOf((Class) tri, "YES"));
            armsField = config.getClass().getField("isShowingArmsInFirstPerson");
            clientConfig = config;
            applyArms();
            installRangeExtension();
            done = true;
            PzCraftClient.LOG.info("Better Combat: first-person animations enabled for native arm capture");
        } catch (ClassNotFoundException e) {
            done = true;                                      // no Better Combat: nothing to do
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            done = true;
            PzCraftClient.LOG.warn("Better Combat first-person setting could not be changed: {}", e.toString());
        }
    }

    /** Use Better Combat's extension API instead of changing its shared presets (vanilla weapons retain their range). */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void installRangeExtension() throws ReflectiveOperationException {
        Class<?> api = Class.forName("net.bettercombat.api.client.AttackRangeExtensions");
        Class<?> context = Class.forName("net.bettercombat.api.client.AttackRangeExtensions$Context");
        Class<?> modifier = Class.forName("net.bettercombat.api.client.AttackRangeExtensions$Modifier");
        Class<?> operation = Class.forName("net.bettercombat.api.client.AttackRangeExtensions$Operation");
        Object add = Enum.valueOf((Class) operation, "ADD");
        var constructor = modifier.getConstructor(double.class, operation);
        var getPlayer = context.getMethod("player");
        var getRange = context.getMethod("attackRange");
        Object neutral = constructor.newInstance(0.0, add);
        java.util.function.Function<Object, Object> extension = ctx -> {
            try {
                var player = (net.minecraft.world.entity.player.Player) getPlayer.invoke(ctx);
                var weapon = PzWeapons.heldMelee(player);
                if (weapon == null) return neutral;
                double range = pzcraft.protocol.MeleeReach.blocks(weapon.get("maxRange").getAsDouble());
                return constructor.newInstance(range - ((Number) getRange.invoke(ctx)).doubleValue(), add);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Could not resolve native weapon range", e);
            }
        };
        api.getMethod("register", java.util.function.Function.class).invoke(null, extension);
    }

    /** Mark a freshly extracted render state for the library's first-person arm/item pass, when it is active. */
    static boolean beginFirstPerson(net.minecraft.client.renderer.entity.state.EntityRenderState state) {
        if (animationFailed) return false;
        try {
            if (!animationChecked) {
                animationChecked = true;
                animationState = Class.forName("com.zigythebird.playeranim.accessors.IAvatarAnimationState");
                Class<?> manager = Class.forName("com.zigythebird.playeranim.animation.AvatarAnimManager");
                getManager = animationState.getMethod("playerAnimLib$getAnimManager");
                setFirstPerson = animationState.getMethod("playerAnimLib$setFirstPersonPass", boolean.class);
                isActive = manager.getMethod("isActive");
                getMode = manager.getMethod("getFirstPersonMode");
                getTransition = manager.getMethod("getFirstPersonTransitionProgress");
            }
            if (!animationState.isInstance(state)) return false;
            Object manager = getManager.invoke(state);
            if (manager == null || !Boolean.TRUE.equals(isActive.invoke(manager))
                    || !"THIRD_PERSON_MODEL".equals(String.valueOf(getMode.invoke(manager)))
                    || ((Number) getTransition.invoke(manager)).floatValue() <= 0) return false;
            setFirstPerson.invoke(state, true);
            return true;
        } catch (ClassNotFoundException e) {
            animationFailed = true;
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            animationFailed = true;
            PzCraftClient.LOG.warn("Native first-person animation bridge unavailable: {}", e.toString());
        }
        return false;
    }

    static void endFirstPerson(net.minecraft.client.renderer.entity.state.EntityRenderState state) {
        try {
            setFirstPerson.invoke(state, false);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Could not restore animation render state", e);
        }
    }
}

