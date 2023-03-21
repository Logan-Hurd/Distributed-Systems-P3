package src.Common;

import java.rmi.Remote;
import java.rmi.RemoteException;

/**
 * IdServer interface. Contains create, lookup, reverseLookup, modify,
 * delete, and get methods headers.
 *
 * @author Anna Rift
 */
public interface IdServerInterface extends Remote {

    /**
     * Creates a user using input information and their ip found using
     * getClientHost(). Returns an appropriate ServerResponse.
     *
     * @param loginName loginName of this user (must be unique)
     * @param realName  realName of this user
     * @param password  password for this user
     * @return Appropriate ServerResponse
     * @throws RemoteException Connection failed during creation
     */
    ServerResponse create(String loginName, String realName, String password) throws RemoteException;

    /**
     * Checks for user within loginData. If user exists, returns user info.
     * If user does not exist, returns an error message. Finds user by
     * loginName.
     *
     * @param loginName loginName of desired user
     * @return Appropriate ServerResponse
     * @throws RemoteException connection failed during lookup
     */
    ServerResponse lookup(String loginName) throws RemoteException;

    /**
     * Checks for user within loginData. If user exists, returns user info.
     * If user does not exist, returns an error message. Finds user by uuid.
     *
     * @param uuidString uuid of desired user
     * @return Appropriate ServerResponse
     * @throws RemoteException connection failed during reverseLookup
     */
    ServerResponse reverseLookup(String uuidString) throws RemoteException;

    /**
     * Attempts to change the loginName of a user. Returns an appropriate
     * error message on failure, or a confirmation message on success.
     *
     * @param oldLoginName current loginName of user
     * @param newLoginName new loginName for user
     * @param password     password for user
     * @return Appropriate ServerResponse
     * @throws RemoteException connection failed during modify
     */
    ServerResponse modify(String oldLoginName, String newLoginName, String password) throws RemoteException;

    /**
     * Attempts to delete a user. Returns an appropriate error upon
     * failure, or a confirmation message upon success.
     *
     * @param loginName loginName of desired user
     * @param password  password for user
     * @return Appropriate ServerResponse
     * @throws RemoteException connection failed during delete
     */
    ServerResponse delete(String loginName, String password) throws RemoteException;

    /**
     * Retrieves all usernames, uuids, or both from loginData.
     * Returns resulting ServerResponse.
     *
     * @param whatToGet tells function what information to retrieve. must be: 'uuid', 'user', or 'all'
     * @return Appropriate ServerResponse
     * @throws RemoteException connection failed during get
     */
    ServerResponse get(String whatToGet) throws RemoteException;

    /**
     * Retrieves the coordinator's address.
     * Returns current coordinator's address.
     *
     * @return ServerResponse containing the coordinator's address
     * @throws RemoteException
     */
    ServerResponse getCoordinator() throws RemoteException;
}
