package src.Server;

import java.rmi.Remote;
import java.rmi.RemoteException;

/**
 * Interface for RMI communication between servers.
 *
 * @author Anna Rift
 */
public interface ReplicaInterface extends Remote {
    void playReplicatedAction(ActionObject action, int timestamp, int expectedLastTimestamp) throws RemoteException;

    boolean receivePing(int timestamp, int replicaLastActionTimestamp) throws RemoteException;

    SyncInfo getMissingInfoSinceTimestamp(int lastActionSeenTimestamp) throws RemoteException;

    void electionAnnounce(int timestamp) throws RemoteException;

    void electionResponse(int timestamp) throws RemoteException;

    void electionVictory(int timestamp) throws RemoteException;

    String getPreviousCoordinatorAddress() throws RemoteException;
}
