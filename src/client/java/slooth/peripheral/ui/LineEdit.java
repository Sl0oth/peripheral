package slooth.peripheral.ui;

import dev.pane.ui.UiCanvas;
import java.awt.Font;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import org.lwjgl.glfw.GLFW;

/**
 * Minimal single-line text model painted through UiCanvas. Used where a field must own focus
 * without a click (the rename sheet) or must live in a script's GUI coordinate space
 * (ScriptableScreen inputs), which PaneLib's layout-space text fields cannot do.
 * The caret is solid rather than blinking, so an idle field never schedules repaints.
 * Caret hit-testing reuses the positions measured by the last paint.
 */
final class LineEdit {
    private volatile String value;
    private final int maxLength;
    private int cursor, anchor;
    private float[] carets=new float[0];

    LineEdit(String initial,int maxLength) {
        this.maxLength=Math.max(1,maxLength);
        value=truncate(clean(initial==null?"":initial),this.maxLength);
        cursor=anchor=value.length();
    }

    String value(){return value;}
    void set(String next){value=truncate(clean(next==null?"":next),maxLength);cursor=anchor=value.length();}
    void selectAll(){anchor=0;cursor=value.length();}

    /** Editing keys. Returns true when the key was consumed. Enter and Escape are left to the caller. */
    boolean key(KeyEvent e) {
        int key=e.key(),mods=e.modifiers();
        boolean shortcut=(mods&(GLFW.GLFW_MOD_CONTROL|GLFW.GLFW_MOD_SUPER))!=0,extend=(mods&GLFW.GLFW_MOD_SHIFT)!=0;
        int len=value.length();cursor=clamp(cursor,len);anchor=clamp(anchor,len);
        if(shortcut&&key==GLFW.GLFW_KEY_A){selectAll();return true;}
        if(shortcut&&key==GLFW.GLFW_KEY_C){copy();return true;}
        if(shortcut&&key==GLFW.GLFW_KEY_X){copy();replace("");return true;}
        if(shortcut&&key==GLFW.GLFW_KEY_V){replace(Minecraft.getInstance().keyboardHandler.getClipboard());return true;}
        switch(key){
            case GLFW.GLFW_KEY_LEFT -> {move(previous(cursor),extend);return true;}
            case GLFW.GLFW_KEY_RIGHT -> {move(next(cursor),extend);return true;}
            case GLFW.GLFW_KEY_HOME -> {move(0,extend);return true;}
            case GLFW.GLFW_KEY_END -> {move(len,extend);return true;}
            case GLFW.GLFW_KEY_BACKSPACE -> {if(cursor==anchor&&cursor>0)anchor=previous(cursor);replace("");return true;}
            case GLFW.GLFW_KEY_DELETE -> {if(cursor==anchor&&cursor<len)anchor=next(cursor);replace("");return true;}
            default -> {return false;}
        }
    }

    boolean type(CharacterEvent e) {
        if(!e.isAllowedChatCharacter())return false;
        replace(e.codepointAsString());
        return true;
    }

    /** Places the caret from a pointer x in the same coordinate space the field was painted in. */
    void click(float x,boolean extend) {
        int best=value.length();float bestD=Float.MAX_VALUE;
        for(int i=0;i<carets.length&&i<=value.length();i++){if(i>0&&i<value.length()&&Character.isLowSurrogate(value.charAt(i))&&Character.isHighSurrogate(value.charAt(i-1)))continue;float d=Math.abs(carets[i]-x);if(d<bestD){bestD=d;best=i;}}
        cursor=best;if(!extend)anchor=best;
    }

    /** Paints the field contents (not its box) clipped to the given rectangle, keeping the caret visible. */
    void paint(UiCanvas c,float x,float y,float w,float h,float size,String family,int text,int muted,int accent,
               String placeholder,boolean focused) {
        String v=value;int len=v.length();
        int cur=clamp(cursor,len),anc=clamp(anchor,len);
        float pad=Math.max(3,Math.min(8,h*.3F)),inner=Math.max(1,w-pad*2);
        float caretX=c.textWidth(v.substring(0,cur),size,Font.PLAIN,family);
        float offset=Math.max(0,caretX-inner+2);
        float tx=x+pad-offset,ty=y+(h-size*1.2F)/2;
        float[] next=new float[len+1];
        for(int i=0;i<=len;i++)next[i]=tx+c.textWidth(v.substring(0,i),size,Font.PLAIN,family);
        carets=next;
        c.withClip(x+pad-1,y+1,inner+2,Math.max(1,h-2),()->{
            if(v.isEmpty()){
                if(placeholder!=null&&!placeholder.isEmpty()&&!focused)c.text(placeholder,x+pad,ty,size,muted,Font.PLAIN,family);
            }else{
                int s=Math.min(cur,anc),e=Math.max(cur,anc);
                if(focused&&e>s)c.fill(UiCanvas.fade(accent,.4F),next[s],y+h*.18F,next[e]-next[s],h*.64F);
                c.text(v,tx,ty,size,text,Font.PLAIN,family);
            }
            if(focused)c.fill(accent,next[cur],y+h*.2F,Math.max(.75F,size/12F),h*.6F);
        });
    }

    private void move(int next,boolean extend){cursor=next;if(!extend)anchor=next;}
    private void copy() {
        int s=Math.min(cursor,anchor),e=Math.max(cursor,anchor);
        if(e>s)Minecraft.getInstance().keyboardHandler.setClipboard(value.substring(s,e));
    }
    private void replace(String insert) {
        String v=value;int len=v.length();
        int s=Math.min(clamp(cursor,len),clamp(anchor,len)),e=Math.max(clamp(cursor,len),clamp(anchor,len));
        String safe=clean(insert==null?"":insert);
        int room=Math.max(0,maxLength-(len-(e-s)));
        safe=truncate(safe,room);
        value=v.substring(0,s)+safe+v.substring(e);
        cursor=anchor=s+safe.length();
    }
    private static String clean(String v){return v.replaceAll("[\\r\\n\\t]+"," ");}
    private int previous(int at){return at>0?value.offsetByCodePoints(at,-1):0;}
    private int next(int at){return at<value.length()?value.offsetByCodePoints(at,1):value.length();}
    private static String truncate(String s,int limit){int end=Math.min(s.length(),Math.max(0,limit));if(end>0&&end<s.length()&&Character.isHighSurrogate(s.charAt(end-1)))end--;return s.substring(0,end);}
    private static int clamp(int i,int len){return Math.max(0,Math.min(len,i));}
}
