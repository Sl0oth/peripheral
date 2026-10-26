package slooth.peripheral.ui;

import slooth.peripheral.PeripheralClient;
import slooth.peripheral.api.PeripheralHttpServer;
import slooth.peripheral.api.PeripheralSocketServer;
import dev.pane.ui.*;
import slooth.peripheral.script.ScriptRunner;
import slooth.peripheral.util.PeripheralConfig;

import net.minecraft.client.Minecraft;

import java.awt.Font;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static slooth.peripheral.ui.PeripheralUi.*;

/**
 * The Scripts home of the control panel: a library column (search, folders, rows) beside the
 * selected script (Run/Stop, Edit, auto-run, hotkey, More) and its live output. Below about 560
 * layout units it shows one pane at a time. File discovery happens off-thread; paint only reads
 * snapshots.
 *
 * <p>Rename, delete, create and edit open sheets or screens owned by {@link PeripheralScreen},
 * so this panel emits those as ordinary actions ({@code rename:}, {@code del:}, {@code edit:},
 * {@code new-script}, {@code new-folder}) and handles everything else itself.
 */
final class ScriptsPanel {
    private static final int LIME_BAR=0xFF222B1A, NEUTRAL_TINT=0xFF262626;
    private static final int[] LOG_COLORS={CODE,0xFF8E8A84,AMBER,RED};
    private static final float ROW_H=30, ROW_GAP=2, LOG_SIZE=11.5F, LOG_LINE=16, BAR_H=30, BTN_H=30;
    // Status kinds derived only from ScriptRunner.isRunning/outcome.
    private static final int K_READY=0,K_RUNNING=1,K_STOPPING=2,K_FINISHED=3,K_STOPPED=4,K_FAILED=5,K_NOTICE=6,K_ENDING=7;
    private static final java.util.regex.Pattern ANSI=java.util.regex.Pattern.compile("\u001B\\[[0-9;?]*[ -/]*[@-~]"),
        EXIT_CODE=java.util.regex.Pattern.compile("\\((-?\\d+)\\)");
    private static final java.time.format.DateTimeFormatter CLOCK=java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final ExecutorService IO=Executors.newSingleThreadExecutor(r -> { Thread t=new Thread(r,"peripheral-script-list");t.setDaemon(true);return t; });
    private static final String[] AUTO_MODES={"off","world","multi","both"},AUTO_LABELS={"Off","Singleplayer","Multiplayer","Any world"};

    /** startedAt is ScriptRunner's own epoch-second start time from the folder scan; 0 when unknown. */
    private record Row(String name,boolean folder,long startedAt){}
    /** Display reading of one script; title/body feed the inline result banner, a null title means none. */
    private record Status(String label,int color,int tint,int kind,boolean marker,String meta,String title,String body){}
    /** One hard-wrapped line of the output tail; kind indexes LOG_COLORS. */
    private record Line(String text,int source,boolean first,int kind){}

    // The open folder and selection survive closing and reopening the panel.
    private static String s_folder="",s_selected="";

    private final UiScreen screen;
    String folder=s_folder,query="",selected=s_selected,problem="",signature="";
    private List<Row> rows=List.of();private List<String> logs=List.of(),running=List.of();
    private boolean scanned,scanning,closed,showLibrary;
    private List<String> wrapSource;private int wrapCols,errorLines;private List<Line> wrapped=List.of();
    private int tick;

    ScriptsPanel(UiScreen screen){
        this.screen=screen;
        running=ScriptRunner.listRunningScripts();
        if(!selected.isEmpty())logs=ScriptRunner.logTail(selected);
    }

    /** The folder new scripts and folders are created in, relative to the scripts root. */
    Path folderPath(){return Path.of(folder);}
    void opened(){closed=false;refresh();}
    void closed(){closed=true;}

    // ---- data

    void tick(){
        if(++tick%10==0){ // process/log snapshots only invalidate when their visible values change
            List<String> active=ScriptRunner.listRunningScripts();
            String sig=active+"|"+ScriptRunner.outcome(selected)+"|"+ScriptRunner.logTail(selected).hashCode()+"|"+PeripheralHttpServer.status
                +"|"+PeripheralSocketServer.status+"|"+PeripheralConfig.getAutoRun(selected)+"|"+PeripheralConfig.slotOf(selected);
            if(!sig.equals(signature)){signature=sig;running=active;logs=ScriptRunner.logTail(selected);invalidateRows();}
        }
        if(tick%40==0)refresh();
    }

    void refresh(){
        if(scanning||closed)return;scanning=true;
        String f=folder,q=query;
        IO.submit(() -> {
            List<Row> found=new ArrayList<>();String error="";
            try{
                if(!q.isBlank()){
                    for(String name:ScriptRunner.listAllScripts())if(name.toLowerCase(Locale.ROOT).contains(q.toLowerCase(Locale.ROOT)))found.add(new Row(name,false,0));
                }else{
                    // Script entries already carry their path; folder entries are bare names.
                    for(var entry:ScriptRunner.listEntries(Path.of(f)))
                        found.add(new Row(entry.isFolder()&&!f.isEmpty()?f+"/"+entry.name():entry.name(),entry.isFolder(),entry.startedAt()));
                }
            }catch(Exception e){error="Could not read this folder. Check file permissions and linked folders.";}
            List<Row> result=List.copyOf(found);String err=error;
            onMain(() -> {
                scanning=false;if(closed)return;
                if(!f.equals(folder)||!q.equals(query)){refresh();return;}
                boolean first=!scanned;scanned=true; // one repaint retires the "reading" placeholder
                if(first||!result.equals(rows)||!err.equals(problem)){rows=result;problem=err;
                    // A selection that no longer exists (renamed or deleted elsewhere) is dropped.
                    if(!selected.isEmpty()&&!java.nio.file.Files.exists(slooth.peripheral.script.ScriptPaths.resolve(selected)))select("");
                    invalidateRows();}
            });
        });
    }

    private void invalidateRows(){screen.invalidate();for(int i=0;i<rows.size();i++)screen.invalidateVirtualItem("scripts",i);}

    /** Selects a script by path ("" clears), as a row click does. */
    void select(String name){
        selected=s_selected=name;showLibrary=name.isEmpty();
        screen.setValue("selected-script",name);
        logs=name.isEmpty()?List.of():ScriptRunner.logTail(name);
        screen.scrollToEnd("output");invalidateRows();
    }

    /** Called after a rename moved a file or folder, so the selection and open folder follow it. */
    void renamed(String from,String to){
        if(selected.equals(from))select(to);
        else if(selected.startsWith(from+"/"))select(to+selected.substring(from.length()));
        if(folder.equals(from)||folder.startsWith(from+"/"))openFolder(to+folder.substring(from.length()));
        refresh();
    }

    /** Called after a delete; clears whatever it removed. */
    void deleted(String path){
        if(selected.equals(path)||selected.startsWith(path+"/"))select("");
        if(folder.equals(path)||folder.startsWith(path+"/"))openFolder(parentOf(path).replaceAll("/$",""));
        refresh();
    }

    private void openFolder(String f){
        folder=s_folder=f;query="";scanned=false;
        screen.setValue("query","");screen.scrollTo("scripts",0);
        refresh();
    }

    // ---- events

    /** Handles the panel's own values and actions; false leaves the event to the screen. */
    boolean event(UiEvent e){
        if(e.type()==UiEventType.VALUE){
            if(e.key().equals("query")){query=e.value();screen.scrollTo("scripts",0);refresh();return true;}
            return false;
        }
        return e.type()==UiEventType.ACTION&&handle(e.key());
    }

    boolean handle(String action){
        if(action.startsWith("choose:")){
            String name=action.substring(7);Row row=rows.stream().filter(r->r.name.equals(name)).findFirst().orElse(null);
            if(row==null&&running.contains(name))row=new Row(name,false,0); // session chips reach running scripts in other folders
            if(row==null)return true;
            if(row.folder)openFolder(name);else select(name);
            return true;
        }
        if(action.startsWith("autoset:")){
            String[] p=action.substring(8).split(":",2);
            if(p.length==2){PeripheralConfig.setAutoRun(p[1],p[0]);invalidateRows();}
            return true;
        }
        if(action.startsWith("hotkey:")){
            String[] p=action.substring(7).split(":",2);
            if(p.length==2){PeripheralConfig.assignHotkey(p[1],Integer.parseInt(p[0]));invalidateRows();}
            return true;
        }
        switch(action){
            case "files" -> {showLibrary=true;invalidateRows();}
            case "up" -> {int i=folder.lastIndexOf('/');openFolder(i<0?"":folder.substring(0,i));}
            case "refresh" -> refresh();
            case "run" -> {if(!selected.isEmpty()){ScriptRunner.runScript(selected);screen.scrollToEnd("output");refresh();invalidateRows();}}
            case "stop" -> {ScriptRunner.stopScript(selected);invalidateRows();}
            case "latest" -> screen.scrollToEnd("output");
            default -> {return false;}
        }
        return true;
    }

    private long startedAt(String name){for(Row r:rows)if(r.name.equals(name))return r.startedAt;return 0;}

    // ---- paint

    void paint(UiContext ui,float x,float y,float w,float h){
        var c=ui.canvas();
        boolean roomy=w>=1050&&h>=560,shortWindow=h<300;
        boolean narrow=w<540;
        List<Row> snapshot=rows;List<String> active=running;

        // Footer: the trust note.
        float footH=30,footY=y+h-footH;
        float noteRoom=w;
        if(noteRoom>80){
            String trust="Only run scripts you trust. They can control your game.";
            UiIcon.SHIELD.draw(c,MUTED,x,footY+footH/2-5.5F,11,1.3F);
            mid(c,c.ellipsize(trust,noteRoom-16,10.5F,Font.PLAIN,SANS),x+16,footY+footH/2,10.5F,MUTED,Font.PLAIN,SANS);
        }
        float bottom=footY-(roomy?14:10);

        if(!narrow){
            float gap=roomy?16:12,lw=Math.round(Math.max(230,Math.min(320,w*.27F)));
            if(w-lw-gap<300)lw=Math.max(220,w-gap-300);
            library(ui,snapshot,x,y,lw,bottom-y);
            float px=x+lw+gap,pw=w-lw-gap;
            runningStrip(ui,active,px,y,pw,32,true);
            float dy=y+42;
            if(selected.isEmpty())emptyDetail(ui,active,px,dy,pw,bottom-dy);
            else detail(ui,px,dy,pw,bottom-dy,false);
        }else{ // one pane at a time; the library keeps its query, folder and scroll
            boolean showLib=selected.isEmpty()||showLibrary;
            float cy=y;
            if(!showLib){
                float libW=ui.buttonWidth("Library",UiIcon.CHEVRON_LEFT,SECONDARY);
                ui.button("library","Library",UiIcon.CHEVRON_LEFT,"files",x,cy,libW,28,SECONDARY,true);
                cy+=shortWindow?32:36;
            }
            runningStrip(ui,active,x,cy,w,shortWindow?26:32,false);cy+=shortWindow?34:40;
            if(showLib)library(ui,snapshot,x,cy,w,bottom-cy);
            else detail(ui,x,cy,w,bottom-cy,true);
        }
    }

    // ---- library

    private void library(UiContext ui,List<Row> snapshot,float x,float y,float w,float h){
        var c=ui.canvas();boolean searching=!query.isBlank(),inExamples=folder.equals("examples")||folder.startsWith("examples/");
        panel(c,x,y,w,h);
        float ip=12,ix=x+ip,iw=w-ip*2,cy=y+ip;
        int scripts=0,folders=0;for(Row r:snapshot)if(!r.folder)scripts++;else folders++;

        // Header: Library + count, then New (script or folder here).
        float newW=ui.menuButtonWidth("New",null,SECONDARY);
        if(!inExamples){
            ui.menuButton("new","New",null,x+w-ip-newW,cy,newW,28,SECONDARY,true,
                List.of(new UiMenuItem("script","New script","new-script"),new UiMenuItem("folder","New folder","new-folder")));
            ui.tooltip("new","Create a script or folder in "+(folder.isEmpty()?"scripts":"scripts/"+folder));
        }
        float headRight=x+w-ip-(inExamples?0:newW+6);
        mid(c,"Library",ix,cy+14,12,TEXT,Font.BOLD,SANS);
        String meta=!scanned&&snapshot.isEmpty()?"reading…":searching?scripts+(scripts==1?" match":" matches")
            :scripts==0&&folders>0?folders+(folders==1?" folder":" folders"):scripts+(scripts==1?" file":" files");
        float metaX=ix+c.textWidth("Library",12,Font.BOLD,SANS);
        // The count shows whole or not at all; a clipped "2 fold…" reads as noise.
        if(metaX+c.textWidth(" / "+meta,11,Font.PLAIN,SANS)<=headRight-8)mid(c," / "+meta,metaX,cy+14,11,FAINT,Font.PLAIN,SANS);
        cy+=36;
        // Search with Refresh beside it: both are about what the list shows.
        float refW=30;
        ui.textField("search","query","Search every folder…",ix,cy,iw-refW-6,30,query,160);
        ui.iconButton("refresh","Reload this folder",UiIcon.REFRESH,"refresh",x+w-ip-refW,cy,refW,SECONDARY,true);
        cy+=38;

        // Where you are: the crumb, with Up and the folder's own menu once inside one.
        if(searching)mid(c,"Results from every folder",ix,cy+13,11,FAINT,Font.PLAIN,SANS);
        else if(folder.isEmpty())crumb(c,ix,cy+13,iw);
        else{
            float upW=ui.buttonWidth("Up",UiIcon.CHEVRON_LEFT,SECONDARY),moreW=inExamples?0:ui.menuButtonWidth("More",null,SECONDARY);
            ui.button("up","Up",UiIcon.CHEVRON_LEFT,"up",ix,cy,upW,26,SECONDARY,true);
            if(!inExamples)ui.menuButton("folder-more","More",null,x+w-ip-moreW,cy,moreW,26,SECONDARY,true,
                List.of(new UiMenuItem("rename","Rename folder","rename:"+folder),new UiMenuItem("delete","Delete folder…","del:"+folder)));
            crumb(c,ix+upW+10,cy+13,iw-upW-10-(moreW>0?moreW+8:0));
        }
        cy+=32;
        hline(c,LINE_SOFT,x+1,cy-3,w-2);
        float rootH=28,listBottom=y+h-rootH;
        UiRect viewport=new UiRect(x+4,cy+2,w-8,Math.max(ROW_H,listBottom-cy-6));
        ui.virtualList("scripts",viewport,snapshot.size(),ROW_H,ROW_GAP,(rowUi,item)->paintRow(rowUi,snapshot.get(item.index()),item.bounds()));
        if(snapshot.isEmpty())emptyLibrary(c,viewport,searching);
        hline(c,LINE_SOFT,x+1,listBottom,w-2);
        UiIcon.FOLDER.draw(c,FAINT,ix,listBottom+9,10,1.3F);
        mid(c,fitTail(c,root(),iw-16,10,Font.PLAIN),ix+16,listBottom+14,10,FAINT,Font.PLAIN,MONO);
    }

    private void paintRow(UiContext ui,Row row,UiRect b){
        var c=ui.canvas();String id="row:"+row.name;
        boolean searching=!query.isBlank(),picked=!row.folder&&row.name.equals(selected),hover=ui.hovered(id);
        float rx=b.x()+2,rw=b.width()-4,cy=b.y()+b.height()/2;
        if(picked)c.roundRect(LIME_TINT,rx,b.y(),rw,b.height(),3);else if(hover)c.roundRect(HOVER,rx,b.y(),rw,b.height(),3);
        float gx=rx+10,nx=rx+30,right=rx+rw-10;
        if(row.folder){
            UiIcon.FOLDER.draw(c,hover?TEXT:MUTED,gx,cy-5,11,1.3F);
            UiIcon.CHEVRON_RIGHT.draw(c,FAINT,right-7,cy-4,8,1.3F);
            mid(c,c.ellipsize(basename(row.name),Math.max(20,right-16-nx),12,Font.PLAIN,SANS),nx,cy,12,TEXT,Font.PLAIN,SANS);
        }else{
            Status s=status(row.name);
            UiIcon.FILE.draw(c,picked?LIME:FAINT,gx,cy-6,12,1.3F);
            float tail=0;
            if(s.marker()){c.roundRect(s.color(),right-5,cy-2.5F,5,5,1);tail=14;}
            // Small tags for what runs this script without a click: its hotkey slot and auto-run.
            int slot=PeripheralConfig.slotOf(row.name);
            if(!PeripheralConfig.getAutoRun(row.name).equals("off")){String t="auto";float tw=c.textWidth(t,9.5F,Font.PLAIN,MONO);
                mid(c,t,right-tail-tw,cy,9.5F,FAINT,Font.PLAIN,MONO);tail+=tw+8;}
            if(slot>0){String t="["+slot+"]";float tw=c.textWidth(t,10,Font.PLAIN,MONO);
                mid(c,t,right-tail-tw,cy,10,LIME,Font.PLAIN,MONO);tail+=tw+8;}
            String shown=c.ellipsize(basename(row.name),Math.max(20,right-tail-nx),12,Font.PLAIN,SANS);
            mid(c,shown,nx,cy,12,picked?LIME:TEXT,Font.PLAIN,SANS);
            if(searching){ // the folder explains why a search hit is here
                String parent=parentOf(row.name);float px=nx+c.textWidth(shown,12,Font.PLAIN,SANS)+8;
                if(!parent.isEmpty()&&px+40<right-tail)mid(c,fitTail(c,parent,right-tail-px,10,Font.PLAIN),px,cy,10,FAINT,Font.PLAIN,MONO);
            }
        }
        if(ui.focused(id))c.strokeRoundRect(LIME,1,rx,b.y(),rw,b.height(),3);
        ui.hit(id,"choose:"+row.name,b.x(),b.y(),b.width(),b.height());
    }

    private void emptyLibrary(UiCanvas c,UiRect v,boolean searching){
        final String head,body;final int color;
        if(!problem.isEmpty()){head="Can't read this folder";body=problem+" Press Refresh to try again.";color=RED;}
        else if(!scanned){head="Reading folder…";body="";color=MUTED;}
        else if(searching){head="No matches";body="No script path contains \""+query+"\". Search looks in every folder; clear it to browse.";color=TEXT;}
        else if(folder.isEmpty()){head="No scripts yet";body="Press New to make one, or put .py files in the folder shown below.";color=TEXT;}
        else{head="This folder is empty";body="Press New to add a script here, or Up to go back.";color=TEXT;}
        float tx=v.x()+10,ty=v.y()+10,tw=v.width()-20;
        c.withClip(v.x(),v.y(),v.width(),v.height(),()->{
            c.text(head,tx,ty,12,color,Font.BOLD,SANS);
            if(!body.isEmpty())c.wrappedText(body,tx,ty+20,tw,11,15,MUTED,Font.PLAIN,SANS);
        });
    }

    /** Breadcrumb for the open folder; leading segments collapse first so the current one stays readable. */
    private void crumb(UiCanvas c,float x,float cy,float max){
        List<String> parts=new ArrayList<>();parts.add("scripts");
        if(!folder.isEmpty())parts.addAll(Arrays.asList(folder.split("/")));
        boolean cut=false;
        while(parts.size()>1&&c.textWidth((cut?"… / ":"")+String.join(" / ",parts),11,Font.PLAIN,SANS)>max){parts.remove(0);cut=true;}
        float px=x;
        if(cut){mid(c,"… / ",px,cy,11,FAINT,Font.PLAIN,SANS);px+=c.textWidth("… / ",11,Font.PLAIN,SANS);}
        for(int i=0;i<parts.size();i++){
            boolean last=i==parts.size()-1;
            String part=last?c.ellipsize(parts.get(i),Math.max(10,x+max-px),11,Font.PLAIN,SANS):parts.get(i);
            mid(c,part,px,cy,11,last?TEXT:MUTED,Font.PLAIN,SANS);
            px+=c.textWidth(part,11,Font.PLAIN,SANS);
            if(!last){mid(c," / ",px,cy,11,FAINT,Font.PLAIN,SANS);px+=c.textWidth(" / ",11,Font.PLAIN,SANS);}
        }
    }

    // ---- detail column

    /** Real running processes as shortcuts (ids session:<path>). Sits above the detail pane. */
    private void runningStrip(UiContext ui,List<String> active,float x,float y,float w,float h,boolean hint){
        var c=ui.canvas();
        panel(c,x,y,w,h);
        float ix=x+12,cy=y+h/2,limit=x+w-8;
        if(active.isEmpty()){
            c.strokeRoundRect(FAINT,1,ix,cy-3,6,6,1);
            mid(c,c.ellipsize("No scripts running",Math.max(20,w-36),11,Font.PLAIN,SANS),ix+14,cy,11,MUTED,Font.PLAIN,SANS);
            if(hint){ // the local API is what scripts talk to; amber only when it isn't ready
                boolean ok="Ready".equals(PeripheralHttpServer.status)&&"Ready".equals(PeripheralSocketServer.status);
                String api=ok?"Local API ready":"Local API · HTTP "+apiState(PeripheralHttpServer.status)+" · Socket "+apiState(PeripheralSocketServer.status);
                float aw=c.textWidth(api,10,Font.PLAIN,MONO);
                if(ix+150+aw<=limit-4)mid(c,api,limit-4-aw,cy,10,ok?FAINT:AMBER,Font.PLAIN,MONO);
            }
            return;
        }
        c.roundRect(LIME,ix,cy-3,6,6,1);
        String count=active.size()+" RUNNING";
        c.trackedText(count,ix+13,cy-6,10,LIME,Font.BOLD,MONO,.4F);
        float chipX=ix+13+c.trackedTextWidth(count,10,Font.BOLD,MONO,.4F)+10;
        for(int i=0;i<active.size();i++){
            String name=active.get(i),label=c.ellipsize(basename(name),150,11,Font.PLAIN,SANS),more="+"+(active.size()-i)+" more";
            float chipW=Math.round(c.textWidth(label,11,Font.PLAIN,SANS)+20),reserve=i<active.size()-1?c.textWidth(more,10.5F,Font.PLAIN,SANS)+10:0;
            if(chipX+chipW+reserve>limit){mid(c,more,chipX+2,cy,10.5F,MUTED,Font.PLAIN,SANS);chipX+=c.textWidth(more,10.5F,Font.PLAIN,SANS)+4;break;}
            ui.button("session:"+name,label,"choose:"+name,chipX,y+4,chipW,h-8,name.equals(selected)?CHIP_ON:CHIP);
            chipX+=chipW+4;
        }
        String tip="Pick one to see its output";
        float tipW=c.textWidth(tip,10.5F,Font.PLAIN,SANS);
        if(hint&&chipX+20+tipW<=limit-4)mid(c,tip,limit-4-tipW,cy,10.5F,FAINT,Font.PLAIN,SANS);
    }

    /** No selection: a short welcome that says what to do first. */
    private void emptyDetail(UiContext ui,List<String> active,float x,float y,float w,float h){
        var c=ui.canvas();
        panel(c,x,y,w,h);
        float mx=x+w/2,maxW=Math.min(w-48,380);
        List<String> body=c.wrapText("Pick a script from the library to run it, edit it and watch its output here.",maxW,12,Font.PLAIN,SANS);
        String note=active.isEmpty()?"New makes a script in the open folder. The examples folder has ready-made ones to try."
            :active.size()==1?basename(active.get(0))+" is still running in the background."
            :active.size()+" scripts are still running in the background.";
        List<String> notes=c.wrapText(note,maxW,10.5F,Font.PLAIN,SANS);
        boolean icon=h>=260;
        float total=(icon?46:0)+26+body.size()*17+12+notes.size()*14;
        float cy=y+Math.max(16,(h-total)/2);
        if(icon){
            c.roundRect(HOVER,mx-16,cy,32,32,4);c.strokeRoundRect(LINE,1,mx-16,cy,32,32,4);
            UiIcon.FILE.draw(c,LIME,mx-6,cy+9,14,1.3F);
            cy+=46;
        }
        c.centeredText("Pick a script",mx,cy+9,16,TEXT,Font.BOLD,SANS);cy+=26;
        for(String line:body){c.centeredText(line,mx,cy+7,12,MUTED,Font.PLAIN,SANS);cy+=17;}
        cy+=12;
        for(String line:notes){if(cy+12>y+h-8)break;c.centeredText(line,mx,cy+6,10.5F,FAINT,Font.PLAIN,SANS);cy+=14;}
    }

    private void detail(UiContext ui,float x,float y,float w,float h,boolean narrow){
        var c=ui.canvas();
        panel(c,x,y,w,h);
        boolean live=ScriptRunner.isRunning(selected);Status s=status(selected);
        boolean compact=narrow||h<340;
        float ip=compact?12:16,ix=x+ip,iw=w-ip*2,cy=y+ip;

        // Heading: file name with the truthful state badge.
        float bw=badgeW(c,s.label()),bx=x+w-ip-bw;
        badge(c,s,bx,cy+2);
        c.text(c.ellipsize(basename(selected),Math.max(30,bx-12-ix),20,Font.BOLD,SANS),ix,cy-1,20,TEXT,Font.BOLD,SANS);
        cy+=28;
        c.text(fitTail(c,"scripts/"+selected,iw,11,Font.PLAIN),ix,cy,11,MUTED,Font.PLAIN,MONO);
        cy+=compact?20:24;

        // Actions. Id "run" is always the live toggle: Run when idle, Stop while running.
        String runLabel=s.kind()==K_FINISHED||s.kind()==K_STOPPED||s.kind()==K_FAILED?"Run again":"Run";
        float ah=30,runW=ui.buttonWidth(live?"Stop":runLabel,live?UiIcon.STOP:UiIcon.PLAY,PRIMARY),editW=ui.buttonWidth("Edit",UiIcon.PEN,PRIMARY);
        if(live)ui.button("run","Stop",UiIcon.STOP,"stop",ix,cy,runW,ah,DANGER,s.kind()!=K_STOPPING);
        else ui.button("run",runLabel,UiIcon.PLAY,"run",ix,cy,runW,ah,PRIMARY,true);
        ui.button("edit","Edit",UiIcon.PEN,"edit:"+selected,ix+runW+6,cy,editW,ah,SECONDARY,true);

        // Settings for this script: how it starts by itself, and the rest behind More.
        String auto=PeripheralConfig.getAutoRun(selected);int slot=PeripheralConfig.slotOf(selected);
        String autoLabel="Auto-run: "+AUTO_LABELS[Math.max(0,Arrays.asList(AUTO_MODES).indexOf(auto))];
        String keyLabel=slot==0?"Hotkey: none":"Hotkey "+slot+keySuffix(slot);
        float autoW=ui.menuButtonWidth(autoLabel,null,SECONDARY),keyW=ui.menuButtonWidth(keyLabel,null,SECONDARY),moreW=ui.menuButtonWidth("More",null,SECONDARY);
        List<UiMenuItem> autoItems=new ArrayList<>(),keyItems=new ArrayList<>();
        for(int i=0;i<AUTO_MODES.length;i++)autoItems.add(new UiMenuItem("auto-"+AUTO_MODES[i],(AUTO_MODES[i].equals(auto)?"✓ ":"")+AUTO_LABELS[i],"autoset:"+AUTO_MODES[i]+":"+selected));
        keyItems.add(new UiMenuItem("key-0",(slot==0?"✓ ":"")+"No hotkey","hotkey:0:"+selected));
        for(int i=1;i<=5;i++){String held=PeripheralConfig.getScriptSlot(i);
            String note=held.isEmpty()||held.equals(selected)?"":" (replaces "+basename(held)+")";
            keyItems.add(new UiMenuItem("key-"+i,(slot==i?"✓ ":"")+"Slot "+i+keySuffix(i)+note,"hotkey:"+i+":"+selected));}
        List<UiMenuItem> moreItems=List.of(new UiMenuItem("build","Build with AI","build:"+selected),
            new UiMenuItem("rename","Rename…","rename:"+selected),new UiMenuItem("delete","Delete…","del:"+selected));
        float sx=ix+runW+editW+12+(compact?6:14),ax=sx;
        boolean oneRow=sx+autoW+keyW+moreW+12<=x+w-ip;
        if(!oneRow){cy+=ah+8;ax=ix;}
        if(ax+autoW+keyW+moreW+12<=x+w-ip){
            ui.menuButton("auto",autoLabel,null,ax,cy,autoW,ah,SECONDARY,true,autoItems);
            ui.menuButton("hotkey",keyLabel,null,ax+autoW+6,cy,keyW,ah,SECONDARY,true,keyItems);
            ui.menuButton("more","More",null,ax+autoW+keyW+12,cy,moreW,ah,SECONDARY,true,moreItems);
        }else{ // very narrow: everything about the script under one menu
            List<UiMenuItem> all=new ArrayList<>(autoItems.stream().map(m->new UiMenuItem(m.id(),"Auto-run: "+m.label(),m.action())).toList());
            all.addAll(keyItems.stream().map(m->new UiMenuItem(m.id(),"Hotkey: "+m.label(),m.action())).toList());
            all.addAll(moreItems);
            ui.menuButton("more","More",null,ax,cy,moreW,ah,SECONDARY,true,all);
        }
        ui.tooltip("auto","Start this script by itself when you join a world");
        ui.tooltip("hotkey",slot>0&&keySuffix(slot).isEmpty()?"Slot "+slot+" has no key yet: set it in Options › Controls › Peripheral"
            :"Toggle this script with a key; keys are set in Options › Controls");
        cy+=ah;
        String meta=s.meta();
        if(live&&s.kind()==K_RUNNING){long t=startedAt(selected);if(t>0)meta="Started "+CLOCK.format(java.time.LocalTime.ofInstant(java.time.Instant.ofEpochSecond(t),java.time.ZoneId.systemDefault()));}
        if(!meta.isEmpty()){cy+=6;mid(c,c.ellipsize(meta,iw,10.5F,Font.PLAIN,MONO),ix,cy+6,10.5F,FAINT,Font.PLAIN,MONO);cy+=12;}
        cy+=ip;

        // Inline result banner from ScriptRunner.outcome only; never from log text.
        if(s.title()!=null){
            List<String> lines=c.wrapText(s.body(),iw-20,11,Font.PLAIN,SANS);
            int max=y+h-cy<220?0:y+h-cy<300?1:2;
            if(lines.size()>max)lines=max==0?List.<String>of():trimLines(c,lines,max,iw-20);
            float bh=22+lines.size()*15+(lines.isEmpty()?0:2);
            c.fill(s.tint(),x+1,cy,w-2,bh);hline(c,LINE_SOFT,x+1,cy,w-2);
            UiIcon g=s.kind()==K_FINISHED?UiIcon.CHECK:s.kind()==K_STOPPED?UiIcon.STOP:UiIcon.WARNING;
            g.draw(c,s.color(),ix,cy+(lines.isEmpty()?bh/2-5.5F:9),11,1.3F);
            mid(c,c.ellipsize(s.title(),iw-20,12,Font.BOLD,SANS),ix+20,cy+(lines.isEmpty()?bh/2:15),12,s.color(),Font.BOLD,SANS);
            for(int i=0;i<lines.size();i++)c.text(lines.get(i),ix+20,cy+24+i*15,11,MUTED,Font.PLAIN,SANS);
            cy+=bh;
        }
        if(y+h-cy>=72)output(ui,x,cy,w,y+h-cy,live,compact);
    }

    /** " (K)" for the key bound to a hotkey slot, or "" when the slot has no key. */
    private static String keySuffix(int slot){String k=PeripheralClient.scriptKeyName(slot);return k.isEmpty()?"":" ("+k+")";}

    /** Scrollable, wrapped output tail. It follows the newest line while the reader is at the bottom. */
    private void output(UiContext ui,float x,float y,float w,float h,boolean live,boolean compact){
        var c=ui.canvas();
        float ip=compact?12:16,headH=compact?30:36,footH=compact?22:26;
        hline(c,LINE,x+1,y,w-2);
        float hy=y+headH/2;
        UiIcon.TERMINAL.draw(c,MUTED,x+ip,hy-5,10,1.3F);
        mid(c,"Output",x+ip+16,hy,12,TEXT,Font.BOLD,SANS);
        float countX=x+ip+16+c.textWidth("Output",12,Font.BOLD,SANS)+8;
        String count=logs.size()+(logs.size()==1?" line":" lines")+(errorLines>0?" · "+errorLines+" error-like":"");
        mid(c,c.ellipsize(count,Math.max(0,x+w-ip-countX),10.5F,Font.PLAIN,SANS),countX,hy,10.5F,FAINT,Font.PLAIN,SANS);

        UiRect view=new UiRect(x+1,y+headH,w-2,Math.max(1,h-headH-footH));
        c.fill(LOG,view.x(),view.y(),view.width(),view.height());
        float gutterX=view.x()+ip-4,textX=gutterX+c.textWidth("000",10,Font.PLAIN,MONO)+12,textW=view.right()-12-textX;
        int cols=Math.max(1,(int)(textW/monoAdvance(c,LOG_SIZE)));
        List<Line> lines=wrap(logs,cols);
        float contentH=lines.size()*LOG_LINE+8;

        float footY=view.bottom();
        hline(c,LINE_SOFT,x+1,footY,w-2);
        String note="Kept until the next run";
        if(lines.isEmpty()){
            mid(c,live?"Waiting for output":"No output yet",x+ip,footY+footH/2,10.5F,FAINT,Font.PLAIN,SANS);
            if(live)centered(c,"Waiting for output","The script is running but hasn't printed anything yet. Lines appear here as they arrive.",view);
            else if(status(selected).kind()==K_READY)centered(c,"No output yet","Press Run. Anything the script prints shows up here, newest at the bottom.",view);
            else centered(c,"No output captured","This run printed nothing that is still held in this session.",view);
            return;
        }
        mid(c,c.ellipsize(note,w-ip*2,10.5F,Font.PLAIN,SANS),x+ip,footY+footH/2,10.5F,FAINT,Font.PLAIN,SANS);

        ui.scroll("output",view,contentH,UiScrollFollow.WHEN_AT_END,scrolled->{
            UiCanvas sc=scrolled.canvas();
            double off=ui.scrollOffset("output");
            int first=Math.max(0,(int)((off-4)/LOG_LINE)),last=Math.min(lines.size(),(int)Math.ceil((off+view.height())/LOG_LINE)+1);
            for(int i=first;i<last;i++){
                Line l=lines.get(i);float ly=view.y()+4+i*LOG_LINE;
                if(l.kind()==3)sc.fill(0x1EF0908A,textX-6,ly,view.right()-textX,LOG_LINE);
                if(l.first()){String n=String.valueOf(l.source()+1);
                    sc.text(n,textX-12-sc.textWidth(n,10,Font.PLAIN,MONO),ly+3,10,l.kind()==3?RED:FAINT,Font.PLAIN,MONO);}
                sc.text(l.text(),textX,ly+1.5F,LOG_SIZE,LOG_COLORS[l.kind()],Font.PLAIN,MONO);
            }
        });
        // Scrolled up: new lines stop moving the view, and this bar brings it back. It sits inside
        // the viewport, which every wheel step repaints, so it appears and disappears as you scroll.
        if(!ui.scrollAtEnd("output")){
            float by=view.bottom()-BAR_H;
            c.fill(LIME_BAR,view.x(),by,view.width(),BAR_H);hline(c,0xFF3B4A2A,view.x(),by,view.width());
            float jW=ui.buttonWidth("Jump to latest",UiIcon.ARROW_DOWN,PRIMARY);
            mid(c,c.ellipsize("Newer output below",Math.max(20,view.width()-ip*2-jW-12),11,Font.PLAIN,SANS),view.x()+ip,by+BAR_H/2,11,TEXT,Font.PLAIN,SANS);
            ui.button("latest","Jump to latest",UiIcon.ARROW_DOWN,"latest",view.right()-ip-jW+4,by+4,jW,BAR_H-8,PRIMARY,true);
        }
    }

    private List<Line> wrap(List<String> source,int cols){
        if(source==wrapSource&&cols==wrapCols)return wrapped;
        List<Line> out=new ArrayList<>();int errors=0;boolean trace=false;
        for(int i=0;i<source.size();i++){
            String clean=ANSI.matcher(String.valueOf(source.get(i))).replaceAll("").replace("\t","    ").replaceAll("\\p{Cntrl}","");
            String lower=clean.toLowerCase(Locale.ROOT);int kind;
            if(lower.contains("traceback (most recent call last)")){trace=true;kind=3;}
            else if(trace&&(clean.isEmpty()||clean.startsWith(" ")))kind=3;
            else if(trace){trace=false;kind=3;} // the exception line that closes a traceback
            else if(lower.contains("error")||lower.contains("exception")||lower.startsWith("could not start"))kind=3;
            else if(lower.contains("warning")||lower.startsWith("stopping")||lower.startsWith("join a world")||lower.startsWith("log stream closed"))kind=2;
            else if(clean.startsWith("Started. Python scripts run")||clean.equals("Stopped")||clean.equals("Finished"))kind=1;
            else kind=0;
            if(kind==3)errors++;
            if(clean.isEmpty()){out.add(new Line("",i,true,kind));continue;}
            for(int at=0;at<clean.length();at+=cols)out.add(new Line(clean.substring(at,Math.min(clean.length(),at+cols)),i,at==0,kind));
        }
        wrapSource=source;wrapCols=cols;wrapped=List.copyOf(out);errorLines=errors;
        return wrapped;
    }

    private static Status status(String name){
        boolean live=ScriptRunner.isRunning(name);String o=ScriptRunner.outcome(name);
        if(live)return o.startsWith("Stopping")?new Status("Stopping",AMBER,AMBER_TINT,K_STOPPING,true,"Stop requested",null,null)
            :new Status("Running",LIME,LIME_TINT,K_RUNNING,true,"",null,null);
        if(o.equals("Ready"))return new Status("Ready",MUTED,NEUTRAL_TINT,K_READY,false,"",null,null);
        if(o.equals("Finished"))return new Status("Finished",TEXT,NEUTRAL_TINT,K_FINISHED,false,"Exit 0",
            "Finished","The last run exited normally. Its output stays below until the next run.");
        if(o.equals("Stopped"))return new Status("Stopped",MUTED,NEUTRAL_TINT,K_STOPPED,false,"",
            "Stopped","This run was stopped before it finished. Its output stays below until the next run.");
        if(o.startsWith("Stopping")||o.equals("Running"))return new Status("Ending",MUTED,NEUTRAL_TINT,K_ENDING,false,"",null,null);
        if(o.startsWith("Exited with error")){var code=EXIT_CODE.matcher(o);String n=code.find()?code.group(1):"?";
            return new Status("Failed",RED,RED_TINT,K_FAILED,true,"Exit "+n,
                "Exited with error (exit code "+n+")","Check the output below, edit the script, then run it again.");}
        if(o.startsWith("Could not start Python"))return new Status("Can't start",RED,RED_TINT,K_FAILED,true,"","Python could not start",o);
        if(o.startsWith("Join a world"))return new Status("Not started",AMBER,AMBER_TINT,K_NOTICE,false,"","Join a world first",o);
        return new Status("Error",RED,RED_TINT,K_FAILED,true,"","This script could not run",o);
    }

    private static float badgeW(UiCanvas c,String label){return Math.round(c.textWidth(label,10.5F,Font.BOLD,SANS)+29);}
    private static void badge(UiCanvas c,Status s,float x,float y){
        float w=badgeW(c,s.label()),h=22;
        c.roundRect(s.tint(),x,y,w,h,3);c.strokeRoundRect(UiCanvas.fade(s.color(),.4F),1,x,y,w,h,3);
        if(s.kind()==K_READY)c.strokeRoundRect(s.color(),1,x+9,y+8,6,6,1);else c.roundRect(s.color(),x+9,y+8,6,6,1);
        mid(c,s.label(),x+20,y+h/2,10.5F,s.color(),Font.BOLD,SANS);
    }
    private static List<String> trimLines(UiCanvas c,List<String> lines,int max,float w){
        List<String> out=new ArrayList<>(lines.subList(0,max));
        out.set(max-1,c.ellipsize(out.get(max-1)+" "+lines.get(max),w,11,Font.PLAIN,SANS));
        return out;
    }
    private static String apiState(String status){return "Ready".equals(status)?"ready":String.valueOf(status);}
    private static String parentOf(String name){int i=name.lastIndexOf('/');return i<0?"":name.substring(0,i+1);}
    private static String root(){return ScriptRunner.SCRIPTS_DIR.toString().replace('\\','/');}
}
