package slooth.peripheral.util;

import slooth.peripheral.overlay.PeripheralOverlay;
import slooth.peripheral.ui.PeripheralScreen;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/** Shared in-memory state. Updated by /push_log and running scripts; read by PeripheralOverlay and PeripheralScreen. */
public class PeripheralStateTracker {

    /** One log line, stamped with its time, and whether the external agent sent it rather than a script. */
    public record Entry(String text, boolean agent) {}

    public static volatile String currentTask = "";
    public static volatile String status      = "idle";
    public static volatile String lastAction  = "";
    public static volatile int    iterations  = 0;

    private static final Deque<Entry>     logLines = new ArrayDeque<>();
    private static final int              MAX_LOG  = 200;
    private static final DateTimeFormatter FMT     = DateTimeFormatter.ofPattern("HH:mm:ss");

    public static synchronized void pushLog(String task, String st, String action, int iter) {
        if (task   != null && !task.isEmpty())   currentTask = task;
        if (st     != null)                      status      = st;
        if (iter   >= 0)                         iterations  = iter;
        if (action != null && !action.isEmpty()) {
            lastAction = action;
            add(action, true);
        }
    }

    /** A line from a running script, or about one starting or stopping. */
    public static synchronized void pushScriptLog(String line) {
        add(line, false);
    }

    private static void add(String line, boolean agent) {
        logLines.addLast(new Entry(LocalTime.now().format(FMT) + " " + line, agent));
        while (logLines.size() > MAX_LOG) logLines.removeFirst();
    }

    public static synchronized List<String> getLog(int maxLines) {
        return getEntries(maxLines).stream().map(Entry::text).toList();
    }

    public static synchronized List<Entry> getEntries(int maxLines) {
        List<Entry> all   = new ArrayList<>(logLines);
        int         start = Math.max(0, all.size() - maxLines);
        return List.copyOf(all.subList(start, all.size()));
    }

    public static synchronized void clearLog() {
        logLines.clear();
    }
}
