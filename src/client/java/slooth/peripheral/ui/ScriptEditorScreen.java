package slooth.peripheral.ui;

import dev.pane.ui.*;

import org.lwjgl.glfw.GLFW;

import java.awt.Font;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.ArrayList;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;

import static slooth.peripheral.ui.PeripheralUi.*;

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
 *   Escape            – close (asks first when there are unsaved changes)
 *   Mouse click       – position cursor / clear selection
 *   Mouse drag        – extend selection
 *   Scroll wheel      – scroll vertically
 *
 * <p>Chrome is painted in sans through the shared Pane surface; code stays monospace. Every
 * pointer mapping uses the geometry recorded by the paint that drew it.
 */
public class ScriptEditorScreen extends UiScreen {

    // ── Code metrics (layout units) ──────────────────────────────────────────
    private static final float CODE_SIZE = 12, LINE_H = 18;

    // ── State ─────────────────────────────────────────────────────────────────
    private final Screen parent;
    private final String scriptName;
    private final Path   scriptPath;

    private final ArrayList<StringBuilder> lines = new ArrayList<>();

    // Undo
    private ArrayList<StringBuilder> undoLines   = null;
    private int                       undoCurLine = 0;
    private int                       undoCurCol  = 0;

    // Cursor
    private int     cursorLine = 0;
    private int     cursorCol  = 0;
    private int     scrollCol  = 0;   // first visible display column
    private boolean modified   = false;
    private String  statusMsg  = "";
    private int     statusTick = 0;

    // Selection: anchor stays fixed; cursor moves; selection = [anchor, cursor] or [cursor, anchor]
    private int     selAnchorLine = 0;
    private int     selAnchorCol  = 0;
    private boolean dragging      = false;

    // Pixel-accurate vertical scroll in layout units; fractional trackpad deltas accumulate here.
    private double  scrollLinePx  = 0.0;

    // Geometry recorded by the last paint (layout units).
    private UiRect  view = new UiRect(0, 0, 0, 0);
    private float   textX, advance = 7;
    private int     visibleCols = 60;

    // ── Constructor ───────────────────────────────────────────────────────────

    public ScriptEditorScreen(Screen parent, String scriptName) {
        this(new Ref<>(), parent, scriptName);
    }

    private ScriptEditorScreen(Ref<ScriptEditorScreen> ref, Screen parent, String scriptName) {
        super(PeripheralUi.document("Edit: " + scriptName, ui -> ref.v.paint(ui)), NativeOverlayRenderer.NONE,
            (UiEventHandler) e -> ref.v.onEvent(e),
            UiScreenOptions.builder().backdrop(UiBackdrop.NONE).onClick((s, e) -> ref.v.onPointer(e)).build());
        ref.v = this;
        this.parent     = parent;
        this.scriptName = scriptName;
        // ScriptPaths takes '/' on every OS and rejects '\\', so don't convert to Windows separators.
        this.scriptPath = slooth.peripheral.script.ScriptPaths.resolve(scriptName);
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

    // ── Tick ─────────────────────────────────────────────────────────────────

    @Override
    public void tick() {
        super.tick();
        if (statusTick > 0 && --statusTick == 0) invalidate(); // status line returns to the cursor readout once
    }

    // ── Events from Pane controls ─────────────────────────────────────────────

    private void onEvent(UiEvent e) {
        if (e.type() != UiEventType.ACTION) return;
        switch (e.key()) {
            case "back" -> onClose();
            case "save" -> saveFile();
            default -> {}
        }
        invalidate();
    }

    // ── Paint ─────────────────────────────────────────────────────────────────

    private void paint(UiContext ui) {
        var c = ui.canvas();
        float sw = ui.width(), sh = ui.height();
        c.fill(0x88000000, 0, 0, sw, sh);
        boolean compact = sw < 620 || sh < 420;
        float edge = compact ? 6 : 12, pad = sw >= 1100 && sh >= 640 ? 22 : compact ? 12 : 16;
        float fx = edge, fy = edge, fw = sw - edge * 2, fh = sh - edge * 2;
        frame(c, fx, fy, fw, fh);
        float cx = fx + pad, cw = fw - pad * 2, top = fy + pad;

        // Header: title + path at left; Back / Save at right.
        float saveW = btnW(c, "Save", G_CHECK, 12), backW = btnW(c, "Back", G_BACK, 11);
        action(ui, "save", "Save", "save", G_CHECK, cx + cw - saveW, top, saveW, 28, PRIMARY, LIME_INK, 12, true);
        action(ui, "back", "Back", "back", G_BACK, cx + cw - saveW - 8 - backW, top, backW, 28, SECONDARY, TEXT, 11, true);
        float titleRoom = cw - saveW - backW - 24;
        float pillW = modified ? pillW(c, "Unsaved") + 10 : 0;
        String title = c.ellipsize(basename(scriptName), Math.max(40, titleRoom - pillW), 18, Font.BOLD, SANS);
        c.text(title, cx, top + 2, 18, TEXT, Font.BOLD, SANS);
        if (modified) pill(c, "Unsaved", cx + c.textWidth(title, 18, Font.BOLD, SANS) + 10, top + 4, AMBER, AMBER_TINT);
        float y = top + 32;
        if (sh >= 360) {
            c.text(fitTail(c, "scripts/" + scriptName, cw, 11, Font.PLAIN), cx, y, 11, MUTED, Font.PLAIN, MONO);
            y += 20;
        }

        // Footer status line.
        float footH = 18, footY = fy + fh - pad - footH;
        if (statusTick > 0) {
            boolean err = statusMsg.startsWith("Error");
            glyph(c, err ? G_WARN : G_CHECK, err ? RED : LIME, cx, footY + 3, 11);
            mid(c, c.ellipsize(statusMsg, cw - 18, 11, Font.PLAIN, SANS), cx + 18, footY + footH / 2, 11, err ? RED : LIME, Font.PLAIN, SANS);
        } else {
            String pos = "Ln " + (cursorLine + 1) + ", Col " + (cursorCol + 1) + (hasSelection() ? " · selection" : "") + " · " + lines.size() + (lines.size() == 1 ? " line" : " lines");
            mid(c, c.ellipsize(pos, cw, 10.5F, Font.PLAIN, MONO), cx, footY + footH / 2, 10.5F, FAINT, Font.PLAIN, MONO);
            String keys = "Ctrl+S save · Ctrl+Z undo · Ctrl+A/C/X/V";
            float kw = c.textWidth(keys, 10.5F, Font.PLAIN, SANS);
            if (c.textWidth(pos, 10.5F, Font.PLAIN, MONO) + 24 + kw <= cw)
                mid(c, keys, cx + cw - kw, footY + footH / 2, 10.5F, FAINT, Font.PLAIN, SANS);
        }

        // Editor well with gutter.
        UiRect well = new UiRect(cx, y, cw, Math.max(LINE_H * 2, footY - 8 - y));
        c.roundRect(LOG, well.x(), well.y(), well.width(), well.height(), 3);
        c.strokeRoundRect(LINE_SOFT, 1, well.x(), well.y(), well.width(), well.height(), 3);
        advance = monoAdvance(c, CODE_SIZE);
        int digits = Math.max(3, String.valueOf(lines.size()).length());
        float gutterW = digits * monoAdvance(c, 10.5F) + 22;
        c.fill(0xFF151515, well.x() + 1, well.y() + 1, gutterW, well.height() - 2);
        vline(c, LINE_SOFT, well.x() + 1 + gutterW, well.y() + 1, well.height() - 2);
        view = new UiRect(well.x() + gutterW + 2, well.y() + 6, well.width() - gutterW - 4, well.height() - 12);
        textX = view.x() + 10;
        visibleCols = Math.max(4, (int) ((view.right() - 8 - textX) / advance));

        double maxScroll = maxScrollPx();
        scrollLinePx = Math.max(0, Math.min(scrollLinePx, maxScroll));
        int first = (int) (scrollLinePx / LINE_H);
        int last = Math.min(lines.size(), first + (int) Math.ceil(view.height() / LINE_H) + 1);
        boolean hasSel = hasSelection();
        int[] selS = hasSel ? selStart() : null, selE = hasSel ? selEnd() : null;

        c.withClip(well.x() + 1, view.y(), well.width() - 2, view.height(), () -> {
            for (int i = first; i < last; i++) {
                float ly = (float) (view.y() + i * LINE_H - scrollLinePx);
                boolean cur = i == cursorLine;
                String raw = lines.get(i).toString();
                String disp = raw.replace("\t", "    ");
                if (cur) c.fill(0xFF1A1A1A, view.x(), ly, view.width(), LINE_H);
                if (hasSel && i >= selS[0] && i <= selE[0]) {
                    int a = i == selS[0] ? rawColToDispCol(raw, selS[1]) : 0;
                    int b = i == selE[0] ? rawColToDispCol(raw, selE[1]) : disp.length() + 1;
                    float xa = colX(Math.max(a, scrollCol)), xb = colX(Math.max(b, scrollCol));
                    xa = Math.max(xa, view.x() + 2); xb = Math.min(xb, view.right() - 2);
                    if (xb > xa) c.fill(SELECTION, xa, ly + 1, xb - xa, LINE_H - 2);
                }
                String ln = String.valueOf(i + 1);
                c.text(ln, well.x() + 1 + gutterW - 10 - c.textWidth(ln, 10.5F, Font.PLAIN, MONO), ly + 3, 10.5F, cur ? LIME : FAINT, Font.PLAIN, MONO);
                if (scrollCol < disp.length()) {
                    int end = Math.min(disp.length(), scrollCol + visibleCols + 2);
                    int comment = commentStart(disp);
                    float tyy = ly + (LINE_H - CODE_SIZE * 1.25F) / 2;
                    if (comment < 0 || comment >= end) c.text(disp.substring(scrollCol, end), textX, tyy, CODE_SIZE, CODE, Font.PLAIN, MONO);
                    else {
                        int split = Math.max(comment, scrollCol);
                        if (split > scrollCol) c.text(disp.substring(scrollCol, split), textX, tyy, CODE_SIZE, CODE, Font.PLAIN, MONO);
                        c.text(disp.substring(split, end), colX(split), tyy, CODE_SIZE, MUTED, Font.PLAIN, MONO);
                    }
                }
                if (cur) {
                    int dc = rawColToDispCol(raw, cursorCol);
                    if (dc >= scrollCol) {
                        float cxp = colX(dc);
                        if (cxp < view.right() - 1) c.fill(LIME, cxp, ly + 2, 1.5F, LINE_H - 4);
                    }
                }
            }
        });

        // Vertical scroll indicator.
        double total = lines.size() * LINE_H + LINE_H;
        if (maxScroll > 0) {
            float trackH = view.height(), thumbH = (float) Math.max(18, trackH * view.height() / total);
            float thumbY = (float) (view.y() + (trackH - thumbH) * (scrollLinePx / maxScroll));
            c.roundRect(UiCanvas.fade(MUTED, .55F), well.right() - 5, thumbY, 2, thumbH, 1);
        }
    }

    private float colX(int dispCol) { return textX + (dispCol - scrollCol) * advance; }

    /** Index of a '#' that starts a comment (outside a string), or -1. */
    private static int commentStart(String s) {
        char quote = 0;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (quote != 0) { if (ch == '\\') i++; else if (ch == quote) quote = 0; }
            else if (ch == '"' || ch == '\'') quote = ch;
            else if (ch == '#') return i;
        }
        return -1;
    }

    private double maxScrollPx() {
        return Math.max(0, lines.size() * LINE_H - Math.max(LINE_H, view.height()) + LINE_H / 2);
    }

    // ── Keyboard ──────────────────────────────────────────────────────────────

    @Override
    public boolean keyPressed(KeyEvent input) {
        int     key      = input.input();
        boolean shift    = input.hasShiftDown();
        boolean ctrl     = input.hasControlDown();

        boolean handled = handleKey(key, shift, ctrl);
        if (handled) { ensureCursorVisible(); invalidate(); }
        return handled;
    }

    private boolean handleKey(int key, boolean shift, boolean ctrl) {
        if (ctrl) {
            switch (key) {
                case GLFW.GLFW_KEY_S -> { saveFile(); return true; }
                case GLFW.GLFW_KEY_Z -> { undo();     return true; }
                case GLFW.GLFW_KEY_V -> { paste();    return true; }
                case GLFW.GLFW_KEY_C -> {
                    if (hasSelection())
                        Minecraft.getInstance().keyboardHandler.setClipboard(getSelectedText());
                    return true;
                }
                case GLFW.GLFW_KEY_X -> {
                    if (hasSelection()) {
                        Minecraft.getInstance().keyboardHandler.setClipboard(getSelectedText());
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
                if (!shift) clearSelection();
                return true;
            }
            case GLFW.GLFW_KEY_END -> {
                cursorCol = lines.get(cursorLine).length();
                if (!shift) clearSelection();
                return true;
            }
            case GLFW.GLFW_KEY_PAGE_UP   -> { pageMove(-visLines()); if (!shift) clearSelection(); return true; }
            case GLFW.GLFW_KEY_PAGE_DOWN -> { pageMove(+visLines()); if (!shift) clearSelection(); return true; }
            case GLFW.GLFW_KEY_ESCAPE    -> { onClose(); return true; }
            default -> { return false; }
        }
    }

    @Override
    public boolean charTyped(CharacterEvent input) {
        if (input.isAllowedChatCharacter()) {
            char c = (char) input.codepoint();
            if (c >= 32 && c != 127) {
                saveUndo();
                if (hasSelection()) deleteSelection();
                lines.get(cursorLine).insert(cursorCol, c);
                cursorCol++;
                clearSelection();
                modified  = true;
                ensureCursorVisible();
                invalidate();
                return true;
            }
        }
        return false;
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
    }

    private void insertTab() {
        saveUndo();
        lines.get(cursorLine).insert(cursorCol, "    ");
        cursorCol += 4;
        clearSelection();
        modified   = true;
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
    }

    private void paste() {
        String clip = Minecraft.getInstance().keyboardHandler.getClipboard();
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

    /** Convert display column → nearest raw column (tabs → 4 spaces). */
    private int dispColToRawCol(String rawLine, int dispCol) {
        int d = 0;
        for (int i = 0; i < rawLine.length(); i++) {
            int w = rawLine.charAt(i) == '\t' ? 4 : 1;
            if (d + w / 2.0 >= dispCol) return i;
            d += w;
        }
        return rawLine.length();
    }

    // ── Cursor movement ───────────────────────────────────────────────────────

    private void moveLeft() {
        if (cursorCol > 0) cursorCol--;
        else if (cursorLine > 0) { cursorLine--; cursorCol = lines.get(cursorLine).length(); }
    }

    private void moveRight() {
        int len = lines.get(cursorLine).length();
        if (cursorCol < len) cursorCol++;
        else if (cursorLine < lines.size() - 1) { cursorLine++; cursorCol = 0; }
    }

    private void moveUp() {
        if (cursorLine > 0) { cursorLine--; clampCol(); }
    }

    private void moveDown() {
        if (cursorLine < lines.size() - 1) { cursorLine++; clampCol(); }
    }

    private void pageMove(int delta) {
        cursorLine   = Math.max(0, Math.min(lines.size() - 1, cursorLine + delta));
        scrollLinePx = Math.max(0, Math.min(maxScrollPx(), scrollLinePx + delta * LINE_H)); // keyboard page-jump
        clampCol();
    }

    private void clampCol() {
        cursorCol = Math.min(cursorCol, lines.get(cursorLine).length());
    }

    private int visLines() {
        return view.height() > 0 ? Math.max(1, (int) (view.height() / LINE_H)) : 23;
    }

    private void ensureCursorVisible() {
        double top = cursorLine * LINE_H, bottom = top + LINE_H;
        float viewH = view.height() > 0 ? view.height() : 23 * LINE_H;
        // Only move when navigation leaves the viewport, so wheel scrolling stays free.
        if (top < scrollLinePx) scrollLinePx = top;
        else if (bottom > scrollLinePx + viewH) scrollLinePx = bottom - viewH;
        scrollLinePx = Math.max(0, scrollLinePx);
        int dc = rawColToDispCol(lines.get(cursorLine).toString(), cursorCol);
        if (dc < scrollCol) scrollCol = Math.max(0, dc - 8);
        else if (dc > scrollCol + visibleCols - 2) scrollCol = dc - visibleCols + 2;
    }

    // ── Mouse ─────────────────────────────────────────────────────────────────

    /** Layout point → {line, col}, clamped to the document; safe while dragging outside the editor. */
    private int[] layoutToDocPos(float lx, float ly) {
        float cy = Math.max(view.y(), Math.min(ly, view.bottom() - 1));
        int ln = (int) ((cy - view.y() + scrollLinePx) / LINE_H);
        ln = Math.max(0, Math.min(ln, lines.size() - 1));
        String raw = lines.get(ln).toString();
        int dispCol = scrollCol + Math.max(0, Math.round((lx - textX) / Math.max(1, advance)));
        return new int[]{ln, dispColToRawCol(raw, dispCol)};
    }

    private boolean onPointer(UiClickEvent e) {
        if (e.nativeEvent().button() != 0) return false;
        if (e.targetId() != null || !view.contains(e.layoutX(), e.layoutY())) return false;
        int[] pos  = layoutToDocPos(e.layoutX(), e.layoutY());
        cursorLine = pos[0];
        cursorCol  = pos[1];
        // Set anchor at the click position (no selection yet)
        selAnchorLine = cursorLine;
        selAnchorCol  = cursorCol;
        dragging  = true;
        ensureCursorVisible();
        invalidate();
        return true;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent click, double dx, double dy) {
        if (dragging) {
            float s = layoutScale(), lx = (float) (click.x() * s), ly = (float) (click.y() * s);
            // Dragging past an edge scrolls one line per event.
            if (ly < view.y()) scrollLinePx = Math.max(0, scrollLinePx - LINE_H);
            else if (ly > view.bottom()) scrollLinePx = Math.min(maxScrollPx(), scrollLinePx + LINE_H);
            int[] pos  = layoutToDocPos(lx, ly);
            cursorLine = pos[0];
            cursorCol  = pos[1];
            invalidate();
            return true;
        }
        return super.mouseDragged(click, dx, dy);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent click) {
        dragging = false;
        return super.mouseReleased(click);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double hAmt, double vAmt) {
        double before = scrollLinePx; int beforeCol = scrollCol;
        scrollLinePx = Math.max(0, Math.min(scrollLinePx - vAmt * LINE_H * 1.5, maxScrollPx()));
        if (hAmt != 0) scrollCol = Math.max(0, scrollCol - (int) Math.round(hAmt * 3));
        if (before != scrollLinePx || beforeCol != scrollCol) invalidate();
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
    }

    // ── File I/O ──────────────────────────────────────────────────────────────

    private void saveFile() {
        try {
            Path safePath=slooth.peripheral.script.ScriptPaths.resolve(scriptName);
            Files.createDirectories(safePath.getParent());
            StringBuilder content = new StringBuilder();
            for (int i = 0; i < lines.size(); i++) {
                if (i > 0) content.append('\n');
                content.append(lines.get(i));
            }
            Files.writeString(safePath, content.toString(), StandardCharsets.UTF_8);
            modified   = false;
            statusMsg  = "Saved!";
            statusTick = 60;
        } catch (IOException | IllegalArgumentException e) {
            statusMsg  = "Error: " + e.getMessage();
            statusTick = 80;
        }
        invalidate();
    }

    // ── Screen overrides ──────────────────────────────────────────────────────

    @Override
    public void onClose() {
        if (!modified) { Minecraft.getInstance().gui.setScreen(parent); return; }
        // Cancel/Escape in the sheet returns to this same editor instance, so the buffer,
        // undo snapshot, cursor and scroll survive untouched.
        Minecraft.getInstance().gui.setScreen(new UnsavedChangesScreen(this,
            () -> {
                saveFile();
                Minecraft.getInstance().gui.setScreen(modified ? this : parent); // failure: back to the editor with the error shown
            },
            () -> Minecraft.getInstance().gui.setScreen(parent)));
    }

    @Override
    public boolean isPauseScreen() { return false; }
}
