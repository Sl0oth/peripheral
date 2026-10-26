package slooth.peripheral.script;

import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Input;
import slooth.peripheral.NavigatorInput;
import slooth.peripheral.PeripheralNavigator;
import slooth.peripheral.api.LocalApiSecurity;

/** Per-action ownership prevents one script stopping another script's current controls. Client thread only. */
public final class ScriptActions {
    private record Value<T>(String owner,T value) {}
    private static Value<Float> forward, strafe;
    private static Value<Boolean> sneak,sprint,jump;
    private static String navOwner;
    public static void movement(String owner, float f, float s) { forward=new Value<>(owner,Math.clamp(f,-1,1)); strafe=new Value<>(owner,Math.clamp(s,-1,1)); apply(); }
    public static void sneak(String owner, boolean on) { sneak=new Value<>(owner,on); apply(); }
    public static void sprint(String owner, boolean on) { sprint=new Value<>(owner,on); apply(); }
    public static void jump(String owner) { jump=new Value<>(owner,true); apply(); }
    public static void navigate(String owner, int x,int y,int z,int tolerance) { navOwner=owner; PeripheralNavigator.INSTANCE.navigateTo(x,y,z,tolerance); }
    public static boolean stopNavigation(String owner) { if (owner.equals(navOwner)) { navOwner=null; PeripheralNavigator.INSTANCE.stop(); return true; } return false; }
    public static void release(String owner) {
        if (forward!=null && forward.owner.equals(owner)) forward=null;
        if (strafe!=null && strafe.owner.equals(owner)) strafe=null;
        if (sneak!=null && sneak.owner.equals(owner)) sneak=null;
        if (sprint!=null && sprint.owner.equals(owner)) sprint=null;
        if (jump!=null && jump.owner.equals(owner)) jump=null;
        stopNavigation(owner); apply();
    }
    public static void clear() { forward=null; strafe=null; sneak=null; sprint=null; jump=null; navOwner=null; PeripheralNavigator.INSTANCE.stop(); apply(); }
    public static void tick() { if (jump!=null) jump=null; }
    private static void apply() {
        var c=Minecraft.getInstance(); if (c.player==null) return;
        if (!(c.player.input instanceof NavigatorInput)) c.player.input=new NavigatorInput(c.player.input);
        NavigatorInput n=(NavigatorInput)c.player.input;
        n.scriptForward=forward==null?0:forward.value; n.scriptStrafe=strafe==null?0:strafe.value;
        n.scriptSneak=sneak!=null && sneak.value; n.scriptSprint=sprint!=null && sprint.value; n.scriptJump=jump!=null && jump.value;
        n.scriptOverriding=n.scriptForward!=0 || n.scriptStrafe!=0 || n.scriptSneak || n.scriptJump || n.scriptSprint;
        if (sprint==null) c.player.setSprinting(c.options.keySprint.isDown());
    }
}
