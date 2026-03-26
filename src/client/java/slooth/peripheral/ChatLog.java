package slooth.peripheral;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Thread-safe circular buffer that stores the last N chat messages received
 * by the client (both player chat and system/game messages).
 *
 * Exposed via GET /chat_log  (optional ?since=<epochMs>&limit=<n>)
 */
public class ChatLog {

    private static final int MAX = 200;
    private static final Deque<Entry> LOG = new ArrayDeque<>();

    public record Entry(long timeMs, String type, String sender, String text) {}

    /** Add a message to the log. type = "chat" | "system" */
    public static synchronized void add(String type, String sender, String text) {
        LOG.addLast(new Entry(System.currentTimeMillis(), type, sender, text));
        while (LOG.size() > MAX) LOG.removeFirst();
    }

    /**
     * Return messages received after sinceMs, capped at limit.
     * Pass sinceMs=0 to get all stored messages.
     */
    public static synchronized List<Entry> since(long sinceMs, int limit) {
        List<Entry> result = new ArrayList<>();
        for (Entry e : LOG) {
            if (e.timeMs() > sinceMs) result.add(e);
        }
        if (result.size() > limit) result = result.subList(result.size() - limit, result.size());
        return result;
    }

    /** Return the most recent n messages. */
    public static synchronized List<Entry> recent(int n) {
        List<Entry> all = new ArrayList<>(LOG);
        if (all.size() > n) all = all.subList(all.size() - n, all.size());
        return all;
    }
}
