package src.Server;

import java.util.HashMap;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

public class ActionLog {
    private Map<Integer, ActionObject> log = new HashMap<>();
    private int capacity;

    public ActionLog(int capacity) {
        this.capacity = capacity;
    }

    public void clear() {
        log.clear();
    }

    public boolean containsTimestamp(int timestamp) {
        return log.containsKey(timestamp);
    }

    public SortedMap<Integer, ActionObject> getActionsSinceTimestamp(int targetTimestamp) {
        if (!this.containsTimestamp(targetTimestamp)) {
            return null;
        }

        SortedMap<Integer, ActionObject> actions = new TreeMap<>();
        for (int timestamp : log.keySet()) {
            if (timestamp > targetTimestamp) {
                actions.put(timestamp, log.get(timestamp));
            }
        }
        return actions;
    }

    public void appendAction(int timestamp, ActionObject actionObject) {
        if (log.size() >= capacity) {
            removeOldest();
        }
        log.put(timestamp, actionObject);
    }

    private void removeOldest() {
        int leastTimestamp = Integer.MAX_VALUE;
        for (int timestamp : log.keySet()) {
            leastTimestamp = Math.min(leastTimestamp, timestamp);
        }
        log.remove(leastTimestamp);
    }
}
