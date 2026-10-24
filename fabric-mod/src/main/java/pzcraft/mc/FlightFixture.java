package pzcraft.mc;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
/** Local control-API flight fixture. It temporarily borrows two slots and restores their exact stacks. */
final class FlightFixture {
    private record Saved(java.util.UUID uuid, GameType mode, ItemStack chest, ItemStack selected,int slot,
                         double x,double y,double z,boolean flying,float speed,boolean invulnerable,
                         int food,float saturation,float exhaustion,int foodTimer) {}
    private static Saved saved;
    static boolean command(ServerPlayer p,String line) {
        if(!line.startsWith("pzcraft_test_flight "))return false;
        String[] a=line.split("\\s+");
        if(a.length>=2 && a[1].equals("end")) {
            if(saved!=null && saved.uuid.equals(p.getUUID())) {
                p.stopFallFlying();p.setGameMode(saved.mode);p.setItemSlot(EquipmentSlot.CHEST,saved.chest);
                p.getInventory().setItem(saved.slot,saved.selected);p.getInventory().setSelectedSlot(saved.slot);
                p.getAbilities().flying=saved.flying;p.getAbilities().setFlyingSpeed(saved.speed);
                p.getAbilities().invulnerable=saved.invulnerable;p.onUpdateAbilities();
                p.getFoodData().setFoodLevel(saved.food);p.getFoodData().setSaturation(saved.saturation);
                foodField(p,"exhaustionLevel",saved.exhaustion);foodField(p,"tickTimer",saved.foodTimer);
                p.connection.teleport(saved.x,saved.y,saved.z,p.getYRot(),p.getXRot());
                p.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);saved=null;
            }
        } else if(a.length>=3 && a[1].equals("begin") && saved==null) {
            int slot=p.getInventory().getSelectedSlot();
            saved=new Saved(p.getUUID(),p.gameMode.getGameModeForPlayer(),p.getItemBySlot(EquipmentSlot.CHEST).copy(),
                    p.getInventory().getItem(slot).copy(),slot,p.getX(),p.getY(),p.getZ(),p.getAbilities().flying,
                    p.getAbilities().getFlyingSpeed(),p.getAbilities().invulnerable,
                    p.getFoodData().getFoodLevel(),p.getFoodData().getSaturationLevel(),
                    foodField(p,"exhaustionLevel",null).floatValue(),foodField(p,"tickTimer",null).intValue());
            boolean elytra=a[2].equals("elytra");
            p.setGameMode(elytra?GameType.SURVIVAL:GameType.CREATIVE);
            p.getAbilities().invulnerable=true;p.getAbilities().flying=!elytra;
            if(!elytra)p.getAbilities().setFlyingSpeed(a.length>3?Float.parseFloat(a[3]):.075f);
            else {p.setItemSlot(EquipmentSlot.CHEST,new ItemStack(Items.ELYTRA));p.getInventory().setItem(slot,new ItemStack(Items.FIREWORK_ROCKET,64));}
            p.onUpdateAbilities();p.connection.teleport(p.getX(),45,p.getZ(),p.getYRot(),p.getXRot());
            p.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
            if(elytra)p.startFallFlying();
        }
        return true;
    }
    private static Number foodField(ServerPlayer p,String name,Number value) {
        try {
            var f=net.minecraft.world.food.FoodData.class.getDeclaredField(name);f.setAccessible(true);
            if(value!=null)f.set(p.getFoodData(),value);
            return (Number)f.get(p.getFoodData());
        }catch(ReflectiveOperationException e){throw new IllegalStateException("Cannot restore flight fixture food",e);}
    }
}
