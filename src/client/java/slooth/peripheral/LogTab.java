package slooth.peripheral;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Util;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

class LogTab {

    private final PeripheralScreen host;
    private int px, contentY, contentH;

    int    scroll   = 0;
    double scrollPx = 0.0;

    private final Map<Integer, String> urlByY = new HashMap<>();
    private int copiedTick = 0;
    private TextFieldWidget taskInput;

    private static final Pattern  URL_PAT   = Pattern.compile("https?://[^\\s§]+");
    private static final int      C_URL     = 0xFF4499FF;
    private static final int      C_URL_HOV = 0xFF88CCFF;
    private static final HttpClient HTTP    = HttpClient.newHttpClient();

    LogTab(PeripheralScreen host) { this.host = host; }

    void build(int px, int py, int contentY, int contentH) {
        this.px = px; this.contentY = contentY; this.contentH = contentH;

        int by = py + PeripheralScreen.H - PeripheralScreen.BH + 5;
        taskInput = new TextFieldWidget(host.tr(), px + 48, by, PeripheralScreen.W - 124, 14, Text.empty());
        taskInput.setMaxLength(256);
        taskInput.setPlaceholder(Text.literal("Send task to AI agent…"));
        taskInput.setFocusUnlocked(false);
        host.widgetAdd(taskInput);

        host.widgetAdd(PeripheralScreen.btn("Go",    px + PeripheralScreen.W - 74, by - 1, 30, 16, b -> sendTask()));
        host.widgetAdd(PeripheralScreen.btn("Stop",  px + PeripheralScreen.W - 42, by - 1, 38, 16, b -> sendStop()));
        host.widgetAdd(PeripheralScreen.btn("Clear", px + 4, by - 1, 40, 16, b -> {
            PeripheralStateTracker.clearLog(); scroll = 0; scrollPx = 0;
        }));
    }

    void render(DrawContext ctx, int mx, int my) {
        urlByY.clear();

        List<String> log  = PeripheralStateTracker.getLog(80);
        int lineH = 9, vis = (contentH - 4) / lineH;
        int maxScr = Math.max(0, log.size() - vis);
        scroll = Math.min(scroll, maxScr);
        int start = Math.max(0, log.size() - vis - scroll);
        int end   = Math.min(log.size(), start + vis);

        boolean inLog = mx >= px + 2 && mx < px + PeripheralScreen.W - 2;

        for (int i = start; i < end; i++) {
            String  line      = log.get(i);
            int     ty        = contentY + 3 + (i - start) * lineH;
            int     baseColor = logColor(line);
            boolean hovered   = inLog && my >= ty && my < ty + lineH;

            if (hovered)
                ctx.fill(px + 2, ty - 1, px + PeripheralScreen.W - 2, ty + lineH - 1, 0x22FFFFFF);

            Matcher m = URL_PAT.matcher(line);
            if (m.find()) {
                String url    = m.group();
                urlByY.put(ty, url);
                int    urlCol = hovered ? C_URL_HOV : C_URL;
                String before = line.substring(0, m.start());
                String after  = line.substring(m.end());

                int x = px + 4;
                if (!before.isEmpty()) {
                    ctx.drawText(host.tr(), Text.literal(before), x, ty, baseColor, false);
                    x += host.tr().getWidth(before);
                }
                ctx.drawText(host.tr(), Text.literal(url), x, ty, urlCol, false);
                int uw = host.tr().getWidth(url);
                ctx.fill(x, ty + lineH - 1, x + uw, ty + lineH, urlCol);
                if (!after.isEmpty())
                    ctx.drawText(host.tr(), Text.literal(after), x + uw, ty, baseColor, false);
            } else {
                ctx.drawText(host.tr(), Text.literal(line), px + 4, ty, baseColor, false);
            }
        }

        if (copiedTick > 0)
            ctx.drawCenteredTextWithShadow(host.tr(), Text.literal("§aCopied!"),
                px + PeripheralScreen.W / 2, contentY + contentH - 10, PeripheralScreen.C_GREEN);
    }

    void tick() { if (copiedTick > 0) copiedTick--; }

    boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0) return false;
        double mx = mouseX, my = mouseY;
        if (mx < px + 2 || mx >= px + PeripheralScreen.W - 2) return false;

        for (Map.Entry<Integer, String> entry : urlByY.entrySet()) {
            int rowY = entry.getKey();
            if (my >= rowY && my < rowY + 10) {
                try { Util.getOperatingSystem().open(URI.create(entry.getValue())); }
                catch (Exception ignored) {}
                return true;
            }
        }

        // Copy line text to clipboard
        List<String> log = PeripheralStateTracker.getLog(80);
        int lineH = 9, vis = (contentH - 4) / lineH;
        int start   = Math.max(0, log.size() - vis - scroll);
        int relLine = ((int) my - contentY - 3) / lineH;
        int absLine = start + relLine;
        if (relLine >= 0 && absLine < log.size()) {
            host.mc().keyboard.setClipboard(log.get(absLine));
            copiedTick = 40;
            return true;
        }
        return false;
    }

    boolean mouseScrolled(double mx, double my, double hAmt, double vAmt) {
        int lineH = 9;
        List<String> log = PeripheralStateTracker.getLog(80);
        int vis    = (contentH - 4) / lineH;
        int maxScr = Math.max(0, log.size() - vis);
        scrollPx = Math.max(0, Math.min(scrollPx - vAmt * (lineH * 0.5), maxScr * lineH));
        scroll   = (int)(scrollPx / lineH);
        return true;
    }

    void reset() { scroll = 0; scrollPx = 0; }

    // ── Agent communication ───────────────────────────────────────────────────

    private void sendTask() {
        if (taskInput == null) return;
        String t = taskInput.getText().trim();
        if (t.isEmpty()) return;
        agentPost("/task", "{\"task\":\"" + esc(t) + "\"}");
        taskInput.setText("");
    }

    private void sendStop() { agentPost("/stop", "{\"type\":\"stop\"}"); }

    private void agentPost(String path, String json) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(PeripheralConfig.agentUrl + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
            HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString());
        } catch (Exception ignored) {}
    }

    private static String esc(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }

    private static int logColor(String l) {
        if (l.contains("BARITONE"))                            return PeripheralScreen.C_CYAN;
        if (l.contains("CRAFT"))                               return PeripheralScreen.C_YELLOW;
        if (l.contains("DONE"))                                return 0xFF00FF00;
        if (l.contains("ERROR") || l.contains("FAIL"))        return PeripheralScreen.C_RED;
        if (l.contains("WAIT"))                                return 0xFFFFFF00;
        if (l.contains("SCRIPT") || l.contains("script"))     return 0xFFAAEEFF;
        if (l.contains("STARTING") || l.contains("starting")) return 0xFF88FFAA;
        return PeripheralScreen.C_WHITE;
    }
}
