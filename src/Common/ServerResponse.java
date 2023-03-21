package src.Common;

import java.io.Serializable;

/**
 * Class to represent ServerResponses item to be passed by server
 *
 * @author Anna Rift
 */
public class ServerResponse implements Serializable {
    public String responseText;
    public ErrorType error;

    /**
     * constructor for error type response
     *
     * @param error error that is to be communicated
     */
    public ServerResponse(ErrorType error) {
        this.responseText = "";
        this.error = error;
    }

    /**
     * constructor for text type response
     *
     * @param responseText text that is to be communicated
     */
    public ServerResponse(String responseText) {
        this.responseText = responseText;
        this.error = ErrorType.NONE;
    }

}
