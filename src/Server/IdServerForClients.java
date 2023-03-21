package src.Server;

import src.Common.IdServerInterface;
import src.Common.ServerResponse;

import java.rmi.RemoteException;

public class IdServerForClients implements IdServerInterface {
    private IdServer backendServer;

    public IdServerForClients(IdServer backendServer) {
        this.backendServer = backendServer;
    }

    @Override
    public ServerResponse create(String loginName, String realName, String password) throws RemoteException {
        return backendServer.create(loginName, realName, password);
    }

    @Override
    public ServerResponse lookup(String loginName) throws RemoteException {
        return backendServer.lookup(loginName);
    }

    @Override
    public ServerResponse reverseLookup(String uuidString) throws RemoteException {
        return backendServer.reverseLookup(uuidString);
    }

    @Override
    public ServerResponse modify(String oldLoginName, String newLoginName, String password) throws RemoteException {
        return backendServer.modify(oldLoginName, newLoginName, password);
    }

    @Override
    public ServerResponse delete(String loginName, String password) throws RemoteException {
        return backendServer.delete(loginName, password);
    }

    @Override
    public ServerResponse get(String whatToGet) throws RemoteException {
        return backendServer.get(whatToGet);
    }

    @Override
    public ServerResponse getCoordinator() throws RemoteException {
        return backendServer.getCoordinator();
    }
}
