package src.Server;

import src.Common.ErrorType;
import src.Common.IdServerInterface;
import src.Common.ServerResponse;

import javax.rmi.ssl.SslRMIClientSocketFactory;
import javax.rmi.ssl.SslRMIServerSocketFactory;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.UnknownHostException;
import java.rmi.AccessException;
import java.rmi.NotBoundException;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.RMIClientSocketFactory;
import java.rmi.server.RMIServerSocketFactory;
import java.rmi.server.RMISocketFactory;
import java.rmi.server.ServerNotActiveException;
import java.rmi.server.UnicastRemoteObject;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import java.util.SortedMap;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ConcurrentHashMap;

import static java.rmi.server.RemoteServer.getClientHost;

/**
 * This class represents a server being run for our implementation of an ID server.
 * The server uses command line arguments to determine the port to service and how
 * much information will be printed to the console. The server can then be started
 * and receive commands and queries from IdClients.
 *
 * @author Anna Rift, Logan Hurd
 */
public class IdServer implements IdServerInterface, ReplicaInterface {
    // port number used for this and other servers
    static int portNumber;
    // Whether additional information should be printed to console
    static boolean verbose;
    // Hostname of this server
    static InetAddress myHostname;
    static IdServerForClients idServerForClients;
    static IdServerForServers idServerForServers;

    // Hashmap for login information
    ConcurrentHashMap<String, LoginInfo> loginData;
    // Logical clock used in this server
    LamportClock clock;
    // List of other servers
    List<ReplicaConnection> replicaConnections = new LinkedList<>();
    // Connection to the coordinator, if it is not this server
    ReplicaConnection coordinatorConnection = null;
    // Whether we are currently the coordinator
    boolean isCoordinator;
    // Previous coordinator that we need to get info from if we become coordinator
    ReplicaConnection previousCoordinator = null;
    // Last few actions performed on database
    ActionLog actionLog = new ActionLog(ACTION_LOG_SIZE);
    Integer latestActionTimestamp = -1;
    // Whether we are conducting an election
    volatile boolean conductingElection;
    // Whether we've heard back from a better server during election
    volatile boolean lostElection;

    private static final String SERIALIZATION_PATH = "src/resources/loginData.ser";
    private static final String RMI_CLIENT_REMOTE_NAME = "IdServer";
    private static final String RMI_SERVER_REMOTE_NAME = "IdServerReplica";
    private static final int AUTO_SAVE_TIME = 30 * 1000;
    private static final int SYNC_PERIOD = 5 * 1000;
    private static final int ACTION_LOG_SIZE = 3;
    private static final int ELECTION_WAIT = 2 * 1000;
    private static final int RMI_TIMEOUT = ELECTION_WAIT;

    public static final String GREY_TEXT = "\u001B[90m";
    public static final String NORMAL_TEXT = "\u001B[0m";

    /**
     * This main class processes command line arguments and starts an IdServer to
     * handle client commands/queries
     *
     * @param args String[] from command line
     */
    public static void main(String[] args) {

        // checks length of args
        if (args.length < 2) {
            printUsageAndExit();
        }

        // attempts to retrieve port number
        portNumber = -1;
        if (!(args[0].equals("--numport") || args[0].equals("-n"))) {
            printUsageAndExit();
        } else {
            portNumber = Integer.parseInt(args[1]);
        }

        // sets verbose and potentialReplicas to correct values
        verbose = false;
        List<String> potentialReplicas = new LinkedList<>();
        if (args.length > 2) {
            if (args[2].equals("--verbose") || args[2].equals("-v")) {
                verbose = true;
            }

            // remaining args are other servers
            // starts at args[3] if verbose, args[2] if not.
            if (verbose && args.length > 3) {
                potentialReplicas = new LinkedList<>(Arrays.asList(args).subList(3, args.length));
            } else if (verbose) {
                potentialReplicas = new LinkedList<>();
            } else {
                potentialReplicas = new LinkedList<>(Arrays.asList(args).subList(2, args.length));
            }
        }

        // starts server and binds to port
        System.setProperty("sun.rmi.transport.tcp.connectionTimeout", String.valueOf(RMI_TIMEOUT));
        System.setProperty("sun.rmi.transport.tcp.handshakeTimeout", String.valueOf(RMI_TIMEOUT));
        System.setProperty("sun.rmi.transport.tcp.responseTimeout", String.valueOf(RMI_TIMEOUT));
        System.setProperty("sun.rmi.transport.tcp.readTimeout", String.valueOf(RMI_TIMEOUT));
        try {
            myHostname = InetAddress.getLocalHost();
        } catch (UnknownHostException e) {
            System.err.println("Could not get local host information");
            System.exit(2);
        }
        try {
            IdServer server = new IdServer(potentialReplicas);
        } catch (RemoteException e) {
            System.err.println("Initializing server failed");
            System.exit(1);
        }
    }

    /**
     * Constructor for the IdServer class. Initializes variables, and starts the automatic
     * serialization and shutdown hook.
     *
     * @param replicas List of replica addresses
     * @throws RemoteException Initializing server failed
     */
    public IdServer(List<String> replicas) throws RemoteException {
        this.clock = new LamportClock();
        this.loginData = new ConcurrentHashMap<>();
        loadData();
        readyAutomaticSerialization();
        Runtime.getRuntime().addShutdownHook(new shutdownHook());

        // setup RMI
        LocateRegistry.createRegistry(portNumber);
        this.bindForClients();
        this.bindForServers();

        // Attempts to connect to replicas
        logDebug("Attempting connection to each listed replica");
        List<String> connectedReplicas = new ArrayList<>();
        List<String> forbiddenHostnames = Arrays.asList("127.0.0.1", "127.0.1.1", myHostname.getHostAddress());
        for (String replicaHostname : replicas) {
            if (forbiddenHostnames.contains(replicaHostname)) {
                logError("Skipped adding replica " + replicaHostname + " because it could refer to this server itself");
                continue;
            }
            ReplicaConnection replicaConnection = new ReplicaConnection(replicaHostname);
            this.replicaConnections.add(replicaConnection);
            if (replicaConnection.attemptConnection()) {
                connectedReplicas.add(replicaHostname);
            }
        }

        clock.incrementForEvent("Completed server startup, connected to the following replicas: " + connectedReplicas);

        // begin an election immediately on startup
        initiateElection();

        startCoordinatorPing();
    }

    /**
     * Sets security policies, prepares RMI socket factories, creates
     * registry, and adds the server into that registry under input
     * name and port number.
     */
    public void bindForClients() {
        try {
            System.setProperty("java.security.policy", "src/resources/mysecurity.policy");
            System.setProperty("javax.net.ssl.keyStore", "src/resources/Server_Keystore");
            System.setProperty("javax.net.ssl.keyStorePassword", "examplepassword");

            RMIClientSocketFactory rmiClientSocketFactory = new SslRMIClientSocketFactory();
            RMIServerSocketFactory rmiServerSocketFactory = new SslRMIServerSocketFactory();
            idServerForClients = new IdServerForClients(this);
            IdServerInterface server = (IdServerInterface) UnicastRemoteObject.exportObject(idServerForClients, 0, rmiClientSocketFactory,
                    rmiServerSocketFactory);
            Registry registry = LocateRegistry.getRegistry(portNumber);
            registry.bind(RMI_CLIENT_REMOTE_NAME, server);
            logDebug("Server bound to client-server RMI (port " + portNumber + ") and ready to serve requests");
            clock.incrementForEvent("Completed client RMI binding");
        } catch (Exception e) {
            e.printStackTrace();
            System.out.println("Exception occurred: " + e);
        }
    }

    public void bindForServers() {
        try {
            // setup sockets for RMI to timeout on connect or read
            RMISocketFactory.setSocketFactory(new RMISocketFactory() {
                // Creates a socket
                public Socket createSocket(String host, int port) throws IOException {
                    Socket socket = new TimeoutSocket(host, port);
                    socket.setSoTimeout(RMI_TIMEOUT);
                    socket.setSoLinger(false, 0);
                    return socket;
                }

                // Creates a ServerSocket
                public ServerSocket createServerSocket(int port) throws IOException {
                    return new ServerSocket(port);
                }

                // Socket with a timeout
                class TimeoutSocket extends Socket {
                    public TimeoutSocket(String host, int port) throws IOException {
                        super(host, port);
                    }

                    @Override
                    public void connect(SocketAddress endpoint) throws IOException {
                        connect(endpoint, RMI_TIMEOUT);
                    }
                }
            });

            // Creates and binds server for connecting to other servers
            idServerForServers = new IdServerForServers(this);
            ReplicaInterface server = (ReplicaInterface) UnicastRemoteObject.exportObject(idServerForServers, 0);
            Registry registry = LocateRegistry.getRegistry(portNumber);
            registry.bind(RMI_SERVER_REMOTE_NAME, server);
            logDebug("Server bound to server-server RMI (port " + portNumber + ") and ready to serve requests");
            clock.incrementForEvent("Completed server RMI binding");
        } catch (Exception e) {
            e.printStackTrace();
            System.out.println("Exception occurred: " + e);
        }
    }

    /**
     * prints usage message and exits
     */
    private static void printUsageAndExit() {
        System.err.println("Usage: $ java IdServer --numport <port#> [--verbose] [ReplicaAddresses]");
        System.exit(1);
    }

    /**
     * gets IdServer's host name
     *
     * @return IdServers host name
     */
    private String getServerName() {
        return myHostname.getHostAddress();
    }

    /**
     * if IdServer is set to verbose, prints "Debug: " + message.
     *
     * @param message String to be printed only if verbose is true
     */
    private void logDebug(String message) {
        if (verbose) {
            LocalTime realTime = LocalTime.now().truncatedTo(ChronoUnit.MILLIS);
            System.err.println(realTime + " debug " + getServerName() + ": " + message);
        }
    }

    /**
     * Prints "ERROR: " + message.
     *
     * @param message Error message to be printed
     */
    private void logError(String message) {
        LocalTime realTime = LocalTime.now().truncatedTo(ChronoUnit.MILLIS);
        System.err.println(realTime + " ERROR " + getServerName() + ": " + message);
    }

    @Override
    public ServerResponse create(String loginName, String realName, String password) throws RemoteException {
        clock.incrementForEvent("Received CREATE from client");
        ActionObject action = new ActionObject(ActionObject.ActionKind.CREATE, loginName, password, realName);
        ServerResponse response = applyAction(action);
        clock.incrementForEvent("Completed processing for CREATE");
        return response;
    }

    @Override
    public ServerResponse lookup(String loginName) throws RemoteException {
        clock.incrementForEvent("Received LOOKUP from client");
        logDebug("Looking up info on user " + loginName);
        ServerResponse response;
        if (!loginData.containsKey(loginName)) {
            logError("User '" + loginName + "' does not exist");
            response = new ServerResponse(ErrorType.NO_SUCH_USER);
        } else {
            response = new ServerResponse(loginData.get(loginName).toString());
        }
        clock.incrementForEvent("Completed processing for LOOKUP");
        return response;
    }

    @Override
    public ServerResponse reverseLookup(String uuidString) throws RemoteException {
        clock.incrementForEvent("Received REVERSE LOOKUP from client");
        logDebug("Looking up info on user with UUID " + uuidString);
        Optional<LoginInfo> lookupResult = loginData.values().stream().filter(x -> x.uuid.toString().equals(uuidString)).findFirst();
        ServerResponse response;
        if (lookupResult.isEmpty()) {
            logError("No user with UUID " + uuidString + " exists");
            response = new ServerResponse(ErrorType.NO_SUCH_USER);
        } else {
            response = new ServerResponse(lookupResult.get().toString());
        }
        clock.incrementForEvent("Completed processing for REVERSE LOOKUP");
        return response;
    }

    @Override
    public ServerResponse modify(String oldLoginName, String newLoginName, String password) throws RemoteException {
        clock.incrementForEvent("Received MODIFY from client");
        ActionObject action = new ActionObject(ActionObject.ActionKind.MODIFY, oldLoginName, password, newLoginName);
        ServerResponse response = applyAction(action);
        clock.incrementForEvent("Completed processing for MODIFY");
        return response;
    }

    @Override
    public ServerResponse delete(String loginName, String password) throws RemoteException {
        clock.incrementForEvent("Received DELETE from client");
        ActionObject action = new ActionObject(ActionObject.ActionKind.DELETE, loginName, password, null);
        ServerResponse response = applyAction(action);
        clock.incrementForEvent("Completed processing for DELETE");
        return response;
    }

    @Override
    public ServerResponse get(String whatToGet) throws RemoteException {
        clock.incrementForEvent("Received GET from client");
        logDebug("Got request for data of type '" + whatToGet + "'");
        ServerResponse response;
        switch (whatToGet) {
            case "users":
                response = new ServerResponse(loginData.keySet().toString());
                break;
            case "uuids":
                List<String> uuids = new ArrayList<>();
                loginData.values().forEach(info -> uuids.add(info.uuid.toString()));
                response = new ServerResponse(uuids.toString());
                break;
            case "all":
                response = new ServerResponse(loginData.toString());
                break;
            default:
                response = new ServerResponse(ErrorType.MALFORMED_INPUT);
                break;
        }
        clock.incrementForEvent("Completed GET processing");
        return response;
    }

    @Override
    public ServerResponse getCoordinator() throws RemoteException {
        while (!isCoordinator && coordinatorConnection == null) {
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }

        return new ServerResponse(getPreviousCoordinatorAddressActual());
    }

    /**
     * Gets previous coordinator's address
     *
     * @return previous coordinator's address
     */
    public String getPreviousCoordinatorAddressActual() {
        if (!isCoordinator && coordinatorConnection == null) {
            return null;
        }

        String coordinatorHostname;
        if (isCoordinator) {
            coordinatorHostname = myHostname.getHostAddress();
        } else {
            coordinatorHostname = coordinatorConnection.hostname;
        }
        return coordinatorHostname;
    }
    @Override
    public String getPreviousCoordinatorAddress() throws RemoteException {
        return getPreviousCoordinatorAddressActual();
    }

    /**
     * Applies a CREATE, MODIFY, or DELETE action.
     *
     * @param action the action to be applied
     * @return a success response or an error response
     */
    private ServerResponse applyAction(ActionObject action) {
        ServerResponse successResponse = null;
        String password = action.password;
        switch (action.kind) {
            case CREATE: // Creates a new set of loginData
                String createLoginName = action.loginName;
                String realName = action.data;
                logDebug("Creating user " + createLoginName);

                // If entry already exists, handle error
                if (loginData.containsKey(createLoginName)) {
                    logError("Name '" + createLoginName + "' already exists");
                    return new ServerResponse(ErrorType.NAME_COLLISION);
                }

                String ip = null;
                try {
                    ip = getClientHost();
                } catch (ServerNotActiveException e) {
                    logError("Could not get client IP");
                    e.printStackTrace();
                }

                LoginInfo loginInfo = new LoginInfo(createLoginName, realName, password, ip);
                loginData.put(createLoginName, loginInfo);
                successResponse = new ServerResponse(loginInfo.uuid.toString());
                break;
            case MODIFY: // Changes the username of an existing set of loginData
                String oldLoginName = action.loginName;
                String newLoginName = action.data;
                logDebug("Attempting to change a login name from '" + oldLoginName + "' to '" + newLoginName + "'");

                // If loginName to be changed already exists, throw error
                if (!loginData.containsKey(oldLoginName)) {
                    return new ServerResponse(ErrorType.NO_SUCH_USER);
                // else if password is wrong, throw error
                } else if (!loginData.get(oldLoginName).password.equals(password)) {
                    return new ServerResponse(ErrorType.INCORRECT_PASSWORD);
                // else if the new name is already taken, throw error
                } else if (loginData.containsKey(newLoginName)) {
                    return new ServerResponse(ErrorType.NAME_COLLISION);
                }

                LoginInfo info = loginData.remove(oldLoginName);
                info.changeLoginName(newLoginName);
                loginData.put(newLoginName, info);
                logDebug("Changed'" + oldLoginName + "'->'" + newLoginName + "'");
                successResponse = new ServerResponse(ErrorType.NONE);
                break;
            case DELETE:
                String deleteLoginName = action.loginName;
                logDebug("Attempting to delete user with login name'" + deleteLoginName + "'");

                // if no such user is found, throw error
                if (!loginData.containsKey(deleteLoginName)) {
                    return new ServerResponse(ErrorType.NO_SUCH_USER);
                // else if password is wrong, throw error
                } else if (!loginData.get(deleteLoginName).password.equals(password)) {
                    return new ServerResponse(ErrorType.INCORRECT_PASSWORD);
                }

                loginData.remove(deleteLoginName);
                logDebug("Deleted '" + deleteLoginName + "'");
                successResponse = new ServerResponse(ErrorType.NONE);
                break;
        }
        clock.incrementForEvent("Applied " + action.kind + " action");

        // if this replica is the coordinator, set timestamp and copy action to replicas
        if (isCoordinator) {
            int previousTimestamp = latestActionTimestamp;
            latestActionTimestamp = clock.getCurrent();
            copyActionToReplicas(action, latestActionTimestamp, previousTimestamp);
            actionLog.appendAction(latestActionTimestamp, action);
        }

        return successResponse;
    }

    /**
     * copies an action to all replicaConnections
     *
     * @param action action to be copied
     * @param timestamp timestamp of action
     * @param previousActionTimestamp timestamp of previous action
     */
    private void copyActionToReplicas(ActionObject action, int timestamp, int previousActionTimestamp) {
        for (ReplicaConnection replica : replicaConnections) {
            if (replica.isConnected()) {
                try {
                    replica.serverStub.playReplicatedAction(action, timestamp, previousActionTimestamp);
                } catch (RemoteException e) {
                    logDebug("Could not copy action to replica " + replica);
                    e.printStackTrace(System.err);
                }
            }
        }
    }

    /**
     * Prepares Automatic Serialization to happen every AUTO_SAVE_TIME seconds
     */
    public void readyAutomaticSerialization() {
        TimerTask autosave = new TimerTask() {
            @Override
            public void run() {
                saveData();
            }
        };
        new Timer().scheduleAtFixedRate(autosave, AUTO_SAVE_TIME, AUTO_SAVE_TIME);
    }

    /**
     * Writes data to a file using java serialization.
     */
    public void saveData() {
        clock.incrementForEvent("Saving state to file");
        try {
            FileOutputStream dataOut = new FileOutputStream(SERIALIZATION_PATH);
            ObjectOutputStream out = new ObjectOutputStream(dataOut);
            out.writeObject(loginData);
            out.flush();
            out.close();
            dataOut.close();
        } catch (Exception e) {
            System.out.println("Error occurred during serialization.");
            e.printStackTrace();
        }
    }

    /**
     * starts a timed coordinatorPingTask that makes sure the coordinator remains up
     */
    private void startCoordinatorPing() {
        TimerTask coordinatorPingTask = new TimerTask() {
            public void run() {
                try {
                    pingCoordinator();
                } catch (RemoteException e) {
                    logDebug("Coordinator did not respond to ping, assuming down");
                    initiateElectionIfNotRunning();
                }
            }
        };
        new Timer().scheduleAtFixedRate(coordinatorPingTask, 0, SYNC_PERIOD);
    }

    /**
     * Pings coordinator to make sure it's up and that this replica is up to date
     *
     * @throws RemoteException failed connection
     */
    public void pingCoordinator() throws RemoteException {
        if (!isCoordinator && coordinatorConnection != null) {
            if (!coordinatorConnection.serverStub.receivePing(clock.getCurrent(), latestActionTimestamp)) {
                // we are out of date
                syncWithCoordinator();
            }
        }
    }

    @Override
    public boolean receivePing(int timestamp, int replicaLastActionTimestamp) throws RemoteException {
        clock.adjustToIncomingMessage(timestamp, "Received ping from " + getRequestOriginatorOrDie());
        return (replicaLastActionTimestamp == latestActionTimestamp);
    }

    @Override
    public SyncInfo getMissingInfoSinceTimestamp(int lastActionSeenTimestamp) throws RemoteException {
        if (!isCoordinator) {
            logDebug("Replica asked to sync with us but we are not coordinator -- must be new coordinator catching up");
        }
        SortedMap<Integer, ActionObject> catchupActions = actionLog.getActionsSinceTimestamp(lastActionSeenTimestamp);
        if (catchupActions == null) {
            // can't catch up from log, return entire database
            return new SyncInfo(loginData);
        } else if (catchupActions.size() == 0) {
            logError("Replica asking for updates appears to be up-to-date already");
            return null;
        } else {
            return new SyncInfo(catchupActions);
        }
    }

    /**
     * Loads serialized data to loginData. If file does not exist, does nothing,
     * but upon other exceptions returns an appropriate error message.
     */
    @SuppressWarnings("unchecked")
    public void loadData() {
        try {
            FileInputStream dataIn = new FileInputStream(SERIALIZATION_PATH);
            ObjectInputStream in = new ObjectInputStream(dataIn);
            loginData = (ConcurrentHashMap<String, LoginInfo>) in.readObject();
            logDebug("Loaded data from " + SERIALIZATION_PATH + ": " + getDatabaseStateAsString());
            in.close();
            dataIn.close();
        } catch (FileNotFoundException e) {
            // Do Nothing
        } catch (Exception e) {
            System.out.println("Error occurred during unserialization.");
            e.printStackTrace();
        }
    }

    /**
     * Gets loginData database as a String
     *
     * @return Database State as String
     */
    private String getDatabaseStateAsString() {
        return loginData.toString();
    }

    /**
     * returns active replicas
     */
    public List<ReplicaConnection> getActiveReplicas() {
        List<ReplicaConnection> activeList = new LinkedList<>();
        for (ReplicaConnection replicaHostname : replicaConnections) {
            if (replicaHostname.isConnected()) {
                activeList.add(replicaHostname);
            }
        }
        return activeList;
    }

    @Override
    public void playReplicatedAction(ActionObject action, int actionTimestamp, int expectedLastTimestamp) throws RemoteException {
        clock.adjustToIncomingMessage(actionTimestamp, "Got action to replicate");
        // update before replicating if we're out of date
        if (latestActionTimestamp != expectedLastTimestamp) {
            logDebug("Out of date: got an action that follows @" + expectedLastTimestamp + ", but our last action was @" + latestActionTimestamp);
            syncWithCoordinator();
        }
        applyAction(action);
        latestActionTimestamp = actionTimestamp;
    }

    /**
     * Handles errors and calls syncWithOtherServer on coordinator
     *
     * @throws RemoteException failed connection
     */
    private void syncWithCoordinator() throws RemoteException {
        clock.incrementForEvent("Starting sync with coordinator");
        if (isCoordinator) {
            logError("But Doctor, I am Coordinator");
            System.exit(1);
        } else if (coordinatorConnection == null) {
            logError("Need to sync with coordinator, but there is none");
            System.exit(1);
        }

        syncWithOtherServer(coordinatorConnection);
    }

    /**
     * Gets all info since latestActionTimestamp from other replica
     *
     * @param other hostname of other server
     * @throws RemoteException connection failed
     */
    private void syncWithOtherServer(ReplicaConnection other) throws RemoteException {
        SyncInfo syncInfo = other.serverStub.getMissingInfoSinceTimestamp(latestActionTimestamp);
        if (syncInfo.isEntireDatabase()) {
            this.loginData = new ConcurrentHashMap<>(syncInfo.allData);
        } else {
            for (int timestamp : syncInfo.recentActions.keySet()) {
                applyAction(syncInfo.recentActions.get(timestamp));
            }
        }
    }

    /**
     * Initiates an Election with other known replicas if not already conducting an election
     */
    private void initiateElectionIfNotRunning() {
        if (!conductingElection) {
            initiateElection();
        }
    }

    /**
     * Starts an election
     */
    private void initiateElection() {
        // checks if an election is already being conducted
        if (conductingElection) {
            logError("Attempted to start an election while already conducting one, ignoring second attempt");
            return;
        }

        clock.incrementForEvent("Initiating election");
        isCoordinator = false;
        coordinatorConnection = null;
        conductingElection = true;
        lostElection = false;
        String previousCoordinatorHostname = null;
        // Communicates election with other replicas
        for (ReplicaConnection replica : replicaConnections) {
            // if serverStub doesn't exist ignore replica
            if (replica.serverStub == null) {
                continue;
            }
            // if there is no known previous coordinator tries to get previous coordinator address from replica
            if (previousCoordinatorHostname == null) {
                try {
                    previousCoordinatorHostname = replica.serverStub.getPreviousCoordinatorAddress();
                } catch (RemoteException ignored) {
                }
            }
            // if the other hostname is a bigger bully, announce the election to them
            if (isBiggerBully(replica)) {
                try {
                    replica.serverStub.electionAnnounce(clock.getCurrent());
                } catch (RemoteException e) {
                    logDebug("Could not announce election to replica " + replica + ", continuing");
                }
            }
        }

        // if all has gone well sets previousCoordinator
        if (previousCoordinatorHostname != null && !previousCoordinatorHostname.equals(myHostname.getHostAddress())) {
            previousCoordinator = getReplicaByHostnameOrDie(previousCoordinatorHostname);
        }

        awaitElectionWin();
    }

    /**
     * Attempts to find a ReplicaConnection to a host with the desired name
     *
     * @param targetHostname desired hostname
     * @return ReplicaConnection with matching hostname
     */
    ReplicaConnection getReplicaByHostnameOrDie(String targetHostname) {
        // finds replica with a hostname equivalent to targetHostname
        for (ReplicaConnection replica : replicaConnections) {
            if (replica.hostname.equals(targetHostname)) {
                return replica;
            }
        }

        // If this is reached, handle error and exit.
        logError("Could not find replica with desired hostname '" + targetHostname + "'!");
        new Exception().printStackTrace(System.err);
        System.exit(1);
        return null;
    }

    /**
     * wait for the election to finish
     */
    private void awaitElectionWin() {
        // timer task to process the Election Result
        TimerTask processElectionResult = new TimerTask() {
            @Override
            public void run() {
                processElectionResult();
            }
        };

        // runs processElectionResult after ELECTION_WAIT/1000 seconds
        new Timer().schedule(processElectionResult, ELECTION_WAIT);
    }

    /**
     * determines whether to become the coordinator
     */
    private void processElectionResult() {
        conductingElection = false;
        if (!lostElection) {
            // we are the winner
            becomeCoordinator();
        }
    }

    /**
     * Makes this replica the coordinator
     */
    private void becomeCoordinator() {
        clock.incrementForEvent("Becoming coordinator");
        isCoordinator = true;
        coordinatorConnection = null;

        // get data from previous coordinator if needed
        if (previousCoordinator != null) {
            clock.incrementForEvent("Syncing new coordinator with previous coordinator");
            try {
                syncWithOtherServer(previousCoordinator);
            } catch (RemoteException e) {
                logError("Failed to sync with previous coordinator");
            }
        } else {
            logDebug("No previous coordinator to sync with, assuming we're up to date");
        }
        previousCoordinator = null;

        // Informs other replicas of election victory
        for (ReplicaConnection replica : getActiveReplicas()) {
            try {
                replica.serverStub.electionVictory(clock.getCurrent());
            } catch (RemoteException e) {
                logDebug("Could not proclaim election victory to replica " + replica + ", continuing");
            }
        }

        logDebug("Database state after becoming coordinator: " + getDatabaseStateAsString());
    }

    @Override
    public void electionAnnounce(int timestamp) throws RemoteException {
        clock.adjustToIncomingMessage(timestamp, "Received election announcement");
        ReplicaConnection requestOriginator = getRequestOriginatorOrDie();
        // we received notice of an election
        if (!isBiggerBully(requestOriginator)) {
            // tell the weaker server to shut up
            if (requestOriginator.isConnected()) {
                requestOriginator.serverStub.electionResponse(clock.getCurrent());
                clock.incrementForEvent("Stopped election from weaker server");
            }
            // take over election
            initiateElectionIfNotRunning();
        }
    }

    @Override
    public void electionResponse(int timestamp) throws RemoteException {
        clock.adjustToIncomingMessage(timestamp, "Received election acknowledgement (shut up)");
        // we received a response from a stronger server
        logDebug("Lost election" + (isCoordinator ? " (was previously coordinator)" : ""));
        getRequestOriginatorOrDie().ensureConnected();
        lostElection = true;
    }

    @Override
    public void electionVictory(int timestamp) throws RemoteException {
        clock.adjustToIncomingMessage(timestamp, "Received notification of an election victory");
        ReplicaConnection requestOriginator = getRequestOriginatorOrDie();
        requestOriginator.ensureConnected();
        updateCoordinatorToOther(requestOriginator);
    }

    /**
     * Updates coordinator to newCoordinator
     *
     * @param newCoordinator the new coordinator
     */
    private void updateCoordinatorToOther(ReplicaConnection newCoordinator) {
        clock.incrementForEvent("Updated coordinator");
        logDebug("Updating coordinator to " + newCoordinator);
        actionLog.clear();
        isCoordinator = false;
        coordinatorConnection = newCoordinator;
    }

    /**
     * Determines whether target replica has a more significant host address
     *
     * @param other ReplicaConnection to target replica
     * @return true if other replica is more significant, false if not
     */
    private boolean isBiggerBully(ReplicaConnection other) {
        return (other.hostname.compareTo(myHostname.getHostAddress()) > 0);
    }

    /**
     * Gets a ReplicaConnection for the originator of a request
     *
     * @return
     */
    private ReplicaConnection getRequestOriginatorOrDie() {
        String ip = null;
        try {
            ip = getClientHost();
        } catch (ServerNotActiveException e) {
            e.printStackTrace(System.err);
        }

        if (ip != null) {
            for (ReplicaConnection replica : replicaConnections) {
                if (replica.hostname.equals(ip)) {
                    return replica;
                }
            }
        }

        logError("Could not find request originator! (ip: '" + ip + "')");
        new Exception().printStackTrace(System.err);
        System.exit(1);
        return null;
    }

    /**
     * shuts down the server and prints an exit message.
     */
    private class shutdownHook extends Thread {
        public void run() {
            saveData();
            System.out.println("Data Saved. Shutting Down.");
        }
    }

    private class ReplicaConnection {
        public String hostname;
        public ReplicaInterface serverStub;

        public ReplicaConnection(String hostname) {
            this.hostname = hostname;
            serverStub = null;
        }

        public boolean attemptConnection() {
            boolean success = false;

            try {
                Registry registry = LocateRegistry.getRegistry(hostname, portNumber);
                this.serverStub = (ReplicaInterface) registry.lookup(RMI_SERVER_REMOTE_NAME);
                success = true;
                logDebug("Successfully connected to " + hostname + " at port number " + portNumber);
            } catch (AccessException e) {
                logError("Unexpected AccessException: ");
                e.printStackTrace(System.err);
            } catch (NotBoundException | RemoteException e) {
//                logDebug("Failed to connect to replica " + hostname + ", exception " + e);
            }

            return success;
        }

        public boolean isConnected() {
            if (serverStub == null) {
                return false;
            }
            try {
                Registry registry = LocateRegistry.getRegistry(hostname, portNumber);
                this.serverStub = (ReplicaInterface) registry.lookup(RMI_SERVER_REMOTE_NAME);
                return true;
            } catch (Exception ignored) {

            }
            return false;
        }

        public void ensureConnected() {
            if (!this.isConnected()) {
                this.attemptConnection();
            }
        }

        @Override
        public String toString() {
            return hostname;
        }
    }

    private class LamportClock {
        private int timestamp;

        public LamportClock() {
            timestamp = 0;
            incrementForEvent("Lamport clock initialized");
        }

        public synchronized int getCurrent() {
            return timestamp;
        }

        public synchronized void incrementForEvent(String event) {
            timestamp++;
            logDebug(GREY_TEXT + "Timestamp @" + timestamp + ": " + event + NORMAL_TEXT);
        }

        public synchronized void adjustToIncomingMessage(int otherTimestamp, String event) {
            int oldValue = timestamp;
            logDebug(GREY_TEXT + "Incoming message event @ " + oldValue + ": " + (event == null ? "(empty)" : event) + ", current timestamp " + oldValue + NORMAL_TEXT);
            timestamp = Math.max(oldValue, otherTimestamp + 1);
            if (timestamp != oldValue) {
                logDebug("Timestamp advanced from " + oldValue + " to " + timestamp);
            }
        }
    }
}
