package src.Server;

import java.io.Serializable;

public class ActionObject implements Serializable {
    public enum ActionKind {
        CREATE,
        MODIFY,
        DELETE
    }

    public ActionObject(ActionKind kind, String loginName, String password, String data) {
        this.kind = kind;
        this.loginName = loginName;
        this.password = password;
        this.data = data;
    }

    public ActionKind kind;

    public String loginName;
    public String password;
    public String data;
}
