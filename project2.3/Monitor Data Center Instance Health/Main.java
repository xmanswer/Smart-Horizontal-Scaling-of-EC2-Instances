import java.io.IOException;
import java.net.ServerSocket;

public class Main {
	private static final int PORT = 80;
	private static DataCenterInstance[] instances;
	private static ServerSocket serverSocket;

	//Update this list with the DNS of your data center instances
	static {
		instances = new DataCenterInstance[3];
		instances[0] = new DataCenterInstance("i-524c80f0", "http://ec2-52-23-252-237.compute-1.amazonaws.com");
		instances[1] = new DataCenterInstance("i-8df23d2f", "http://ec2-54-164-17-123.compute-1.amazonaws.com");
		instances[2] = new DataCenterInstance("i-5bfb32f9", "http://ec2-52-91-203-193.compute-1.amazonaws.com");
	}

	public static void main(String[] args) throws IOException {
		initServerSocket();
		LoadBalancer loadBalancer = new LoadBalancer(serverSocket, instances);
		loadBalancer.start();
	}

	/**
	 * Initialize the socket on which the Load Balancer will receive requests from the Load Generator
	 */
	private static void initServerSocket() {
		try {
			serverSocket = new ServerSocket(PORT);
		} catch (IOException e) {
			System.err.println("ERROR: Could not listen on port: " + PORT);
			e.printStackTrace();
			System.exit(-1);
		}
	}
}
