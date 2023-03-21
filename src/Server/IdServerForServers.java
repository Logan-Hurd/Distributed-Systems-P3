package src.Server;

import java.rmi.RemoteException;

public class IdServerForServers implements ReplicaInterface {
    private IdServer backendServer;

    public IdServerForServers(IdServer backendServer) {
        this.backendServer = backendServer;
    }

    @Override
    public void playReplicatedAction(ActionObject action, int timestamp, int expectedLastTimestamp) throws RemoteException {
        backendServer.playReplicatedAction(action, timestamp, expectedLastTimestamp);
    }

    @Override
    public boolean receivePing(int timestamp, int replicaLastActionTimestamp) throws RemoteException {
        return backendServer.receivePing(timestamp, replicaLastActionTimestamp);
    }

    @Override
    public SyncInfo getMissingInfoSinceTimestamp(int lastActionSeenTimestamp) throws RemoteException {
        return backendServer.getMissingInfoSinceTimestamp(lastActionSeenTimestamp);
    }

    @Override
    public void electionAnnounce(int timestamp) throws RemoteException {
        backendServer.electionAnnounce(timestamp);
    }

    @Override
    public void electionResponse(int timestamp) throws RemoteException {
        backendServer.electionResponse(timestamp);
    }

    @Override
    public void electionVictory(int timestamp) throws RemoteException {
        backendServer.electionVictory(timestamp);
    }

    @Override
    public String getPreviousCoordinatorAddress() throws RemoteException {
        return backendServer.getPreviousCoordinatorAddress();
    }
}
