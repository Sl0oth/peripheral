package slooth.peripheral.ui;

import dev.pane.ui.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import java.awt.Font;

/** Save/discard/cancel choice that never discards an editor draft on Escape. */
public final class UnsavedChangesScreen extends UiScreen {
    private final Screen editor;
    public UnsavedChangesScreen(Screen editor,Runnable saveAndClose,Runnable discardAndClose){
        super(createDocument(),NativeOverlayRenderer.NONE,(UiEventHandler)e->{
            if(e.type()!=UiEventType.ACTION)return;
            switch(e.key()){
                case "save" -> saveAndClose.run();
                case "discard" -> discardAndClose.run();
                case "cancel" -> Minecraft.getInstance().gui.setScreen(editor);
            }
        },UiScreenOptions.builder().backdrop(UiBackdrop.NONE).build());
        this.editor=editor;
    }
    private static UiDocument createDocument(){
        var button=UiButtonStyle.builder().background(0xFF262626).hoverBackground(0xFF333333).pressedBackground(0xFF202020)
            .border(0xFF444444).text(0xFFEDE9E2).focus(0xFFC7EF88).radius(3).textSize(12).fontFamily(UiCanvas.SANS).shadow(0,0,0,0).build();
        var primary=UiButtonStyle.builder().background(0xFFC7EF88).hoverBackground(0xFFD5F5A2).pressedBackground(0xFFB2DA72)
            .border(0xFFC7EF88).text(0xFF1B2410).focus(0xFFC7EF88).radius(3).textSize(12).fontFamily(UiCanvas.SANS).shadow(0,0,0,0).build();
        return UiDocument.builder("Unsaved changes").shell(UiShell.NONE)
            .scaling(UiScaling.standard().range(1,2.5F)).renderSettings(new UiRenderSettings(5120,3200,4))
            .page(new UiPage("confirm","Unsaved changes","",0xFFC7EF88,ui->{
                var c=ui.canvas();float w=Math.min(420,ui.width()-24),h=190,x=(ui.width()-w)/2,y=(ui.height()-h)/2;
                c.fill(0xBB000000,0,0,ui.width(),ui.height());c.roundRect(0xFF303030,x,y,w,h,4);c.strokeRoundRect(0xFF444444,1,x,y,w,h,4);
                c.text("Keep your changes?",x+20,y+20,20,0xFFEDE9E2,Font.BOLD,UiCanvas.SANS);
                c.wrappedText("This script has unsaved edits. Save them before leaving, or keep editing.",x+20,y+56,w-40,12,18,0xFFA29E97,Font.PLAIN,UiCanvas.SANS);
                float gap=8,bw=(w-40-gap*2)/3,by=y+h-52;
                ui.button("save","Save","save",x+20,by,bw,32,primary);
                ui.button("discard","Discard","discard",x+20+bw+gap,by,bw,32,button);
                ui.button("cancel","Keep editing","cancel",x+20+(bw+gap)*2,by,bw,32,button);
            })).build();
    }
    @Override public void onClose(){Minecraft.getInstance().gui.setScreen(editor);}
    @Override public boolean isPauseScreen(){return false;}
}
