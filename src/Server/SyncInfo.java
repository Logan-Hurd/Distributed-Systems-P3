package src.Server;

import java.io.Serializable;
import java.util.Map;
import java.util.SortedMap;

public class SyncInfo implements Serializable {
    public SortedMap<Integer, ActionObject> recentActions;
    Map<String, LoginInfo> allData;

    public SyncInfo(SortedMap<Integer, ActionObject> recentActions) {
        this.recentActions = recentActions;
        this.allData = null;
    }

    public SyncInfo(Map<String, LoginInfo> allData) {
        this.allData = allData;
        this.recentActions = null;
    }

    public boolean isEntireDatabase() {
        return allData != null;
    }
}
