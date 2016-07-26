import java.net.*;
import java.util.*;
import java.io.*;

public class WebProxy {
	// Port for the proxy
	private static int port;

	// Socket for client connections
	private static ServerSocket socket;
	
	// Defines whether messages will be shown
	private static boolean verbose = false; 
	private static PrintStream originalSysOut = null;
	private static PrintStream originalSysErr = null;
	
	// Array of censored words in whatever case
	private static String[] censoredWords = {};
	
	public static void main(String args[]) {		
		if(!verbose) disableOutput();
		
		initializeCensorshipList();
		
		// Attempt to create a socket listening at the given port
		if (!createSocketAtPort(args[0]))
			// Quits if the operation fails
			System.exit(1);
		
		// Spin off a thread for every incoming connection
		// in order to handle multiple connections (multi-threading)
		continuallyServeRequests();
		
		// Gracefully shutdown the proxy
		halt();
		
		if(!verbose) restoreOutput();		
		return;
	}
	
	/**
	 * Restores SysOut and SysErr
	 */
	private static void restoreOutput() {
		restoreSysOut();
		restoreSysErr();
	}

	/**
	 * Redirects SysOut and SysErr to nothing
	 */
	private static void disableOutput() {
		disableSysOut();
		disableSysErr();
	}

	/**
	 * Initializes the censorship list in censor.txt
	 */
	private static void initializeCensorshipList() {
		try (BufferedReader br = new BufferedReader(new FileReader("censor.txt"))) {
			List<String> words = new ArrayList<String>();
			String line;
		    while ((line = br.readLine()) != null) {
		       words.add(line);
		    }
		    censoredWords = new String[words.size()];
		    censoredWords = words.toArray(censoredWords);
		} catch (FileNotFoundException e) {
			System.out.println("No censor.txt found.");
			return;
		} catch (IOException e) {
			e.printStackTrace();
			return;
		}
	}

	/**
	 * Restores the original SysOut stream
	 */
	private static void restoreSysOut() {
	    System.setOut(originalSysOut);
	}

	/**
	 * Redirects SysOut to nothing
	 */
	private static void disableSysOut() {
		try {
		    originalSysOut = System.out;
		    System.setOut(new PrintStream(new OutputStream() {
		                public void write(int b) {
		                    //DO NOTHING
		                }
		            }));
		}
		catch (Exception e) {
		    e.printStackTrace();
		}
	}

	/**
	 * Restores the original SysErr stream
	 */
	private static void restoreSysErr() {
	    System.setOut(originalSysErr);
	}

	/**
	 * Redirects SysErr to nothing
	 */
	private static void disableSysErr() {
		try {
		    originalSysErr = System.err;
		    System.setErr(new PrintStream(new OutputStream() {
		                public void write(int b) {
		                    //DO NOTHING
		                }
		            }));
		}
		catch (Exception e) {
		    e.printStackTrace();
		}
	}

	/**
	 * Spins off a thread to handle this new client connection
	 */
	private static void continuallyServeRequests() {
		while (true) {
			try {
				new WebProxyThread(socket.accept(), censoredWords).start();
			} catch (IOException e) {
				e.printStackTrace();
				continue;
			}
		}
	}
	
	/**
	 * Attempts to close the socket gracefully
	 */
	private static void halt() {
		try {
			System.out.println("Closing the server socket...");
			socket.close();
		} catch (IOException e) {
			System.out.println("Error: Failed to close socket.");
			e.printStackTrace();
		}
	}
	
	/**
	 * Attempts to create a server socket listening at the input port
	 * @param inputPort
	 * @return true if successful, false otherwise
	 */
	private static boolean createSocketAtPort(String inputPort) {
		return assignPort(inputPort) && listenToPort();
	}
	
	/**
	 * Attempts to assign the listening port to the given input
	 * @param inputPort
	 * @return true if successful, false otherwise
	 */
	private static boolean assignPort(String inputPort) {
		boolean success = false;
		
		// Attempt to assign the port number
		try	{
			int portNumber = Integer.parseInt(inputPort);
			if (withinRange(portNumber)) {
				port = portNumber;
				success = true;
			} else {
				System.out.println("Error: Port number must be within the range of 0 - 65535.");
			}
		} catch(NumberFormatException e) {
			System.out.println("Error: Port number must be an integer.");
		}
		
		return success;
	}
	
	/**
	 * Attempts to listen to the assigned port number
	 * @return true if successful, false otherwise
	 */
	private static boolean listenToPort() {
		boolean success = false;
		
		// Try to create a server socket at the given port
		try{
			socket = new ServerSocket(port);
			success = true;
			System.out.println("Listening on port " + port + "...");
		} catch(Exception e) {
			System.out.println("Error: Failed to listen to port " + port + ".");
			e.printStackTrace();
		}
		
		return success;
	}
	
	/**
	 * Checks if a given port number is within the range
	 * of valid port numbers
	 * @param portNumber
	 * @return true if the given port number is valid, false otherwise
	 */
	private static boolean withinRange(int portNumber) {
		int lowerBound = 0;
		int upperBound = 65535;
		return (portNumber >= lowerBound && portNumber <= upperBound);
	}

}
