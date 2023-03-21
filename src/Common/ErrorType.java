package src.Common;

/**
 * ErrorType is a public enum meant to represent different types of errors in our ID server implementation.
 *
 * @author Anna Rift
 */
public enum ErrorType {
    NONE,
    NO_SUCH_USER, // attempted call on a user that does not exist
    NAME_COLLISION, // input name is already taken
    INCORRECT_PASSWORD, // input password doesn't match what we have stored
    MALFORMED_INPUT, // input does not take the form it should
}
