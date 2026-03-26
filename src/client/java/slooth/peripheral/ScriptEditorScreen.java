package slooth.peripheral;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.input.CharInput;
import net.minecraft.client.input.KeyInput;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.ArrayList;

/**
 * In-game multi-line script editor.
 *
 * Controls:
 *   Arrow keys / Home / End / PageUp / PageDown  – navigate
 *   Shift + Arrow / Home / End                   – extend selection
 *   Ctrl+A                                       – select all
 *   Ctrl+C                                       – copy selection
 *   Ctrl+X                                       – cut selection
 *   Tab               – insert 4 spaces
 *   Enter             – newline with auto-indent
 *   Backspace / Del   – delete (or delete selection)
 *   Ctrl+S            – save
 *   Ctrl+Z            – undo
 *   Ctrl+V            – paste (replaces selection if active)
 *   Escape            – close
 *   Mouse click       – position cursor / clear selection
 *   Mouse drag        – extend selection
 *   Scroll wheel      – scroll vertically
 */
public class ScriptEditorScreen extends Screen {

    // ── Layout ────────────────────────────────────────────────────────────────
    private static final int W      = 404;
    private static final int H      = 252;
    private static final int TH     = 14;
    private static final int BH     = 26;
    private static final int GUTTER = 28;
    private static final int LINE_H = 9;

    // ── Colours ───────────────────────────────────────────────────────────────
    private static final int C_PANEL   = 0xEE0D0D0D;
    private static final int C_TITLE   = 0xDD0E2B1C;
    private static final int C_CONTENT = 0xCC090909;
    private static final int C_BORDER  = 0xFF153020;
    private static final int C_GUTTER  = 0xCC050505;
    private static final int C_GREEN   = 0xFF00FFAA;
    private static final int C_WHITE   = 0xFFDDDDDD;
    private static final int C_GREY    = 0xFF888888;
    private static final int C_DIM     = 0xFF444444;
    private static final int C_RED     = 0xFFFF4444;
    private static final int C_CURSOR  = 0xFFAAFFCC;
    private static final int C_LINENO  = 0xFF3D5E3D;
    private static final int C_CURLINE = 0xCC0D1A0D;
    private static final int C_SEL     = 0x554499FF;  // selection highlight

    // ── State ─────────────────────────────────────────────────────────────────
    private final Screen parent;
    private final String scriptName;
    private final Path   scriptPath;

    private int px, py, contentY, contentH, editorX, editorW;

    private final ArrayList<StringBuilder> lines = new ArrayList<>();

    // Undo
    private ArrayList<StringBuilder> undoLines   = null;
    private int                       undoCurLine = 0;
    private int                       undoCurCol  = 0;

    // Cursor
    private int     cursorLine = 0;
    private int     cursorCol  = 0;
    private int     scrollLine = 0;
    private int     scrollCol  = 0;
    private int     blinkTick  = 0;
    private boolean modified   = false;
    private String  statusMsg  = "";
    private int     statusTick = 0;

    // Selection: anchor stays fixed; cursor moves; selection = [anchor, cursor] or [cursor, anchor]
    private int     selAnchorLine = 0;
    private int     selAnchorCol  = 0;
    private boolean dragging      = false;

    // Pixel-accurate vertical scroll position — same approach as MC's EntryListWidget.
    // Fractional deltas from Mac trackpad accumulate here without integer truncation.
    private double  scrollLinePx  = 0.0;

    // ── Constructor ───────────────────────────────────────────────────────────

    public ScriptEditorScreen(Screen parent, String scriptName) {
        super(Text.literal("Edit: " + scriptName));
        this.parent     = parent;
        this.scriptName = scriptName;
        this.scriptPath = ScriptRunner.SCRIPTS_DIR.resolve(
            scriptName.replace('/', java.io.File.separatorChar));
        loadFile();
    }

    private void loadFile() {
        lines.clear();
        try {
            if (Files.exists(scriptPath)) {
                String raw = Files.readString(scriptPath, StandardCharsets.UTF_8);
                raw = raw.replace("\r\n", "\n").replace("\r", "\n");
                for (String part : raw.split("\n", -1))
                    lines.add(new StringBuilder(part));
            }
        } catch (IOException e) {
            lines.add(new StringBuilder("# Error loading file: " + e.getMessage()));
        }
        if (lines.isEmpty()) lines.add(new StringBuilder());
    }

    // ── Init ─────────────────────────────────────────────────────────────────

    @Override
    protected void init() {
        px       = (width  - W) / 2;
        py       = (height - H) / 2;
        contentY = py + TH;
        contentH = H - TH - BH;
        editorX  = px + 1 + GUTTER;
        editorW  = W - 2 - GUTTER;

        addDrawableChild(ButtonWidget.builder(Text.literal("← Back"), b -> close())
            .dimensions(px + 4, py + H - BH + 6, 48, 14).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Save"), b -> saveFile())
            .dimensions(px + W - 52, py + H - BH + 6, 48, 14).build());
    }

    // ── Tick ─────────────────────────────────────────────────────────────────

    @Override
    public void tick() {
        super.tick();
        blinkTick++;
        if (statusTick > 0) statusTick--;
        // NOTE: ensureCursorVisible() is NOT called here.
        // It is only called after keyboard/click events that move the cursor.
        // Calling it every tick would cancel free scroll-wheel scrolling.
    }

    // ── Render ────────────────────────────────────────────────────────────────

    @Override
    public void renderBackground(DrawContext ctx, int mouseX, int mouseY, float delta) {
        // Suppress blur — applyBlur() can only fire once per frame.
    }

    @Override
    public void render(DrawContext ctx, int mx, int my, float delta) {
        ctx.fill(0, 0, width, height, 0x88000000);
        ctx.fill(px, py, px + W, py + H, C_PANEL);
        ctx.fill(px, py, px + W, py + TH, C_TITLE);
        ctx.drawText(textRenderer,
            Text.literal("§a■ §fEDIT  §7" + scriptName + (modified ? "  §e[modified]" : "")),
            px + 4, py + 3, C_GREEN, false);

        ctx.fill(px + 1, contentY, px + W - 1, contentY + contentH, C_CONTENT);
        ctx.fill(px + 1, contentY, px + 1 + GUTTER, contentY + contentH, C_GUTTER);
        ctx.fill(px + 1 + GUTTER, contentY, px + 2 + GUTTER, contentY + contentH, C_BORDER);
        ctx.fill(px, contentY + contentH, px + W, contentY + contentH + 1, C_BORDER);

        int vl    = visLines();
        int endLn = Math.min(scrollLine + vl, lines.size());

        // Pre-compute selection bounds once per frame
        boolean hasSel  = hasSelection();
        int[]   selS    = hasSel ? selStart() : null;
        int[]   selE    = hasSel ? selEnd()   : null;

        for (int i = scrollLine; i < endLn; i++) {
            int     ty        = contentY + (i - scrollLine) * LINE_H + 1;
            boolean isCurLine = (i == cursorLine);
            String  rawLine   = lines.get(i).toString();
            String  dispLine  = rawLine.replace("\t", "    ");

            // Current-line highlight
            if (isCurLine)
                ctx.fill(px + 2 + GUTTER, ty - 1, px + W - 1, ty + LINE_H - 1, C_CURLINE);

            // Selection highlight
            if (hasSel && i >= selS[0] && i <= selE[0]) {
                int hscDisp = (i == selS[0]) ? rawColToDispCol(rawLine, selS[1]) : 0;
                int hecDisp = (i == selE[0]) ? rawColToDispCol(rawLine, selE[1]) : dispLine.length() + 1;

                int xS = dispColToX(dispLine, hscDisp);
                int xE = (i < selE[0] && hecDisp > dispLine.length())
                       ? px + W - 2
                       : dispColToX(dispLine, hecDisp);
                xS = Math.max(xS, editorX + 2);
                xE = Math.min(xE, px + W - 2);
                if (xE > xS)
                    ctx.fill(xS, ty - 1, xE, ty + LINE_H - 1, C_SEL);
            }

            // Line number
            String lnStr = String.valueOf(i + 1);
            int    lnX   = px + 1 + GUTTER - textRenderer.getWidth(lnStr) - 3;
            ctx.drawText(textRenderer, Text.literal(lnStr), lnX, ty,
                isCurLine ? C_GREEN : C_LINENO, false);

            // Line text
            if (scrollCol < dispLine.length()) {
                String visible = dispLine.substring(scrollCol);
                int maxC = editorW / 6 + 2;
                if (visible.length() > maxC) visible = visible.substring(0, maxC);
                ctx.drawText(textRenderer, Text.literal(visible), editorX + 2, ty, C_WHITE, false);
            }

            // Blinking cursor
            if (isCurLine && (blinkTick / 10) % 2 == 0 && cursorCol >= scrollCol) {
                int safeCol = Math.min(cursorCol, dispLine.length());
                String bef  = dispLine.substring(scrollCol, safeCol);
                int    cx   = editorX + 2 + textRenderer.getWidth(bef);
                if (cx < px + W - 1)
                    ctx.fill(cx, ty - 1, cx + 1, ty + LINE_H - 1, C_CURSOR);
            }
        }

        // Bottom status strip
        if (statusTick > 0) {
            ctx.drawText(textRenderer, Text.literal(statusMsg),
                px + 60, py + H - BH + 9,
                statusMsg.startsWith("Error") ? C_RED : C_GREEN, false);
        } else {
            String selInfo = hasSel ? "  |  [sel]" : "";
            ctx.drawText(textRenderer,
                Text.literal("Ln " + (cursorLine + 1) + "  Col " + (cursorCol + 1) + selInfo
                    + "  |  Ctrl+S  Ctrl+Z  Ctrl+A  Ctrl+C/X"),
                px + 60, py + H - BH + 9, C_DIM, false);
        }

        super.render(ctx, mx, my, delta);
    }

    // ── Keyboard ──────────────────────────────────────────────────────────────

    @Override
    public boolean keyPressed(KeyInput input) {
        int     key      = input.getKeycode();
        boolean shift    = input.hasShift();
        boolean ctrl     = input.hasCtrl();

        if (ctrl) {
            switch (key) {
                case GLFW.GLFW_KEY_S -> { saveFile(); return true; }
                case GLFW.GLFW_KEY_Z -> { undo();     return true; }
                case GLFW.GLFW_KEY_V -> { paste();    return true; }
                case GLFW.GLFW_KEY_C -> {
                    if (hasSelection())
                        MinecraftClient.getInstance().keyboard.setClipboard(getSelectedText());
                    return true;
                }
                case GLFW.GLFW_KEY_X -> {
                    if (hasSelection()) {
                        MinecraftClient.getInstance().keyboard.setClipboard(getSelectedText());
                        saveUndo();
                        deleteSelection();
                    }
                    return true;
                }
                case GLFW.GLFW_KEY_A -> {
                    // Select all
                    selAnchorLine = 0;
                    selAnchorCol  = 0;
                    cursorLine    = lines.size() - 1;
                    cursorCol     = lines.get(cursorLine).length();
                    blinkTick     = 0;
                    return true;
                }
                default -> {}
            }
        }

        switch (key) {
            case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> {
                if (hasSelection()) { saveUndo(); deleteSelection(); }
                insertNewline(); return true;
            }
            case GLFW.GLFW_KEY_BACKSPACE -> { backspace(); return true; }
            case GLFW.GLFW_KEY_DELETE    -> { delete();    return true; }
            case GLFW.GLFW_KEY_TAB       -> {
                if (hasSelection()) { saveUndo(); deleteSelection(); }
                insertTab(); return true;
            }
            case GLFW.GLFW_KEY_LEFT -> {
                if (shift) {
                    moveLeft();
                } else {
                    if (hasSelection()) { int[] s = selStart(); cursorLine = s[0]; cursorCol = s[1]; }
                    else moveLeft();
                    clearSelection();
                }
                return true;
            }
            case GLFW.GLFW_KEY_RIGHT -> {
                if (shift) {
                    moveRight();
                } else {
                    if (hasSelection()) { int[] e = selEnd(); cursorLine = e[0]; cursorCol = e[1]; }
                    else moveRight();
                    clearSelection();
                }
                return true;
            }
            case GLFW.GLFW_KEY_UP -> {
                moveUp();
                if (!shift) clearSelection();
                return true;
            }
            case GLFW.GLFW_KEY_DOWN -> {
                moveDown();
                if (!shift) clearSelection();
                return true;
            }
            case GLFW.GLFW_KEY_HOME -> {
                cursorCol = 0;
                blinkTick = 0;
                if (!shift) clearSelection();
                return true;
            }
            case GLFW.GLFW_KEY_END -> {
                cursorCol = lines.get(cursorLine).length();
                blinkTick = 0;
                if (!shift) clearSelection();
                return true;
            }
            case GLFW.GLFW_KEY_PAGE_UP   -> { pageMove(-visLines()); if (!shift) clearSelection(); return true; }
            case GLFW.GLFW_KEY_PAGE_DOWN -> { pageMove(+visLines()); if (!shift) clearSelection(); return true; }
            case GLFW.GLFW_KEY_ESCAPE    -> { close(); return true; }
            default -> {}
        }

        ensureCursorVisible();
        return super.keyPressed(input);
    }

    @Override
    public boolean charTyped(CharInput input) {
        if (input.isValidChar()) {
            char c = (char) input.codepoint();
            if (c >= 32 && c != 127) {
                saveUndo();
                if (hasSelection()) deleteSelection();
                lines.get(cursorLine).insert(cursorCol, c);
                cursorCol++;
                clearSelection();
                modified  = true;
                blinkTick = 0;
                ensureCursorVisible();
                return true;
            }
        }
        return super.charTyped(input);
    }

    // ── Edit operations ───────────────────────────────────────────────────────

    private void insertNewline() {
        saveUndo();
        StringBuilder cur = lines.get(cursorLine);
        String s = cur.toString();
        int indent = 0;
        while (indent < s.length() && s.charAt(indent) == ' ') indent++;
        String after = cur.substring(cursorCol);
        cur.delete(cursorCol, cur.length());
        StringBuilder next = new StringBuilder();
        for (int i = 0; i < indent; i++) next.append(' ');
        next.append(after);
        lines.add(cursorLine + 1, next);
        cursorLine++;
        cursorCol = indent;
        clearSelection();
        modified  = true;
        blinkTick = 0;
    }

    private void insertTab() {
        saveUndo();
        lines.get(cursorLine).insert(cursorCol, "    ");
        cursorCol += 4;
        clearSelection();
        modified   = true;
        blinkTick  = 0;
    }

    private void backspace() {
        if (hasSelection()) { saveUndo(); deleteSelection(); return; }
        saveUndo();
        if (cursorCol > 0) {
            lines.get(cursorLine).deleteCharAt(cursorCol - 1);
            cursorCol--;
        } else if (cursorLine > 0) {
            String rest = lines.remove(cursorLine).toString();
            cursorLine--;
            cursorCol = lines.get(cursorLine).length();
            lines.get(cursorLine).append(rest);
        }
        clearSelection();
        modified  = true;
        blinkTick = 0;
    }

    private void delete() {
        if (hasSelection()) { saveUndo(); deleteSelection(); return; }
        saveUndo();
        StringBuilder cur = lines.get(cursorLine);
        if (cursorCol < cur.length()) {
            cur.deleteCharAt(cursorCol);
        } else if (cursorLine < lines.size() - 1) {
            cur.append(lines.remove(cursorLine + 1));
        }
        modified  = true;
        blinkTick = 0;
    }

    private void paste() {
        String clip = MinecraftClient.getInstance().keyboard.getClipboard();
        if (clip == null || clip.isEmpty()) return;
        saveUndo();
        if (hasSelection()) deleteSelection();
        clip = clip.replace("\r\n", "\n").replace("\r", "\n").replace("\t", "    ");
        String[] parts = clip.split("\n", -1);
        lines.get(cursorLine).insert(cursorCol, parts[0]);
        cursorCol += parts[0].length();
        for (int p = 1; p < parts.length; p++) {
            String tail = lines.get(cursorLine).substring(cursorCol);
            lines.get(cursorLine).delete(cursorCol, lines.get(cursorLine).length());
            StringBuilder newLine = new StringBuilder(parts[p]).append(tail);
            lines.add(cursorLine + 1, newLine);
            cursorLine++;
            cursorCol = parts[p].length();
        }
        clearSelection();
        modified  = true;
        blinkTick = 0;
    }

    // ── Selection helpers ─────────────────────────────────────────────────────

    private boolean hasSelection() {
        return selAnchorLine != cursorLine || selAnchorCol != cursorCol;
    }

    private void clearSelection() {
        selAnchorLine = cursorLine;
        selAnchorCol  = cursorCol;
    }

    /** Returns true if (l1,c1) comes before (l2,c2) in the document. */
    private boolean posLess(int l1, int c1, int l2, int c2) {
        return l1 < l2 || (l1 == l2 && c1 < c2);
    }

    private int[] selStart() {
        return posLess(selAnchorLine, selAnchorCol, cursorLine, cursorCol)
            ? new int[]{selAnchorLine, selAnchorCol}
            : new int[]{cursorLine,    cursorCol};
    }

    private int[] selEnd() {
        return posLess(selAnchorLine, selAnchorCol, cursorLine, cursorCol)
            ? new int[]{cursorLine,    cursorCol}
            : new int[]{selAnchorLine, selAnchorCol};
    }

    private String getSelectedText() {
        if (!hasSelection()) return "";
        int[] s = selStart(), e = selEnd();
        if (s[0] == e[0]) return lines.get(s[0]).substring(s[1], e[1]);
        StringBuilder sb = new StringBuilder(lines.get(s[0]).substring(s[1]));
        for (int i = s[0] + 1; i < e[0]; i++) sb.append('\n').append(lines.get(i));
        sb.append('\n').append(lines.get(e[0]).substring(0, e[1]));
        return sb.toString();
    }

    private void deleteSelection() {
        if (!hasSelection()) return;
        int[] s = selStart(), e = selEnd();
        if (s[0] == e[0]) {
            lines.get(s[0]).delete(s[1], e[1]);
        } else {
            String tail = lines.get(e[0]).substring(e[1]);
            for (int i = e[0]; i > s[0]; i--) lines.remove(i);
            lines.get(s[0]).delete(s[1], lines.get(s[0]).length()).append(tail);
        }
        cursorLine = s[0];
        cursorCol  = s[1];
        clearSelection();
        modified = true;
    }

    /** Convert raw-text column → display column (tabs → 4 spaces). */
    private int rawColToDispCol(String rawLine, int rawCol) {
        rawCol = Math.min(rawCol, rawLine.length());
        return rawLine.substring(0, rawCol).replace("\t", "    ").length();
    }

    /** Convert display column → pixel x, clamped to the editor area. */
    private int dispColToX(String dispLine, int dispCol) {
        int sc  = Math.max(scrollCol, 0);
        int col = Math.max(dispCol, sc);
        col     = Math.min(col, dispLine.length());
        return editorX + 2 + textRenderer.getWidth(dispLine.substring(sc, col));
    }

    // ── Cursor movement ───────────────────────────────────────────────────────

    private void moveLeft() {
        if (cursorCol > 0) cursorCol--;
        else if (cursorLine > 0) { cursorLine--; cursorCol = lines.get(cursorLine).length(); }
        blinkTick = 0;
    }

    private void moveRight() {
        int len = lines.get(cursorLine).length();
        if (cursorCol < len) cursorCol++;
        else if (cursorLine < lines.size() - 1) { cursorLine++; cursorCol = 0; }
        blinkTick = 0;
    }

    private void moveUp() {
        if (cursorLine > 0) { cursorLine--; clampCol(); }
        blinkTick = 0;
    }

    private void moveDown() {
        if (cursorLine < lines.size() - 1) { cursorLine++; clampCol(); }
        blinkTick = 0;
    }

    private void pageMove(int delta) {
        cursorLine   = Math.max(0, Math.min(lines.size() - 1, cursorLine + delta));
        scrollLine   = Math.max(0, Math.min(Math.max(0, lines.size() - visLines()), scrollLine + delta));
        scrollLinePx = scrollLine * LINE_H; // keyboard page-jump: snap pixel pos
        clampCol();
        blinkTick    = 0;
    }

    private void clampCol() {
        cursorCol = Math.min(cursorCol, lines.get(cursorLine).length());
    }

    private int visLines() {
        return contentH > 0 ? contentH / LINE_H : 23;
    }

    private void ensureCursorVisible() {
        int vl = visLines();
        int prevScrollLine = scrollLine;
        if (cursorLine < scrollLine)        scrollLine = cursorLine;
        if (cursorLine >= scrollLine + vl)  scrollLine = cursorLine - vl + 1;
        scrollLine = Math.max(0, scrollLine);
        // Only reset the pixel accumulator when cursor navigation forces a scroll jump.
        // If we reset it every tick, Mac trackpad fractional deltas get erased 20×/s.
        if (scrollLine != prevScrollLine) {
            scrollLinePx = scrollLine * LINE_H;
        }
        if (cursorCol < scrollCol) {
            scrollCol = Math.max(0, cursorCol - 8);
        } else {
            String lineStr = lines.get(cursorLine).toString();
            int safeCol = Math.min(cursorCol, lineStr.length());
            if (scrollCol <= safeCol) {
                String vis = lineStr.substring(scrollCol, safeCol);
                while (textRenderer.getWidth(vis) > editorW - 12 && scrollCol < safeCol) {
                    scrollCol++;
                    vis = lineStr.substring(scrollCol, safeCol);
                }
            }
        }
    }

    // ── Mouse ─────────────────────────────────────────────────────────────────

    /**
     * Converts a screen pixel position to a {line, col} document position.
     * Clamps to the valid editor content area — safe to call during drag even
     * when the mouse has drifted outside the editor box.
     */
    private int[] mouseToDocPos(double mx, double my) {
        // Clamp vertically to editor content
        double cmy = Math.max(contentY, Math.min(my, contentY + contentH - 1));
        int ln = scrollLine + (int)((cmy - contentY) / LINE_H);
        ln = Math.max(0, Math.min(ln, lines.size() - 1));

        String raw  = lines.get(ln).toString();
        int    relX = (int)(mx - editorX - 2);
        int    col  = scrollCol;
        for (int c = scrollCol; c <= raw.length(); c++) {
            if (textRenderer.getWidth(raw.substring(scrollCol, c)) > relX) break;
            col = c;
        }
        return new int[]{ln, col};
    }

    @Override
    public boolean mouseClicked(Click click, boolean focused) {
        double mx = click.x(), my = click.y();
        if (mx >= editorX && mx < px + W - 1 && my >= contentY && my < contentY + contentH) {
            int[] pos  = mouseToDocPos(mx, my);
            cursorLine = pos[0];
            cursorCol  = pos[1];
            // Set anchor at the click position (no selection yet)
            selAnchorLine = cursorLine;
            selAnchorCol  = cursorCol;
            dragging  = true;
            blinkTick = 0;
            ensureCursorVisible();
            return true;
        }
        return super.mouseClicked(click, focused);
    }

    @Override
    public boolean mouseDragged(Click click, double dx, double dy) {
        if (dragging) {
            int[] pos  = mouseToDocPos(click.x(), click.y());
            cursorLine = pos[0];
            cursorCol  = pos[1];
            blinkTick  = 0;
            return true;
        }
        return super.mouseDragged(click, dx, dy);
    }

    @Override
    public boolean mouseReleased(Click click) {
        dragging = false;
        return super.mouseReleased(click);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double hAmt, double vAmt) {
        // Mirrors Minecraft's EntryListWidget:
        //   setScrollAmount(scrollAmount - verticalAmount * itemHeight / 2)
        // LINE_H = 9 px, so scale = 9 * 0.5 = 4.5
        int maxScroll = Math.max(0, lines.size() - visLines());
        scrollLinePx  = Math.max(0, Math.min(scrollLinePx - vAmt * (LINE_H * 0.5), maxScroll * LINE_H));
        scrollLine    = (int)(scrollLinePx / LINE_H);
        return true;
    }

    // ── Undo ─────────────────────────────────────────────────────────────────

    private void saveUndo() {
        undoLines = new ArrayList<>(lines.size());
        for (StringBuilder sb : lines) undoLines.add(new StringBuilder(sb));
        undoCurLine = cursorLine;
        undoCurCol  = cursorCol;
    }

    private void undo() {
        if (undoLines == null) return;
        lines.clear();
        lines.addAll(undoLines);
        cursorLine = Math.min(undoCurLine, lines.size() - 1);
        cursorCol  = Math.min(undoCurCol,  lines.get(cursorLine).length());
        clearSelection();
        undoLines  = null;
        blinkTick  = 0;
    }

    // ── File I/O ──────────────────────────────────────────────────────────────

    private void saveFile() {
        try {
            Files.createDirectories(scriptPath.getParent());
            StringBuilder content = new StringBuilder();
            for (int i = 0; i < lines.size(); i++) {
                if (i > 0) content.append('\n');
                content.append(lines.get(i));
            }
            Files.writeString(scriptPath, content.toString(), StandardCharsets.UTF_8);
            modified   = false;
            statusMsg  = "Saved!";
            statusTick = 60;
        } catch (IOException e) {
            statusMsg  = "Error: " + e.getMessage();
            statusTick = 80;
        }
    }

    // ── Screen overrides ──────────────────────────────────────────────────────

    @Override
    public void close() {
        MinecraftClient.getInstance().setScreen(parent);
    }

    @Override
    public boolean shouldPause() { return false; }
}
