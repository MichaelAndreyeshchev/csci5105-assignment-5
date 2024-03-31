import java.rmi.Remote;
import java.rmi.RemoteException;
import java.rmi.server.UnicastRemoteObject;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.rmi.Naming;
import java.rmi.NotBoundException;
import java.rmi.registry.*;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.PriorityBlockingQueue;
import java.io.BufferedReader;
import java.io.EOFException;
import java.io.FileReader;
import java.io.IOException;
import java.net.*;
import java.nio.file.*;

public class RMIBankServerImp implements RMIBankServer {
    private int serverID;
    private ConcurrentHashMap<Integer, Account> accounts = new ConcurrentHashMap<>(); // ConcurrentHashMap that maps unique account IDs to Account objects, representing the bank accounts managed by the server
    private AtomicInteger accountUIDCounter = new AtomicInteger(1); // generate unique IDs for new accounts, starting from 1
    private LamportClock clock = new LamportClock(); // Lamport clock to help with executing the same sequence of operations, using the State Machine Model
    private PriorityBlockingQueue<Request> requests = new PriorityBlockingQueue<>();
    private ConcurrentHashMap<Integer, LamportClock> ackRecieved = new ConcurrentHashMap<>();
    private Map<Integer, String> serverIDToAddress = new HashMap<>();
    private static int port;
    private static String hostname;
    //private Map<Integer, RMIBankServer> replicaServers = new HashMap();

    public RMIBankServerImp() throws RemoteException {
        super();
    }

    public RMIBankServerImp(String configFilePath, int serverID) throws IOException {
        super();
        this.serverID = serverID;
        loadConfigFile(configFilePath);

        for(int i = 1; i <= 20; i++) {
            Account account = new Account(i, 1000);
            accounts.put(i, account);
        }
        System.out.println("Initialization is complete! Ready to get requests...");
    }

    public void loadConfigFile(String configFilePath) throws RemoteException, IOException {
        BufferedReader reader = new BufferedReader(new FileReader(configFilePath));
        String line;
        while ((line = reader.readLine()) != null) {
            String[] config = line.split(" ");
            int serverID = Integer.parseInt(config[1]);

            if (serverID != this.serverID) {
                String serverAddress = "//" + config[0] + ":" + config[2] + "/Server_" + config[1];
                serverIDToAddress.put(serverID, serverAddress);
                ackRecieved.put(serverID, new LamportClock());
            }
        }
        reader.close();
    }

    public void shutdown() throws RemoteException  { // unbinds the server from the RMI registry and unexports the RMI object, effectively shutting down the server
        System.out.println("Server is terminating...");

        Registry localRegistry = LocateRegistry.getRegistry(hostname, port);
        try{
            localRegistry.unbind("Server_" + serverID);
        }
        catch (Exception e) {
            System.err.println("Server is not bound to the registry, finishing shutdown...");
        }

        UnicastRemoteObject.unexportObject(this, true);
        System.out.println("RMI Server Port Shutdown Completed!");
        System.exit(0);
    }

    public int createAccount() throws RemoteException { // generates a unique ID for a new account, creates an account, logs the operation, and stores it in the accounts map
        int sourceAcountUID = accountUIDCounter.getAndIncrement();
        Account account = new Account(sourceAcountUID);
        accounts.put(sourceAcountUID, account);

        return sourceAcountUID;
    }

    public String deposit(int sourceAcountUID, int amount) throws RemoteException { // adds a specified amount to an existing account and logs the operation
        Account account = accounts.get(sourceAcountUID);

        if (account != null) {
            account.deposit(amount);
            return "OK";
        }

        else {
            return "FAILED";
        }
    }

    public int getBalance(int sourceAcountUID) throws RemoteException { // retrieves the balance of a specified account and logs the operation
        Account account = accounts.get(sourceAcountUID);

        if (account != null) {
            int balance = account.getBalance();
            return balance;
        }

        else {
            return -1;
        }
    }

    public String transfer(int sourceAcountUID, int targetAccountUID, int amount) throws RemoteException { // transfers funds from one account to another if sufficient funds are available and logs the operation.
        Account account = accounts.get(sourceAcountUID);
        Account targetAccount = accounts.get(targetAccountUID);

        if (account != null && targetAccount != null && account.transfer(amount)) {
            targetAccount.deposit(amount);
            return "OK";
        }

        else {
            return "FAILED";
        }
    }

    public void halt(Request r) throws RemoteException, IOException {
        requests.remove(r);
        int balanceAllAccounts = 0;

        for (Map.Entry<Integer, Account> entry : accounts.entrySet()) {
            int balance = entry.getValue().getBalance();
            balanceAllAccounts += balance;
        }
        
        long totalProcessingTime = 0;
        int serverIDCounter = 0;

        List<String> clientLogEntries = Files.readAllLines(Paths.get("clientLogfile.log"));
        for (String line : clientLogEntries) {
            if (line.contains("\"RSP\"") && line.contains("SRV-0")) {
                long serverProcessingTime = Long.parseLong(line.split(" = ")[1]);
                totalProcessingTime += serverProcessingTime;
                serverIDCounter++;
            }
        }

        ServerLogger.haltResultLog(String.valueOf(serverID), "[" + r.getTimestamp() + ", " + r.getSendingServerID() + "]", balanceAllAccounts, requests.size() + "", totalProcessingTime / serverIDCounter);
        System.out.println(String.valueOf(serverID) +  " [" + r.getTimestamp() + ", " + r.getSendingServerID() + "] " + " " + balanceAllAccounts + " " +  requests.size());
        shutdown();
    }

    public int getServerID() throws RemoteException {
        return this.serverID;
    }
    public int syncClock(int timestamp){
        clock.update(timestamp);
        return clock.getTime();
    }

    public long clientRequest(Request request) throws RemoteException, MalformedURLException, NotBoundException, IOException {        
        long t0 = System.nanoTime();
        int logicalTime = clock.increment();
        request.setTimestamp(logicalTime);
        requests.add(request);
        //ServerLogger.recieveClientLog(String.valueOf(serverID), "[" + logicalTime + ", " + this.serverID + "]", "" + request.getSendingServerID(), request.getRequestType(), " " + request.getSourceAccountUID() + " to " + request.getTargetAccountUID());
        request.SetSendingServerID(this.serverID);

        cast(request);

        processRequest(request);
        long t1 = System.nanoTime();

        return t1 - t0;
    }

    public synchronized void cast(Request request) throws RemoteException, MalformedURLException, NotBoundException {
        for (Map.Entry<Integer, String> entry : serverIDToAddress.entrySet()) {
            int replicaID = entry.getKey();
            String replicaAddress = entry.getValue();
            RMIBankServer replica = (RMIBankServer) Naming.lookup(replicaAddress);
            int timestampOther = replica.multicast(request, this.serverID);
            clock.update(timestampOther);
            ackRecieved.get(replicaID).updateNoIncrement(timestampOther);
        }
    }

    public int multicast(Request request, int senderID) throws RemoteException, MalformedURLException, NotBoundException { // This is after I recieve from main server
        requests.add(request);
        clock.update(request.getTimestamp());
        //ServerLogger.recieveMulticastLog(String.valueOf(serverID), "[" + request.getTimestamp() + ", " + request.getSendingServerID() + "]", request.getRequestType(), "");

        return clock.getTime();
    }

    public boolean executeRequestCheck(Request request) throws RemoteException {
        for (Integer replicaID : ackRecieved.keySet()) {
            int timestamp = ackRecieved.get(replicaID).getTime();
            if (timestamp < request.getTimestamp()) {
                return false;
            }
            else if (timestamp == request.getTimestamp() && replicaID < request.getSendingServerID()){
                return false;
            }
        }
        int timestamp = clock.getTime();
        if (timestamp < request.getTimestamp()){
            return false;
        }
        else if (timestamp == request.getTimestamp() && this.serverID < request.getSendingServerID()){
            return false;
        }
        else {
            return true;
        }
    }

    public void processRequest(Request request) throws MalformedURLException, RemoteException, NotBoundException, IOException {
        while (!requests.peek().equals(request) || executeRequestCheck(request) == false) {}
        System.out.println(clock.getTime());
        castExecute(request);
    }

    public synchronized void castExecute(Request request) throws RemoteException, MalformedURLException, NotBoundException, IOException {
        if (request.getRequestType().equals("halt")) {
            for (String replicaAddress : serverIDToAddress.values()) {
                RMIBankServer replica = (RMIBankServer) Naming.lookup(replicaAddress);
                // Causes error always when process exits
                try{
                    replica.executeRequest(request);
                }            
                catch (RemoteException e){}
    
            }
            executeRequest(request);
        }
        else {
            for (String replicaAddress : serverIDToAddress.values()) {
                RMIBankServer replica = (RMIBankServer) Naming.lookup(replicaAddress);
                replica.executeRequest(request);
            }
            executeRequest(request);
        }
        

    }

    public void executeRequest(Request request) throws RemoteException, IOException {
        while (!requests.peek().equals(request)){}

        switch (request.getRequestType()) {
            case "createAccount":
                createAccount();
                break;
            case "deposit":
                deposit(request.getSourceAccountUID(), request.getAmount());
                break;
            case "transfer":
                transfer(request.getSourceAccountUID(), request.getTargetAccountUID(), request.getAmount());
                break;

            case "halt":
                halt(request);
                break;
            default:
                System.err.println("ERROR: Unknown Request Type: " + request.getRequestType());
                break;
        }
        ServerLogger.removeLog(String.valueOf(serverID), "[" + request.getTimestamp() + ", " + request.getSendingServerID() + "]");
        requests.remove(request);
    }
    public static void main (String args[]) throws Exception {
        if (args.length != 2) {
            System.err.println("Usage: java RMIBankServerImp <serverID> <configFilePath>");
            System.exit(1);
        }

        try {
            hostname = "";
            port = -1;
            int serverID = Integer.parseInt(args[0]);
            String configFilePath = args[1];
            BufferedReader reader = new BufferedReader(new FileReader(configFilePath));
            String line;
            while ((line = reader.readLine()) != null) {
                String[] config = line.split(" ");

                if (serverID == Integer.parseInt(config[1])) {
                    hostname = config[0];
                    port = Integer.parseInt(config[2]);
                }
            }
            reader.close();

            if (port == -1) {
                throw new Exception("Entered a non existant serverID!");
            }

            Registry registry;

            try {
                registry = LocateRegistry.createRegistry(port);
            }

            catch (Exception e) {
                registry = LocateRegistry.getRegistry(port);
            }

            

            RMIBankServerImp bankServer = new RMIBankServerImp(configFilePath, serverID);
            
            System.setProperty("java.rmi.server.hostname", hostname);
            RMIBankServer bankServerStub = (RMIBankServer) UnicastRemoteObject.exportObject(bankServer, 0) ;


            //Registry registry = LocateRegistry.createRegistry(5000 + serverID); // Example port assignment logic
            try {
                registry.bind("Server_" + serverID, bankServerStub);
            }

            catch (Exception e) {
                registry.rebind("Server_" + serverID, bankServerStub);
            }
        
            System.out.println("Server " + serverID + " is ready.");
            System.out.println("//" + hostname + ":" + port + "/Server_" + serverID);
        }

        catch (Exception e) {
            e.printStackTrace();
        }
    }
}

