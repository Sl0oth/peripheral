package slooth.peripheral;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/** Shared in-memory state. Updated by /push_log; read by PeripheralOverlay and PeripheralScreen. */
public class PeripheralStateTracker {

    public static volatile String currentTask = "";
    public static volatile String status      = "idle";
    public static volatile String lastAction  = "";
    public static volatile int    iterations  = 0;

    private static final Deque<String>    logLines = new ArrayDeque<>();
    private static final int              MAX_LOG  = 200;
    private static final DateTimeFormatter FMT     = DateTimeFormatter.ofPattern("HH:mm:ss");

    public static synchronized void pushLog(String task, String st, String action, int iter) {
        if (task   != null && !task.isEmpty())   currentTask = task;
        if (st     != null)                      status      = st;
        if (iter   >= 0)                         iterations  = iter;
        if (action != null && !action.isEmpty()) {
            lastAction = action;
            logLines.addLast(LocalTime.now().format(FMT) + " " + action);
            while (logLines.size() > MAX_LOG) logLines.removeFirst();
        }
    }

    public static synchronized List<String> getLog(int maxLines) {
        List<String> all   = new ArrayList<>(logLines);
        int          start = Math.max(0, all.size() - maxLines);
        return all.subList(start, all.size());
    }

    public static synchronized void clearLog() {
        logLines.clear();
    }
}
