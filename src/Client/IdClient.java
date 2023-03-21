package src.Client;

import src.Common.ErrorType;
import src.Common.IdServerInterface;
import src.Common.ServerResponse;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.rmi.NotBoundException;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.RMISocketFactory;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;

/**
 * This class represents a client being run for our implementation of an ID server.
 * The client uses command line inputs to form a temporary connection to an IdServer,
 * submit one of a variety of queries, and print out the server's response.
 *
 * @author Anna Rift, Logan Hurd
 */
public class IdClient {
    // Initializes a variable to contain our interface for the IdServer
    IdServerInterface idServerStub = null;

    private static final int RMI_TIMEOUT = 2 * 1000;

    /**
     * This main method checks and handles arguments, then creates an IdClient to
     * connect to an IdServer at input hostname and port and calls processQuery
     * with input query arguments.
     *
     * @param args String[] from command line
     */
    public static void main(String[] args) {
        // Checks number of input arguments
        int minimumArgsBeforeQuery = 4;
        if (args.length < minimumArgsBeforeQuery + 1) {
            printUsageAndExit();
        }

        // flagLocation will store location of --numport (or -n) flag; 2 is minimum location
        int flagLocation = 1;

        // checking for --server (or -s) flag
        if (!(args[0].equals("--server") || args[0].equals("-s"))) {
            printUsageAndExit();
        }

        // get server hostnames and location of --numport (or -n)
        List<String> hostnames = new LinkedList<>();
        for (int i = flagLocation; i < (args.length - 2); i++) {
            if (args[i].equals("--numport") || args[i].equals("-n")) {
                flagLocation = i;
                break;
            } else {
                hostnames.add(args[i]);
            }
        }
        if (flagLocation == 1) {
            printUsageAndExit();
        }

        // TEST PRINT
        System.out.println("Hostnames: " + hostnames);

        // gets port number (as portNumber)
        int portNumber = Integer.parseInt(args[flagLocation + 1]);

        // get query-specific arguments (as queryArgs)
        int numQueryArgs = args.length - (flagLocation + 2);
        String[] queryArgs = new String[numQueryArgs];
        System.arraycopy(args, flagLocation + 2, queryArgs, 0, numQueryArgs);


        // Creates custom RMI sockets
        try {
            RMISocketFactory.setSocketFactory(new RMISocketFactory() {
                // Creates socket with custom settings
                public Socket createSocket(String host, int port) throws IOException {
                    Socket socket = new TimeoutSocket(host, port);
                    socket.setSoTimeout(RMI_TIMEOUT);
                    socket.setSoLinger(false, 0);
                    return socket;
                }

                // Creates new ServerSocket
                public ServerSocket createServerSocket(int port) throws IOException {
                    return new ServerSocket(port);
                }

                // Socket Creator with timeout
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
        } catch (IOException e) {
            System.err.println("Could not setup custom sockets for RMI");
            e.printStackTrace(System.err);
            exitWithError();
        }


        // set up an IdClient client, then connectToServer and processQuery
        IdClient client = new IdClient();
        // asks servers in random order for coordinator address until success
        Collections.shuffle(hostnames);
        String coordinatorHostname = null;
        for (String hostname : hostnames) {
            try {
                client.connectToServer(hostname, portNumber);
                coordinatorHostname = client.getCoordinatorName();
                if (coordinatorHostname == null) {
                    continue;
                }
                if (!hostnames.contains(coordinatorHostname)) {
                    System.err.println("Retrieved coordinator address " + coordinatorHostname + " is not in our list of servers");
                    exitWithError();
                }
                System.err.println("Located coordinator " + coordinatorHostname + " via replica " + hostname);
                break;
            } catch (RemoteException e) {
                // TEST PRINT (might be worth keeping though?)
                System.err.println("Asking remote server " + hostname + " for coordinator failed");
            }
        }

        if (coordinatorHostname == null) {
            System.err.println("Could not get coordinator address from any listed server!");
            exitWithError();
        }

        // perform actual command on coordinator
        try {
            client.connectToServer(coordinatorHostname, portNumber);
            client.processQuery(queryArgs);
        } catch (RemoteException e) {
            System.err.println("Query or connection to coordinator server failed");
            e.printStackTrace(System.err);
            exitWithError();
        }
    }

    /**
     * Retrieves and returns coordinator name from connected server.
     *
     * @return coordinator's name
     */
    String getCoordinatorName() {
        try {
            return idServerStub.getCoordinator().responseText;
        } catch (Exception e) {
            System.err.println("Failed to retrieve coordinator address from connected server.");
//            e.printStackTrace(System.err);
        }
        return null;
    }

    /**
     * This method sets system properties and forms a connection to the IdServer.
     *
     * @param host represents name of host
     * @param port represents host's port number
     * @throws RemoteException connection failed during connectToServer
     */
    void connectToServer(String host, int port) throws RemoteException {
        try {
            // set system properties
            System.setProperty("java.security.policy", "src/resources/mysecurity.policy");
            System.setProperty("javax.net.ssl.trustStore", "src/resources/Client_Truststore");
            System.setProperty("javax.net.ssl.trustStorePassword", "examplepassword");

            // locate registry and look up IdServer
            Registry registry = LocateRegistry.getRegistry(host, port);
            idServerStub = (IdServerInterface) registry.lookup("IdServer");
        } catch (NotBoundException | IOException e) {
            // print failure message upon exception
            System.err.println("Retrieving remote server binding failed");
//            e.printStackTrace(System.err);
            throw new RemoteException();
        }
    }

    /**
     * This method uses a switch statement to determine which query was input,
     * prepares objects needed for query, and queries the server.
     *
     * @param queryArgs represents all arguments for this query
     * @throws RemoteException connection failed during processQuery
     * @returns boolean representing success
     */
    boolean processQuery(String[] queryArgs) throws RemoteException {
        // boolean representing success (for return)
        boolean success = false;

        // declare variables that are needed by some modes
        String loginName;
        String password;
        String uuid;

        // declare ServerResponse for communicating with server
        ServerResponse response;

        // switch to mode based on queryArgs input
        String queryType = queryArgs[0];
        try {
            switch (queryType) {

                // create a new set of credentials
                case "--create":
                case "-c":
                    expectMinArgsForQuery(4, queryArgs);
                    loginName = queryArgs[1];
                    String realName;
                    if (flagIsNotPassword(queryArgs[2])) {
                        // real name specified, password after
                        // strip quotes from inputted string if present
                        realName = queryArgs[2].replace("\"", "");
                        assertFlagIsPassword(queryArgs[3]);
                        password = hashPassword(queryArgs[4]);
                    } else {
                        // no real name specified, password flag here
                        realName = System.getProperty("user.name");
                        password = hashPassword(queryArgs[3]);
                    }
                    response = idServerStub.create(loginName, realName, password);

                    // prints message if error is encountered+
                    switch (response.error) {
                        case NONE:
                            uuid = response.responseText;
                            System.out.println("Created login entry for name " + loginName + " (" + (realName != null ? "real name " + realName : "no real name specified") + ") with UUID " + uuid);
                            success = true;
                            break;
                        case NAME_COLLISION:
                            System.err.println("User with name '" + loginName + "' already exists");
                            break;
                        default:
                            unexpectedErrorType(response.error);
                            break;
                    }
                    break;

                // look up existing credentials by username
                case "--lookup":
                case "-l":
                    expectMinArgsForQuery(2, queryArgs);
                    loginName = queryArgs[1];

                    response = idServerStub.lookup(loginName);
                    switch (response.error) {
                        case NONE:
                            System.out.println(response.responseText);
                            success = true;
                            break;
                        case NO_SUCH_USER:
                            System.err.println("User with name '" + loginName + "' does not exist");
                            break;
                        default:
                            unexpectedErrorType(response.error);
                            break;
                    }
                    break;

                // look up existing credentials by uuid
                case "--reverse-lookup":
                case "-r":
                    expectMinArgsForQuery(2, queryArgs);
                    uuid = queryArgs[1];

                    response = idServerStub.reverseLookup(uuid);
                    switch (response.error) {
                        case NONE:
                            System.out.println(response.responseText);
                            success = true;
                            break;
                        case NO_SUCH_USER:
                            System.err.println("Requested UUID does not exist");
                            break;
                        default:
                            unexpectedErrorType(response.error);
                            break;
                    }
                    break;

                // modify a user's username.
                case "--modify":
                case "-m":
                    expectMinArgsForQuery(5, queryArgs);
                    String oldLoginName = queryArgs[1];
                    String newLoginName = queryArgs[2];
                    assertFlagIsPassword(queryArgs[3]);
                    password = hashPassword(queryArgs[4]);

                    response = idServerStub.modify(oldLoginName, newLoginName, password);
                    switch (response.error) {
                        case NONE:
                            System.out.println("User modification succeeded");
                            success = true;
                            break;
                        case NO_SUCH_USER:
                            System.err.println("Old login name '" + oldLoginName + "' does not exist");
                            break;
                        case INCORRECT_PASSWORD:
                            System.err.println("Incorrect password");
                            break;
                        case NAME_COLLISION:
                            System.err.println("New name '" + newLoginName + "' is already taken");
                            break;
                        default:
                            unexpectedErrorType(response.error);
                            break;
                    }
                    break;

                // delete an existing user
                case "--delete":
                case "-d":
                    expectMinArgsForQuery(4, queryArgs);
                    loginName = queryArgs[1];
                    assertFlagIsPassword(queryArgs[2]);
                    password = hashPassword(queryArgs[3]);

                    response = idServerStub.delete(loginName, password);
                    switch (response.error) {
                        case NONE:
                            System.out.println("Delete succeeded");
                            success = true;
                            break;
                        case NO_SUCH_USER:
                            System.err.println("Login name '" + loginName + "' does not exist");
                            break;
                        case INCORRECT_PASSWORD:
                            System.err.println("Incorrect password");
                            break;
                        default:
                            unexpectedErrorType(response.error);
                            break;
                    }
                    break;

                // gets specified information for all users: users for usernames, uuids for uuids
                case "--get":
                case "-g":
                    expectMinArgsForQuery(2, queryArgs);
                    String whatToGet = queryArgs[1];

                    response = idServerStub.get(whatToGet);
                    switch (response.error) {
                        case NONE:
                            System.out.println(response.responseText);
                            success = true;
                            break;
                        case MALFORMED_INPUT:
                            System.err.println("Get must request one of users, uuids, or all");
                            printQueryUsage();
                            break;
                        default:
                            unexpectedErrorType(response.error);
                            break;
                    }
                    break;

                // prints notification that query is unrecognized and usage, then exits
                default:
                    System.err.println("Unrecognized query type '" + queryType + "'");
                    printQueryUsage();
                    break;
            }
        } catch (IncorrectPasswordException e) {
            // makes sure success is false
            success = false;
        }

        // returns whether success is true
        return success;
    }

    /**
     * handles unexpected error types by printing error message and exiting
     *
     * @param response Error type that wasn't expected
     */
    private static void unexpectedErrorType(ErrorType response) {
        System.err.println("Received unexpected error type " + response.toString());
    }

    /**
     * if input string flag isn't equal to the password flag, prints usage and exits.
     *
     * @param flag String flag typed into the command line
     */
    private static void assertFlagIsPassword(String flag) {
        if (flagIsNotPassword(flag)) {
            System.err.println("Must specify a password");
            printQueryUsage();
            throw new IncorrectPasswordException();
        }
    }

    /**
     * returns whether the password flag is equal to a recognized password flag.
     *
     * @param flag String flag typed into the command line
     * @return boolean of whether flag equals recognized password flag
     */
    private static boolean flagIsNotPassword(String flag) {
        return (!flag.equals("-p") && !flag.equals("--password"));
    }

    /**
     * checks whether the number of args is valid for this query.
     *
     * @param requiredArgs number of required arguments for this query
     * @param argsList     list of arguments input to this query
     */
    private static void expectMinArgsForQuery(int requiredArgs, String[] argsList) {
        if (argsList.length < requiredArgs) {
            System.err.println("Specified query type requires " + requiredArgs + " args (got " + argsList.length + ")");
            printUsageAndExit();
        }
    }

    /**
     * hashes the input string and returns result
     *
     * @param input password to be hashed
     * @return fully hashed password
     */
    private static String hashPassword(String input) {
        String output = null;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] bytes = input.getBytes();
            md.reset();
            output = new String(md.digest(bytes));
        } catch (Exception e) {
            System.out.println("Error while hashing password.");
            e.printStackTrace();
        }
        return output;
    }

    /**
     * prints usage information
     */
    private static void printUsage() {
        System.err.println("Usage: $ java IdClient --server <serverhosts> --numport <port#> <query>");
        System.err.println();
        printQueryUsage();
    }

    /**
     * prints full query usage information
     */
    private static void printQueryUsage() {
        System.err.println("Exactly one of the following queries must be specified:");
        System.err.println("--create <loginname> [<real name>] --password <password>");
        System.err.println("With this option, the client contacts the server and attempts to create the new login name.The client optionally provides the real user name and password along with the request.");
        System.err.println();
        System.err.println("--lookup <loginname>");
        System.err.println("With this option, the client connects with the server and looks");
        System.err.println("up the loginname and displays all information found associated with the login name");
        System.err.println("(except for the encrypted password).");
        System.err.println();
        System.err.println("--reverse-lookup <UUID>");
        System.err.println("With this option, the client connects with the server and looks");
        System.err.println("up the UUID and displays all information found associated with the UUID (except for");
        System.err.println("the encrypted password).");
        System.err.println();
        System.err.println("--modify <oldloginname> <newloginname> --password <password>");
        System.err.println("The client contacts the server and requests a loginname change. If the new login name is available,");
        System.err.println("the server changes the name (note that the java.util.UUID does not ever change, once it has been");
        System.err.println("assigned). If the new login name is taken, then the server returns an error.");
        System.err.println();
        System.err.println("--delete <loginname> --password <password>");
        System.err.println("The client contacts the server and requests to delete their loginname. The client must supply the");
        System.err.println("correct password for this operation to succeed.");
        System.err.println();
        System.err.println("--get users|uuids|all The client contacts the server and obtains either a list all login");
        System.err.println("names, list of all UUIDs or a list of user, UUID and string description all accounts");
    }

    /**
     * printUsages and exitwithErrors
     */
    private static void printUsageAndExit() {
        printUsage();
        exitWithError();
    }

    /**
     * exits with status as 1
     */
    private static void exitWithError() {
        System.exit(1);
    }

    /**
     * class represents an exception where the input password is incorrect
     */
    private static class IncorrectPasswordException extends RuntimeException {
        public IncorrectPasswordException() {
            super("Incorrect password");
        }
    }
}
