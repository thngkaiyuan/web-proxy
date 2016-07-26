import java.net.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.text.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.io.*;

public class WebProxyThread extends Thread {

	// Class local cache
	private static Hashtable<String, WebProxyCachedObject> cache = new Hashtable<String, WebProxyCachedObject>();
	private static AtomicInteger cacheCounter = new AtomicInteger(0);
	
	// Instance variables
	private Socket clientSocket = null, serverSocket = null;
	private InputStream fromClient = null, fromServer = null;
	private OutputStream toClient = null, toServer = null;
	private String URL = null, requestString = null;
	private String[] censoredList = {};
	
	// Create a buffers to store the request and response
	private byte[] request = new byte[8192], response = new byte[8192];
	
	// default timeout value in ms for reading inputstream
	int timeout = 1000;
	
	// default timeout value in ms for connections
	int connTimeout = 20000;
	
	/**
	 * Constructor: takes in a client socket
	 * and initialises this thread
	 * @param socket
	 */
	public WebProxyThread(Socket socket) {
		super();
		clientSocket = socket;
		System.out.println("Received a connection from: " + clientSocket.toString());
	}
	
	/**
	 * Constructor: takes in a client socket and censored list
	 * and initialises this thread with the socket and censored list
	 * @param socket
	 */
	public WebProxyThread(Socket socket, String[] censoredList) {
		super();
		clientSocket = socket;
		this.censoredList = censoredList;
		System.out.println("Received a connection from: " + clientSocket.toString());
	}
	
	/**
	 * Everything is pretty much self-explanatory
	 */
	public void run() {
		// Create I/O streams for the client socket
		createClientStreams();
		
		// Process client's request
		processClientRequest();
		
		// Quits if this request is blank
		if(blankRequest()) {
			System.out.println("Blank request detected, closing client socket...");
			try {
				clientSocket.close();
			} catch (IOException e) {
				e.printStackTrace();
			}
			return;
		}
		
		// Performs caching check and related operations
		if(cached()) return;
		
		// Create socket and I/O streams to remote server
		if(!createServerSocketAndStreams()) return;
		
		// Send client's request to remote server
		sendRequestToServer();

		// Send server's response to client
		sendResponseToClient();
		
		return;
	}
	
	/**
	 * Checks if the current request is blank
	 * @return true if the request is blank, false otherwise
	 */
	private boolean blankRequest() {
		if(requestString == null) return true;
		return requestString.trim().equals("");
	}

	/**
	 * Checks if the requested object is cached
	 * and responds with the cached object if it is
	 * @return true if object is cached, false otherwise
	 */
	private boolean cached() {
		setURL();
		if(URL == null) return false;
		
		// Cached
		if(cache.containsKey(URL) && cacheUpToDate()) {
			// Retrieve the cached object
			WebProxyCachedObject thisCache = cache.get(URL);
			String filename = thisCache.filename;
			
			// Send the cached object to client
			try {
				System.out.println("Sending cached response...");
				if(thisCache.isText) sendCensoredToClient(thisCache.textCache);
				else sendDirectToClient(getFileInputStream(filename));
				System.out.println("Closing client socket...");
				clientSocket.close();
				System.out.println("Done.");
			} catch (IOException e) {
				e.printStackTrace();
			}
			return true;
		}
		
		// Not cached
		return false;
	}

	/**
	 * Checks if the current cached URL object is current on the server
	 * @return true if the object is current, false otherwise
	 */
	private boolean cacheUpToDate() {
		try {
			URLConnection conn = new URL(URL).openConnection();
			conn.setRequestProperty("If-Modified-Since", cache.get(URL).date);
			HttpURLConnection httpConn = (HttpURLConnection) conn;
			conn.setConnectTimeout(connTimeout);
			return httpConn.getResponseCode() == 304;
		} catch (MalformedURLException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		}
		return false;
	}

	/**
	 * Sends the server's response to the client and at the same time
	 * write the server's response to cache
	 */
	private void sendResponseToClient() {
		System.out.println("Sending response to client...");
		
		String filename = generateFilename(URL);
		OutputStream thisCache = getFileOutputStream(filename);
		ByteArrayOutputStream textCache = new ByteArrayOutputStream();
		
		boolean isText = false;
		int attempts = 0;
		int reads = 0;
		// attempts == total number of seconds to spend reading from
		// receive buffer until we terminate the client connection
		while(reads == 0 && attempts < 20){
			try {
				int bytes_length;
				while((bytes_length = fromServer.read(response)) != -1) {
					reads++;
					
					// Write to cache
					thisCache.write(response, 0, bytes_length);
					thisCache.flush();
					
					// During the first read, check if this response is text
					if(reads == 1) {
						textCache.write(response, 0, bytes_length);
						textCache.flush();
						byte[] array = getSubarray(response, 0, bytes_length-1);
						int endOfHeader = endOfHeader(array);
						String header = new String(getSubarray(array, 0, endOfHeader));
						isText = isText(header);
						if(!isText) {
							toClient.write(response, 0, bytes_length);
							toClient.flush();
						}
					
					// If the response is not text, we continue to write directly to client
					} else if (!isText) {
						toClient.write(response, 0, bytes_length);
						toClient.flush();
					
					// Else we write to a small cache meant for text only
					} else {
						textCache.write(response, 0, bytes_length);
					}
				}
			} catch (SocketException e){
				e.printStackTrace();
				System.out.println("Socket exception: " + serverSocket.toString());
				break;
			} catch (SocketTimeoutException e) {
				System.out.println("Socket timed out.");
			} catch (IOException e) {
				System.out.println("Error: Failed to send response to client.");
				e.printStackTrace();
				break;
			}
			attempts++;
		}
		
		// If the response is text, send the censored object to client
		if(isText) sendCensoredToClient(textCache);
		WebProxyCachedObject cachedObject = new WebProxyCachedObject(getServerTime(), filename, isText, textCache);
		cache.put(URL, cachedObject);
		closeSockets();
	}

	/**
	 * Returns an outputstream
	 * @param filename
	 * @return
	 */
	private OutputStream getFileOutputStream(String filename) {
		try {
			return Files.newOutputStream(Paths.get(filename));
		} catch (IOException e1) {
			System.out.println("Error: Failed to open outputstream to file: " + filename);
			e1.printStackTrace();
		}		
		return null;
	}

	/**
	 * Returns an inputstream
	 * @param filename
	 * @return
	 */
	private InputStream getFileInputStream(String filename) {
		try {
			return Files.newInputStream(Paths.get(filename));
		} catch (IOException e1) {
			System.out.println("Error: Failed to open inputstream to file: " + filename);
			e1.printStackTrace();
		}		
		return null;
	}

	/**
	 * Censors a response content before sending it to client
	 * @param thisCache
	 */
	private void sendCensoredToClient(ByteArrayOutputStream response) {
		byte[] filteredResponse = null;
		try {
			filteredResponse = filterResponse(response);
			toClient.write(filteredResponse);
			toClient.flush();
		} catch (IOException e) {
			System.out.println("Error: Failed to send response to client.");
			e.printStackTrace();
		}
	}

	/**
	 * Writes a given input stream to client
	 * @param stream
	 */
	private void sendDirectToClient(InputStream stream) {
		int bytes_length;
		byte[] buffer = new byte[8192];
		try {
			while((bytes_length = stream.read(buffer)) != -1) {			
				toClient.write(buffer, 0, bytes_length);
				toClient.flush();
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	/**
	 * Censors a byte stream 
	 * @param thisCache
	 * @return a censored byte stream
	 */
	private byte[] filterResponse(ByteArrayOutputStream response) {
		byte[] responseBytes = response.toByteArray();
		int endOfHeader = endOfHeader(responseBytes);
		String header = new String(getSubarray(responseBytes, 0, endOfHeader));
		if(!isText(header)) return responseBytes;
		for(String censoredWord : censoredList) {
			responseBytes = censorBytes(responseBytes, endOfHeader, censoredWord, "---");
		}
		return responseBytes;
	}
	
	/**
	 * Returns a subarray from an array
	 * @param responseBytes
	 * @param i
	 * @param endOfHeader
	 * @return
	 */
	private byte[] getSubarray(byte[] array, int start, int end) {
		if(array == null || array.length == 0) return array;
		byte[] result = new byte[end - start + 1];
		for(int i = 0; start + i <= end; i++) {
			result[i] = array[start + i];
		}
		return result;
	}

	/**
	 * Returns the index of the end of header for haystack
	 * @param haystack
	 * @return the index of the end of header for haystack, or defaultVal if the double CRLF cannot be found
	 */
	private int endOfHeader(byte[] haystack) {
		int offset = 0;
		byte[] needle = "\r\n\r\n".getBytes();
		int defaultVal = Math.max(0, haystack.length - 1);
		
		for(int i = offset; i < haystack.length; i++) {
			for(int j = 0; i + j < haystack.length && j < needle.length && (haystack[i + j] == needle[j]); j++) {
				if(j == needle.length - 1) return i - 1;
			}
		}
		
		return defaultVal;
	}

	/**
	 * Replaces censoredWord with replaceWith in the byteArray
	 * @param byteArray
	 * @param endOfHeader
	 * @param censoredWord
	 * @param replaceWith
	 * @return the censored byteArray
	 */
	private byte[] censorBytes(byte[] byteArray, int endOfHeader, String censoredWord, String replaceWith) {
		byte[] replaceBytes = replaceWith.getBytes();

		byte[] censorBytes = censoredWord.getBytes();
		byte[] censorBytesUp = censoredWord.toUpperCase().getBytes();
		byte[] censorBytesDown = censoredWord.toLowerCase().getBytes();
		
		for(int i = endOfHeader + 4; i < byteArray.length; i++) {
			for(int j = 0; i + j < byteArray.length && j < censorBytes.length && 
			((byteArray[i + j] == censorBytesUp[j]) || (byteArray[i + j] == censorBytesDown[j])); j++) {
				if(j == censorBytes.length - 1) {
					byte[] head = getSubarray(byteArray, 0, i-1);
					byte[] tail = getSubarray(byteArray, i+j+1, byteArray.length-1);					
					byteArray = combineThreeArrays(head, replaceBytes, tail);
					i += replaceWith.length() - 1;
				}
			}
		}
		
		return byteArray;
	}

	/**
	 * Combines first, second and third into one array
	 * @param first
	 * @param second
	 * @param third
	 * @return the combined array
	 */
	private byte[] combineThreeArrays(byte[] first, byte[] second, byte[] third) {
		byte[] result = new byte[first.length + second.length + third.length];
		int i = 0;
		for(byte b : first) {
			result[i] = b;
			i++;
		}
		for(byte b : second) {
			result[i] = b;
			i++;
		}
		for(byte b : third) {
			result[i] = b;
			i++;
		}
		return result;
	}

	/**
	 * Checks if a given header indicates that this response is text 
	 * @param responseString
	 * @return true if response is text, false otherwise
	 */
	private boolean isText(String header) {
		boolean isText = false;
		String[] lines = header.split("\\r?\\n");
		for(String line : lines) {
			String[] keyVal = line.split(":\\s+", 2);
			if(keyVal[0].equalsIgnoreCase("Content-Type")) {
				isText = keyVal[1].toLowerCase().contains("text/");
			} else if(keyVal[0].toLowerCase().contains("encoding")) {
				if(keyVal[1].toLowerCase().contains("gzip")) return false;
			}
		}
		return isText;
	}

	/**
	 * Attempts to close the client and server sockets
	 */
	private void closeSockets() {
		System.out.println("Closing sockets...");
		try {
			clientSocket.close();
			serverSocket.close();
		} catch (IOException e) {
			System.out.println("Error: Failed to close socket(s).");
			e.printStackTrace();
		}
		System.out.println("Done.");
	}

	/**
	 * Sends the existing request to the remote server
	 */
	private void sendRequestToServer() {
		try {
			System.out.println("Sending request to remote server...");
			// Write the request to server, then flush
			toServer.write(request);
			toServer.flush();
		} catch (IOException e1) {
			System.out.println("Error: Failed to send request to remote server.");
			e1.printStackTrace();
		}
	}

	/**
	 * Creates a socket and I/O streams for the remote server
	 * @return false if the host is unreachable, true otherwise
	 */
	private boolean createServerSocketAndStreams() {
		// Search in the input stream for the remote host address
		String address = getAddress(requestString);
		String host = getHost(address);
		int port = getPort(address);

		System.out.println("Connecting to remote server " + host + " at port " + port + "...");
		// Create server socket and streams
		try {
			serverSocket = new Socket();
			InetSocketAddress server = new InetSocketAddress(host, port);
			serverSocket.setSoTimeout(timeout);
			serverSocket.connect(server, connTimeout);
			fromServer = serverSocket.getInputStream();
			toServer = serverSocket.getOutputStream();
		} catch (Exception e) {
			System.out.println("Error: Failed to create socket/streams to remote server " + host);
			send502Response();
			e.printStackTrace();
			return false;
		}
		
		// Return true if everything is OK
		return true;
	}

	/**
	 * Sends a 502 Response to the client
	 */
	private void send502Response() {
		try {
			System.out.println("Error: Unknown host, sending 502 and closing socket.");
			toClient.write(get502ErrorMessage());
			toClient.flush();
			clientSocket.close();
		} catch (IOException e1) {
			e1.printStackTrace();
		}
	}

	/**
	 * Processes the client's request
	 */
	private void processClientRequest() {
		System.out.println("Processing the request...");		
		// Copy the request into our request buffer
		try {
			fromClient.read(request);
			requestString = new String(request);
		} catch (IOException e) {
			System.out.println("Error: Failed to read request from client's input stream.");
			e.printStackTrace();
		}
	}

	/**
	 * Creates an input/output stream for the existing client socket
	 */
	private void createClientStreams() {
		System.out.println("Creating client streams...");
		// Create client streams
		try {
			fromClient = clientSocket.getInputStream();
			toClient = clientSocket.getOutputStream();
		} catch (IOException e) {
			System.out.println("Error: Failed to create client streams.");
			e.printStackTrace();
			return;
		}
	}

	/**
	 * Parses a request string to obtain the host address
	 * @param request
	 * @return host address
	 */
	private String getAddress(String request) {
		String[] lines = request.split("\\r?\\n");
		for(String line : lines) {
			String[] keyVal = line.split(":\\s+", 2);
			if(keyVal[0].equalsIgnoreCase("Host")) return keyVal[1];
		}
		return "";
	}

	/**
	 * Parses an address string to get the host
	 * @param address (e.g. google.com:8080)
	 * @return host
	 */
	private String getHost(String address) {
		return address.split(":", 2)[0];
	}

	/**
	 * Parses a an address string to get the port number
	 * @param address (e.g. google.com:8080)
	 * @return port number
	 */
	private int getPort(String address) {
		String[] hostAddr = address.split(":", 2);
		if(hostAddr.length == 2) return Integer.parseInt(hostAddr[1]);
		else return 80;
	}
	
	/**
	 * Sets the request URL
	 * @param lines
	 */
	private void setURL() {
		String[] lines = requestString.split("\\r?\\n");
		String requestLine = lines[0];
		String[] requestAttr = requestLine.split("\\s+", 3);
		if(requestAttr.length != 3) return;
		URL = requestAttr[1];
	}

	/**
	 * Returns a 502 response in byte array
	 * @return 502 response in byte array
	 */
	private byte[] get502ErrorMessage() {
		String eol = "\r\n";
		String header = "HTTP/1.0 502 Bad Gateway" + eol;
		String body = "502 Error: Cannot reach server." + eol;
		return (header + eol + body + eol).getBytes();
	}
	
	/**
	 * Returns the current server time in HTTP format
	 * @return current server time in HTTP format
	 */
	private String getServerTime() {
	    Calendar calendar = Calendar.getInstance();
	    SimpleDateFormat dateFormat = new SimpleDateFormat(
	        "EEE, dd MMM yyyy HH:mm:ss z", Locale.US);
	    dateFormat.setTimeZone(TimeZone.getTimeZone("GMT"));
	    return dateFormat.format(calendar.getTime());
	}
	
	/**
	 * Generates a filename for the given URL
	 * @param url
	 * @return a generated filename for the given URL
	 */
	private String generateFilename(String url) {
		int thisCount = cacheCounter.getAndIncrement();
		return String.valueOf(thisCount) + ".cache";
	}
}
