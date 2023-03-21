package src.Server;


import java.io.Serializable;
import java.util.Date;
import java.util.UUID;

/**
 * This class represents the LoginInfo for a user. It has a toString
 * and a changeLoginName function.
 */
public class LoginInfo implements Serializable {
    String loginName;
    final UUID uuid;
    final String realName;
    final String creatorIpAddr;
    final Date createdDate;
    final Date lastChangeDate;
    final String password;

    public LoginInfo(String loginName, String realName, String password, String creatorIpAddr) {
        this.loginName = loginName;
        this.uuid = UUID.randomUUID();
        this.realName = realName;
        this.creatorIpAddr = creatorIpAddr;
        this.createdDate = new Date();
        this.lastChangeDate = createdDate;
        this.password = password;

    }

    /**
     * returns a string representing the user's loginInfo
     *
     * @return String
     */
    @Override
    public String toString() {
        return "LoginInfo{" + "loginName=" + loginName + ", uuid=" + uuid + ", realName='" + realName + '\'' + ", creatorIpAddr='" + creatorIpAddr + '\'' + ", createdDate=" + createdDate + ", lastChangeDate=" + lastChangeDate + '}';
    }

    /**
     * changes the loginName of this user
     *
     * @param newName new loginName for this user
     */
    public void changeLoginName(String newName) {
        this.loginName = newName;
    }
}
