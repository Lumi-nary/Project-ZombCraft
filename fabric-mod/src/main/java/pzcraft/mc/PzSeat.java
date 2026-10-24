package pzcraft.mc;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.vehicle.DismountHelper;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.PushReaction;
//#if MC_1_20
//$ import net.minecraft.nbt.CompoundTag;
//#else
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
//#endif
import net.minecraft.world.phys.Vec3;
import pzcraft.protocol.WorldTargets;

/**
 * What Steve sits on when he sits on a PZ chair, couch or bench (W4): an invisible, unsaved point at the seat's surface
 * (a player rides 0.6 below their seat point, so he sits right on it). Its type must be serializable: Minecraft's server
 * refuses to let anyone ride an entity whose type cannot be saved (Entity.startRiding), which is why sitting never worked
 * (B21); it is kept out of the world and the player's save by {@link #shouldBeSaved} and {@link #saveAsPassenger} instead. It goes when nobody sits on it, or when PZ no
 * longer reports its seat (mined, broken); standing up (sneak) puts him in front of it, where PZ's floor is free.
 * In a car (W5, {@link PzCars}) it follows the PZ player's car seat every tick; getting off it tells PZ to get out.
 */
public final class PzSeat extends Entity {
    public static EntityType<PzSeat> TYPE;

    /** The PZ seat this stands for (server only). */
    private WorldTargets.Target seat;
    private int ticks;
    /** A car seat (W5), and whether PZ let him go already (so getting off does not ask PZ again). */
    private boolean car, released;

    public PzSeat(EntityType<?> type, Level level) {
        super(type, level);
        this.noPhysics = true;
    }

    /** Main entrypoint, before registries freeze. */
    static void register() {
        ResourceKey<EntityType<?>> key = ResourceKey.create(Registries.ENTITY_TYPE, Identifier.fromNamespaceAndPath(PzBlocks.NAMESPACE, "seat"));
        //#if MC_1_20
        //$ TYPE = net.minecraft.core.Registry.register(BuiltInRegistries.ENTITY_TYPE, key,
        //$         EntityType.Builder.<PzSeat>of(PzSeat::new, MobCategory.MISC).sized(0f, 0f).noSummon()
        //$                 .clientTrackingRange(10).updateInterval(1).build(key.location().toString())); // every tick: a car seat moves fast
        //#else
        TYPE = net.minecraft.core.Registry.register(BuiltInRegistries.ENTITY_TYPE, key,
                EntityType.Builder.<PzSeat>of(PzSeat::new, MobCategory.MISC).sized(0f, 0f).noSummon().noLootTable()
                        .clientTrackingRange(10).updateInterval(1).build(key)); // every tick: a car seat moves fast
        //#endif
    }

    void seat(WorldTargets.Target t) { this.seat = t; }

    void car() { this.car = true; }
    boolean isCar() { return car; }
    void letGo() { this.released = true; }

    @Override
    public void tick() {
        if (level().isClientSide()) return;
        if (getPassengers().isEmpty()) {
            if (car && !released) PzCars.requestExit(); // do not let the old PZ seat report mount him again before acknowledgement
            discard();
            return;
        }
        if (seat != null && ++ticks % 20 == 0 && PzTargets.fresh()
                && PzTargets.find(seat.kind(), seat.x(), seat.y(), seat.level(), seat.key()) == null) {
            ejectPassengers();
            discard();
        }
    }

    /** In front of the seat (the way the sitter faces), on PZ's floor; else wherever vanilla finds room. */
    @Override
    public Vec3 getDismountLocationForPassenger(LivingEntity passenger) {
        if (seat != null) {
            Direction front = Direction.fromYRot(getYRot());
            double floor = PzTargets.floorY(seat.level());
            BlockPos ahead = BlockPos.containing(seat.x() + .5 + front.getStepX(), floor + .01, seat.y() + .5 + front.getStepZ());
            Vec3 safe = DismountHelper.findSafeDismountLocation(passenger.getType(), level(), ahead, true);
            if (safe != null) return safe;
        }
        return super.getDismountLocationForPassenger(passenger);
    }

    @Override
    protected boolean canAddPassenger(Entity passenger) { return getPassengers().isEmpty(); }

    /** Never written to the world: it belongs to one sitting (its PZ seat is not saved with it). */
    @Override
    public boolean shouldBeSaved() { return false; }

    /** Nor with the player who sits on it (ServerPlayer saves its root vehicle): he loads standing. */
    //#if MC_1_20
    //$ @Override
    //$ public boolean saveAsPassenger(CompoundTag output) { return false; }
    //$
    //$ @Override
    //$ protected void defineSynchedData() {}
    //$
    //$ @Override
    //$ protected void readAdditionalSaveData(CompoundTag input) {}
    //$
    //$ @Override
    //$ protected void addAdditionalSaveData(CompoundTag output) {}
    //#else
    @Override
    public boolean saveAsPassenger(ValueOutput output) { return false; }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder entityData) {}

    @Override
    protected void readAdditionalSaveData(ValueInput input) {}

    @Override
    protected void addAdditionalSaveData(ValueOutput output) {}
    //#endif

    @Override
    //#if MC_26_1
    //$ public PushReaction getPistonPushReaction() { return PushReaction.IGNORE; }
    //#else
    public PushReaction getPistonPushReaction() { return PushReaction.IGNORE_ENTITY; }
    //#endif

    @Override
    public boolean isIgnoringBlockTriggers() { return true; }

    //#if MC_1_20
    //$ @Override
    //$ public boolean hurt(DamageSource source, float damage) { return false; }
    //#else
    @Override
    public boolean hurtServer(ServerLevel level, DamageSource source, float damage) { return false; }
    //#endif
}
