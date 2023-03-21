package src.Client;


import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.rmi.RemoteException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * class represents a testing suite for IdClient
 * made for CS455 Project 2 March 2022
 *
 * @author ~Anna Rift~
 */
public class IdClientTesting {
    private static final String TESTING_HOST = "localhost";
    private static final int TESTING_PORT = 5180;
    private static final PrintStream originalOut = System.out;
    private static final PrintStream originalErr = System.err;
    private static final String CORRECT_PW = "correctpassword";
    private static final String INCORRECT_PW = "incorrectpassword";

    private static int currentTestNumber = 0;

    /**
     * Main method for IdClientTesting. Runs all tests and handles exceptions.
     *
     * @param args String[] of arguments input from the commandline
     * @throws InterruptedException the thread has decided it didn't want to help you
     */
    public static void main(String[] args) {
        try {
            runAllTests();
        } catch (TestFailureException e) {
            restoreFilePointers();
            System.err.println("Test " + currentTestNumber + " failed");
            e.printStackTrace(System.err);
            System.exit(2);
        } catch (RemoteException e) {
            restoreFilePointers();
            System.err.println("Exception during testing");
            e.printStackTrace(System.err);
            System.exit(3);
        }
    }

    /**
     * Runs many different tests on IdClient.
     * Uses testQuery extensively.
     *
     * @throws RemoteException connection has failed
     */
    private static void runAllTests() throws RemoteException {
        // checks basic response on --create mode success
        testQuery("--create arift \"Anna Rift\" --password " + CORRECT_PW,
                true,
                new String[]{"Created login entry for name arift (real name Anna Rift)"},
                null);
        // checks response on --create mode where the input loginName is already in use
        testQuery("--create arift \"Anna Rift\" --password " + CORRECT_PW,
                false,
                null,
                new String[]{"arift", "already exists"});
        // another check on basic response on --create mode success
        testQuery("--create lhurd \"Logan Hurd\" --password " + CORRECT_PW,
                true,
                new String[]{"Created login entry for name lhurd (real name Logan Hurd)"},
                null);
        // another check on basic response on --create mode success except without a realName
        testQuery("--create testuser --password " + CORRECT_PW,
                true,
                new String[]{"Created login entry for name testuser"},
                null);
        // checks response of --get in the event that 'users' is input
        testQuery("--get users",
                true,
                new String[]{"arift", "lhurd", "testuser"},
                null);
        // checks response of --get in the event that 'all' is input
        testQuery("--get all",
                true,
                new String[]{"arift", "lhurd", "testuser"},
                null);
        // checks response of --delete in the event that the password is incorrect
        testQuery("--delete arift --password " + INCORRECT_PW,
                false,
                null,
                new String[]{"Incorrect password"});
        // checks response of --modify in the event that the password is incorrect
        testQuery("--modify arift avrift --password " + INCORRECT_PW,
                false,
                null,
                new String[]{"Incorrect password"});
        // checks response of --modify in the event that the password is correct but loginName is taken
        testQuery("--modify arift lhurd --password " + CORRECT_PW,
                false,
                null,
                new String[]{"New name 'lhurd' is already taken"});
        // checks response of --delete in the event that the password is correct
        testQuery("--delete lhurd --password " + CORRECT_PW,
                true,
                new String[]{"Delete succeeded"},
                null);
        // checks response of --modify in the event that password is correct and loginName is not taken
        testQuery("--modify testuser lhurd --password " + CORRECT_PW,
                true,
                new String[]{"User modification succeeded"},
                null);
        // checks response of --modify in the event that password is correct and loginName is not taken (again)
        testQuery("--modify arift avrift --password " + CORRECT_PW,
                true,
                new String[]{"User modification succeeded"},
                null);
        // checks response of --get when input is 'users' after previous changes
        testQuery("--get users",
                true,
                new String[]{"[avrift, lhurd]"},
                null);
        // checks response of --lookup when the input loginName does not exist
        testQuery("--lookup nosuchuser",
                false,
                null,
                new String[]{"does not exist"});
        // checks response of --lookup when the input loginName does exist
        testQuery("--lookup avrift",
                true,
                new String[]{"LoginInfo", "loginName=avrift", "uuid=", "realName='Anna Rift'", "creatorIpAddr=", "createdDate=", "lastChangeDate="},
                null);
        // checks response of --lookup when the input loginName does exist
        testQuery("--lookup lhurd",
                true,
                new String[]{"loginName=lhurd"},
                null);
        // checks response of --reverse-lookup when input uuid does not exist
        testQuery("--reverse-lookup not_a_valid_uuid",
                false,
                null,
                new String[]{"Requested UUID does not exist"});
    }

    /**
     * Runs a test based off of inputs variables.
     *
     * @param query           contains the commandline arguments for the test
     * @param expectedSuccess contains whether the test is expected to succeed
     * @param outContains     is compared to the output of the test
     * @param errContains     is compared to any errors thrown by the test
     * @throws RemoteException connection failure
     */
    private static void testQuery(String query, boolean expectedSuccess, String[] outContains, String[] errContains) throws RemoteException {
        currentTestNumber++;
        System.out.println("Executing test " + currentTestNumber + ": " + query);
        // capture stdout and stderr for testing
        ByteArrayOutputStream capturedOut = new ByteArrayOutputStream();
        ByteArrayOutputStream capturedErr = new ByteArrayOutputStream();
        System.setOut(new PrintStream(capturedOut));
        System.setErr(new PrintStream(capturedErr));

        // connect to server and execute query
        IdClient client = new IdClient();
        client.connectToServer(TESTING_HOST, TESTING_PORT);
        // https://stackoverflow.com/a/7804472
        List<String> queryArgsList = new ArrayList<>();
        Matcher m = Pattern.compile("([^\"]\\S*|\".+?\")\\s*").matcher(query.trim());
        while (m.find()) {
            queryArgsList.add(m.group(1));
        }
        String[] queryArgs = queryArgsList.toArray(new String[0]);
        boolean success = client.processQuery(queryArgs);

        // complete capture and restore original stdout and stderr
        String capturedOutStr = capturedOut.toString().trim();
        String capturedErrStr = capturedErr.toString().trim();
        restoreFilePointers();
        // output results of capture from both to stdout
        System.out.println("Captured stdout: " + capturedOutStr);
        System.out.println("Captured stderr: " + capturedErrStr);

        // assert results are as expected
        // if we expect NO output, assert this specifically
        if (outContains != null) {
            for (String subStr : outContains) {
                assertContains(capturedOutStr, subStr);
            }
        } else {
            assertEmpty(capturedOutStr);
        }
        if (errContains != null) {
            for (String subStr : errContains) {
                assertContains(capturedErrStr, subStr);
            }
        } else {
            assertEmpty(capturedErrStr);
        }
        assertSuccess(success, expectedSuccess);
        System.err.println("Test " + currentTestNumber + " succeeded");
    }

    /**
     * Checks for a needle in the haystack
     *
     * @param haystack String
     * @param needle   possible substring
     */
    private static void assertContains(String haystack, String needle) {
        if (!haystack.contains(needle)) {
            assertionFailure("Haystack '" + haystack + "' did not contain needle '" + needle + "'");
        }
    }

    /**
     * Checks whether an input String is empty. If not, assertionFailure.
     *
     * @param shouldBeEmpty String that is supposed to be empty
     */
    private static void assertEmpty(String shouldBeEmpty) {
        if (!shouldBeEmpty.isEmpty()) {
            assertionFailure("String desired empty was '" + shouldBeEmpty + "'");
        }
    }

    /**
     * Checks if returned success is equal to expected success
     *
     * @param wasSuccess      returned success
     * @param expectedSuccess expected success
     */
    private static void assertSuccess(boolean wasSuccess, boolean expectedSuccess) {
        if (wasSuccess != expectedSuccess) {
            assertionFailure("Query expected to " + (expectedSuccess ? "succeed" : "fail") + " did not");
        }
    }

    /**
     * throws TestFailureException with msg as an argument
     *
     * @param msg TestFailureException input
     */
    private static void assertionFailure(String msg) {
        throw new TestFailureException(msg);
    }

    /**
     * restores file pointers
     */
    private static void restoreFilePointers() {
        System.setOut(originalOut);
        System.setErr(originalErr);
    }

    /**
     * exception that represents the failure of a test
     */
    private static class TestFailureException extends RuntimeException {
        public TestFailureException(String msg) {
            super(msg);
        }
    }
}
