package slooth.peripheral.ui;

import dev.pane.ui.*;
import java.awt.Font;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;

/**
 * Shared presentation vocabulary for Peripheral's Pane-based screens. Tokens, button styles,
 * glyphs and the document recipe are lifted from the approved Scripts design (now ScriptsPanel)
 * so every window reads as one product: charcoal frame, #1d1d1d panels, #111111 output wells,
 * warm off-white sans type, neutral borders and restrained lime for the current/primary control.
 * Amber and red are reserved for real warnings and errors.
 *
 * <p>Every screen built on this uses the same adaptive scaling and high-resolution raster
 * settings, so text is rasterized at physical resolution and hit regions always come from the
 * same paint that drew them.
 */
final class PeripheralUi {
    private PeripheralUi() {}

    static final int OUTER=0xF7303030, PANEL=0xFF1D1D1D, LOG=0xFF111111, FIELD=0xFF171717, HOVER=0xFF262626,
        LINE=0xFF383838, LINE_SOFT=0xFF2B2B2B, TEXT=0xFFEDE9E2, MUTED=0xFFA29E97, FAINT=0xFF706D68, CODE=0xFFDAD6CF,
        LIME=0xFFC7EF88, LIME_INK=0xFF1B2410, LIME_TINT=0xFF2B3522,
        RED=0xFFF0908A, RED_TINT=0xFF33201E, AMBER=0xFFE7C37E, AMBER_TINT=0xFF322A1C,
        STOP_TEXT=0xFFF5B8AE, SCRIM=0xB0000000, SELECTION=0x47C7EF88;
    static final String SANS=UiCanvas.SANS, MONO=UiCanvas.MONO;

    // Code-drawn glyphs (no assets).
    static final int G_NONE=0,G_BACK=1,G_CLOSE=2,G_PLAY=3,G_STOP=4,G_EDIT=5,G_REFRESH=6,G_LEFT=7,G_RIGHT=8,G_DOWN=9,
        G_CHECK=10,G_WARN=11,G_FOLDER=12,G_FILE=13,G_SHIELD=14,G_PROMPT=15,G_PLUS=16,G_COPY=17,G_SEND=18,G_CHEVRON=19;

    static final UiButtonStyle SECONDARY=style(0xFF262626,0xFF2F2F2F,0xFF202020,0xFF3A3A3A,TEXT,11,1),
        PRIMARY=style(LIME,0xFFD5F5A2,0xFFB2DA72,LIME,LIME_INK,12,1),
        DANGER=style(0xFF3A2321,0xFF462A27,0xFF311E1C,0xFF8C4A43,STOP_TEXT,12,1),
        CHIP=style(0x00000000,0xFF2A2A2A,0xFF222222,0x00000000,TEXT,11,0),
        CHIP_ON=style(LIME_TINT,0xFF34402A,0xFF283220,0xFF4A5E33,LIME,11,1),
        ROW=style(0x00000000,HOVER,0xFF222222,0x00000000,TEXT,12,0);

    /** PaneLib's native confirmation dialog in Peripheral's colors, with a red confirm for destructive or risky choices. */
    static final UiDialogStyle DANGER_DIALOG=UiDialogStyle.builder().backdrop(SCRIM).surface(0xFF242424).border(LINE)
        .titleColor(TEXT).messageColor(MUTED).cancelBackground(0xFF262626).cancelBorder(0xFF3A3A3A).cancelText(TEXT)
        .confirmBackground(0xFF5A2A26).confirmText(STOP_TEXT).radius(4).fontFamily(SANS)
        .titleSize(15).messageSize(11.5F).buttonSize(12).build();

    static UiButtonStyle style(int background,int hover,int pressed,int border,int text,float size,float borderWidth) {
        return UiButtonStyle.builder().background(background).hoverBackground(hover).pressedBackground(pressed).border(border)
            .text(text).focus(LIME).radius(3).borderWidth(borderWidth).textSize(size).fontStyle(Font.PLAIN).fontFamily(SANS).shadow(0,0,0,0).build();
    }

    /** One-page retained document with the Scripts screen's scaling and raster budget. */
    /** Peripheral's charcoal and lime as a PaneLib theme, for screens PaneLib builds itself such as the HUD editor. */
    static final UiTheme THEME=new UiTheme(PANEL,PANEL,FIELD,LINE,TEXT,MUTED,LIME,LIME_INK,3,3,4,1,.4F,SANS);

    static UiDocument document(String title,UiPainter painter) {
        return UiDocument.builder(title).shell(UiShell.NONE)
            .theme(THEME)
            // Grow the controls with the window; never shrink the compact layout's type.
            .scaling(UiScaling.standard().range(1,2.5F)).renderSettings(new UiRenderSettings(5120,3200,4))
            .page(new UiPage("main",title,"P",LIME,painter)).build();
    }

    /** Lets a screen hand instance methods to its own super constructor. */
    static final class Ref<T> { T v; }

    /** Runs on Minecraft's render thread; immediately when already there. */
    static void onMain(Runnable task) {
        Minecraft mc=Minecraft.getInstance();
        if(mc==null||mc.isSameThread())task.run(); else mc.execute(task);
    }

    // ---- drawing vocabulary: every text call names SANS or MONO explicitly

    static void frame(UiCanvas c,float x,float y,float w,float h) {
        c.roundRect(OUTER,x,y,w,h,4);c.strokeRoundRect(LINE,1,x,y,w,h,4);
    }
    static void panel(UiCanvas c,float x,float y,float w,float h) {
        if(w<=0||h<=0)return;
        c.roundRect(PANEL,x,y,w,h,3);c.strokeRoundRect(LINE_SOFT,1,x,y,w,h,3);
    }
    static void well(UiCanvas c,float x,float y,float w,float h) {
        if(w<=0||h<=0)return;
        c.roundRect(LOG,x,y,w,h,3);c.strokeRoundRect(LINE_SOFT,1,x,y,w,h,3);
    }
    static void hline(UiCanvas c,int color,float x,float y,float w){if(w>0)c.fill(color,x,y,w,1);}
    static void vline(UiCanvas c,int color,float x,float y,float h){if(h>0)c.fill(color,x,y,1,h);}

    /** Vertically centres one line of text on cy. */
    static void mid(UiCanvas c,String value,float x,float cy,float size,int color,int style,String family) {
        c.text(value,x,cy-size*.6F,size,color,style,family);
    }
    static float btnW(UiCanvas c,String label,int glyph,float size) {
        return Math.round(c.textWidth(label,size,Font.PLAIN,SANS)+(glyph==G_NONE?0:Math.round(size*.85F)+6)+22);
    }
    /** Pane button with the label and an optional code-drawn glyph painted over it. Disabled buttons register no hit. */
    static void action(UiContext ui,String id,String label,String action,int glyph,float x,float y,float w,float h,
                       UiButtonStyle style,int color,float size,boolean enabled) {
        ui.button(id,"",action,x,y,w,h,style,enabled);
        // The label is painted by hand below; give it to the narrator and to scripts that click by name.
        ui.narration(id,UiNarration.of(UiNarration.Role.BUTTON,label));
        var c=ui.canvas();
        float gs=glyph==G_NONE?0:Math.round(size*.85F),gap=glyph==G_NONE?0:6;
        String shown=c.ellipsize(label,Math.max(8,w-12-gs-gap),size,Font.PLAIN,SANS);
        float tw=c.textWidth(shown,size,Font.PLAIN,SANS),sx=x+(w-(gs+gap+tw))/2;
        c.withOpacity(enabled?1:.42F,()->{
            if(glyph!=G_NONE)glyph(c,glyph,color,sx,y+(h-gs)/2,gs);
            c.centeredText(shown,sx+gs+gap+tw/2,y+h/2,size,color,Font.PLAIN,SANS);
        });
    }
    /** Standard secondary button sized to its label; returns its width. */
    static float secondary(UiContext ui,String id,String label,int glyph,float x,float y,float h,boolean enabled) {
        float w=btnW(ui.canvas(),label,glyph,11);
        action(ui,id,label,id,glyph,x,y,w,h,SECONDARY,TEXT,11,enabled);
        return w;
    }
    static float primary(UiContext ui,String id,String label,int glyph,float x,float y,float h,boolean enabled) {
        float w=btnW(ui.canvas(),label,glyph,12);
        action(ui,id,label,id,glyph,x,y,w,h,PRIMARY,LIME_INK,12,enabled);
        return w;
    }
    static float danger(UiContext ui,String id,String label,int glyph,float x,float y,float h,boolean enabled) {
        float w=btnW(ui.canvas(),label,glyph,12);
        action(ui,id,label,id,glyph,x,y,w,h,DANGER,STOP_TEXT,12,enabled);
        return w;
    }

    /** Small rounded status pill. Returns its width. */
    static float pill(UiCanvas c,String label,float x,float y,int color,int tint) {
        float w=Math.round(c.textWidth(label,10.5F,Font.BOLD,SANS)+16),h=20;
        c.roundRect(tint,x,y,w,h,3);c.strokeRoundRect(UiCanvas.fade(color,.4F),1,x,y,w,h,3);
        c.centeredText(label,x+w/2,y+h/2,10.5F,color,Font.BOLD,SANS);
        return w;
    }
    static float pillW(UiCanvas c,String label){return Math.round(c.textWidth(label,10.5F,Font.BOLD,SANS)+16);}

    /**
     * Modal sheet: dims the whole surface, swallows clicks outside the sheet (sending
     * {@code scrimAction}, or "noop"), and paints a centred panel that always fits the window.
     * Paint it after the page so its hit regions win.
     */
    static UiRect modal(UiContext ui,float w,float h,String scrimAction) {
        var c=ui.canvas();
        float sw=ui.width(),sh=ui.height();
        c.fill(SCRIM,0,0,sw,sh);
        ui.hit("modal-scrim",scrimAction==null?"noop":scrimAction,0,0,sw,sh);
        float mw=Math.min(w,sw-16),mh=Math.min(h,sh-16),mx=Math.round((sw-mw)/2),my=Math.round((sh-mh)/2);
        c.roundRect(0xFF242424,mx,my,mw,mh,4);c.strokeRoundRect(LINE,1,mx,my,mw,mh,4);
        ui.hit("modal-body","noop",mx,my,mw,mh);
        return new UiRect(mx,my,mw,mh);
    }

    static void centered(UiCanvas c,String title,String body,UiRect v) {
        float maxW=Math.max(40,Math.min(v.width()-32,380));
        List<String> lines=body==null||body.isEmpty()?List.of():c.wrapText(body,maxW,11,Font.PLAIN,SANS);
        float total=22+lines.size()*15,ty=v.y()+Math.max(10,(v.height()-total)/2),mx=v.x()+v.width()/2;
        c.withClip(v.x(),v.y(),v.width(),v.height(),()->{
            c.centeredText(title,mx,ty+7,12.5F,TEXT,Font.BOLD,SANS);
            for(int i=0;i<lines.size();i++)c.centeredText(lines.get(i),mx,ty+27+i*15,11,MUTED,Font.PLAIN,SANS);
        });
    }

    /** Wrapped paragraph; returns the height used. */
    static float para(UiCanvas c,String text,float x,float y,float w,float size,float lineH,int color) {
        return c.wrappedText(text,x,y,w,size,lineH,color,Font.PLAIN,SANS);
    }

    static void glyph(UiCanvas c,int kind,int color,float x,float y,float s) {
        float m=s/2;
        switch(kind){
            case G_BACK -> {c.line(color,1.3F,x+1,y+m,x+s-1,y+m);c.line(color,1.3F,x+1,y+m,x+m,y+1.5F);c.line(color,1.3F,x+1,y+m,x+m,y+s-1.5F);}
            case G_CLOSE -> {c.line(color,1.3F,x+1.5F,y+1.5F,x+s-1.5F,y+s-1.5F);c.line(color,1.3F,x+s-1.5F,y+1.5F,x+1.5F,y+s-1.5F);}
            case G_PLAY -> c.polygon(color,x+2,y+1,x+s-1,y+m,x+2,y+s-1);
            case G_STOP -> c.roundRect(color,x+1.5F,y+1.5F,s-3,s-3,1);
            case G_EDIT -> {c.line(color,1.4F,x+2,y+s-2,x+s-1.5F,y+1.5F);c.line(color,1.2F,x+1,y+s-.6F,x+m,y+s-.6F);}
            case G_REFRESH -> {c.strokeCircle(color,1.2F,x+m,y+m,m-1.5F);c.polygon(color,x+s-.5F,y+.5F,x+s-.5F,y+m-.5F,x+m+.5F,y+.5F);}
            case G_LEFT -> {c.line(color,1.4F,x+s*.65F,y+1.5F,x+s*.3F,y+m);c.line(color,1.4F,x+s*.3F,y+m,x+s*.65F,y+s-1.5F);}
            case G_RIGHT -> {c.line(color,1.4F,x+s*.35F,y+1.5F,x+s*.7F,y+m);c.line(color,1.4F,x+s*.7F,y+m,x+s*.35F,y+s-1.5F);}
            case G_DOWN -> {c.line(color,1.3F,x+m,y+1,x+m,y+s-1);c.line(color,1.3F,x+m,y+s-1,x+1.5F,y+m);c.line(color,1.3F,x+m,y+s-1,x+s-1.5F,y+m);}
            case G_CHECK -> {c.line(color,1.5F,x+1,y+s*.55F,x+s*.4F,y+s-2);c.line(color,1.5F,x+s*.4F,y+s-2,x+s-1,y+2);}
            case G_WARN -> {c.strokeCircle(color,1.2F,x+m,y+m,m-.6F);c.fill(color,x+m-.6F,y+s*.25F,1.2F,s*.36F);c.fill(color,x+m-.6F,y+s*.68F,1.2F,1.4F);}
            case G_FOLDER -> {c.strokeRoundRect(color,1,x,y+2,s,s-3,1.5F);c.fill(color,x+1,y+.5F,s*.4F,1.5F);}
            case G_FILE -> {c.strokeRoundRect(color,1,x+1,y,s-3,s,1.5F);c.line(color,1,x+3.5F,y+s*.45F,x+s-4.5F,y+s*.45F);c.line(color,1,x+3.5F,y+s*.7F,x+s-5.5F,y+s*.7F);}
            case G_SHIELD -> {c.strokeCircle(color,1,x+m,y+m,m-.5F);c.line(color,1.2F,x+s*.3F,y+m,x+s*.45F,y+s*.66F);c.line(color,1.2F,x+s*.45F,y+s*.66F,x+s*.72F,y+s*.34F);}
            case G_PROMPT -> {c.line(color,1.3F,x+1,y+1.5F,x+s*.45F,y+m);c.line(color,1.3F,x+s*.45F,y+m,x+1,y+s-1.5F);c.line(color,1.3F,x+s*.55F,y+s-1,x+s,y+s-1);}
            case G_PLUS -> {c.line(color,1.4F,x+m,y+1.5F,x+m,y+s-1.5F);c.line(color,1.4F,x+1.5F,y+m,x+s-1.5F,y+m);}
            case G_COPY -> {c.strokeRoundRect(color,1,x+s*.3F,y,s*.7F,s*.7F,1.2F);c.strokeRoundRect(color,1,x,y+s*.3F,s*.7F,s*.7F,1.2F);}
            case G_CHEVRON -> {c.line(color,1.3F,x+.5F,y+s*.3F,x+m,y+s*.72F);c.line(color,1.3F,x+m,y+s*.72F,x+s-.5F,y+s*.3F);}
            case G_SEND -> {c.line(color,1.4F,x+1,y+m,x+s-1,y+m);c.line(color,1.4F,x+s-1,y+m,x+m,y+1.5F);c.line(color,1.4F,x+s-1,y+m,x+m,y+s-1.5F);}
            default -> {}
        }
    }

    // ---- text utilities

    /** Removes Minecraft legacy formatting codes for plain painting and clipboard copies. */
    static String strip(String value){return value==null?"":value.replaceAll("§.","");}
    static String basename(String name){int i=name.lastIndexOf('/');return name.substring(i+1);}
    /** Keeps the end of a monospace path (file name) visible by eliding from the left. */
    static String fitTail(UiCanvas c,String value,float max,float size,int style) {
        if(c.textWidth(value,size,style,MONO)<=max)return value;
        int start=Math.max(0,value.length()-(int)(max/Math.max(1,c.textWidth("M",size,style,MONO))));
        while(start<value.length()-1&&c.textWidth("…"+value.substring(start),size,style,MONO)>max)start++;
        return "…"+value.substring(start);
    }
    /** Average advance of one monospace column, measured over a long run so rounding cannot accumulate. */
    static float monoAdvance(UiCanvas c,float size){return c.textWidth("M".repeat(64),size,Font.PLAIN,MONO)/64F;}
    /** Hard-wraps to a fixed column count (monospace output). Empty lines are kept. */
    static List<String> hardWrap(String line,int cols) {
        List<String> out=new ArrayList<>();
        if(line.isEmpty()){out.add("");return out;}
        for(int at=0;at<line.length();at+=cols)out.add(line.substring(at,Math.min(line.length(),at+cols)));
        return out;
    }
    static String joinLines(List<String> lines,int a,int b) {
        int s=Math.max(0,Math.min(a,b)),e=Math.min(lines.size()-1,Math.max(a,b));
        StringBuilder sb=new StringBuilder();
        for(int i=s;i<=e;i++){if(i>s)sb.append('\n');sb.append(strip(lines.get(i)));}
        return sb.toString();
    }
}
