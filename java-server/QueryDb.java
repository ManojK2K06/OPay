import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

public class QueryDb {
    public static void main(String[] args) throws Exception {
        Class.forName("org.h2.Driver");
        Connection conn = DriverManager.getConnection("jdbc:h2:file:./data/opaydb", "opay", "opay_secret");
        Statement stmt = conn.createStatement();
        ResultSet rs = stmt.executeQuery("SELECT ACCOUNT_NUMBER, PUBLIC_KEY_BASE64 FROM USER_KEYS");
        while (rs.next()) {
            System.out.println(rs.getString(1) + ": " + rs.getString(2));
        }
        conn.close();
    }
}
