import java.io.*;
import java.time.LocalDateTime;

public class ServerLogger {
    private static final String FILE_NAME = "clientLogfile.log";

    public static synchronized void recieveClientLog(String serverID, String requestTimestamp, String clientID, String operationName, String parameters) {
        try  {
            FileWriter fileWriter = new FileWriter(FILE_NAME, true);
            String logEntry = String.format("Server-%s CLIENT-REQ %s %s Thread-%s %s %s%n", serverID, LocalDateTime.now(), requestTimestamp, clientID, operationName, parameters);            
            fileWriter.write(logEntry);
            fileWriter.flush();
            fileWriter.close();
        }

        catch (IOException e) {
            System.err.println("ServerLogger failed to log!");
        }
    }

    public static synchronized void recieveMulticastLog(String serverID, String requestTimestamp, String operationName, String parameters) {
        try  {
            FileWriter fileWriter = new FileWriter(FILE_NAME, true);
            String logEntry = String.format("Server-%s SRV-REQ %s %s %s %s%n", serverID, LocalDateTime.now(), requestTimestamp, operationName, parameters);            
            fileWriter.write(logEntry);
            fileWriter.flush();
            fileWriter.close();
        }

        catch (IOException e) {
            System.err.println("ServerLogger failed to log!");
        }
    }

    public static synchronized void removeLog(String serverID, String requestTimestamp) {
        try  {
            FileWriter fileWriter = new FileWriter(FILE_NAME, true);
            String logEntry = String.format("Server-%s REQ_PROCESSING %s %s%n", serverID, LocalDateTime.now(), requestTimestamp);            
            fileWriter.write(logEntry);
            fileWriter.flush();
            fileWriter.close();
        }

        catch (IOException e) {
            System.err.println("ServerLogger failed to log!");
        }
    }

    public static synchronized void haltResultLog(String serverID, String requestTimestamp, int sumAllAccountBalance, String requestQueue) {
        try  {
            FileWriter fileWriter = new FileWriter(FILE_NAME, true);
            String logEntry = String.format("Server-%s REQ_PROCESSING %s %s | Sum of the balance in all 20 accounts = %s | Request Queue = %s%n", serverID, LocalDateTime.now(), requestTimestamp, "" + sumAllAccountBalance, requestQueue);            
            fileWriter.write(logEntry);
            fileWriter.flush();
            fileWriter.close();
        }

        catch (IOException e) {
            System.err.println("ServerLogger failed to log!");
        }
    }
}


