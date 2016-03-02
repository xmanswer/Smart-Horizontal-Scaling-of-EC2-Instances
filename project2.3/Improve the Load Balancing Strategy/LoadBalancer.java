import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.URL;
import java.net.URLConnection;
import java.net.UnknownHostException;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.PriorityQueue;
import java.util.Queue;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.PriorityBlockingQueue;

public class LoadBalancer2 {
	private static final int THREAD_POOL_SIZE = 4;
	private final ServerSocket socket;
	private final DataCenterInstance[] instances;
	private PriorityQueue<DataCenterInstance> minHeap;

	public LoadBalancer2(ServerSocket socket, DataCenterInstance[] instances) {
		this.socket = socket;
		this.instances = instances;
		
		//create a min heap for holding DCI in asc order of cpu utilization
		this.minHeap = new PriorityQueue<DataCenterInstance>(3, new compareCPU());
		instances[0].setcpuUtil(0d);
		instances[1].setcpuUtil(1d);
		instances[2].setcpuUtil(2d);
		
		minHeap.add(instances[0]);
		minHeap.add(instances[1]);
		minHeap.add(instances[2]);
	}

	// Complete this function
	public void start() throws IOException {
		ExecutorService executorService = Executors.newFixedThreadPool(THREAD_POOL_SIZE);
		while (true) {
			while(minHeap.size() > 0) {
				//get the DCI with the minimum cpu util from min heap 
				DataCenterInstance currInstance = minHeap.poll();
				for(int j = 0; j < 2; j++) { //for each DCI, send two requests
					Runnable requestHandler = new RequestHandler(socket.accept(), currInstance);
					executorService.execute(requestHandler);
				}
			}
			//re-build the min heap based on newly queried cpu util
			for(int i = 0; i < instances.length; i++) {
				instances[i].setcpuUtil(queryDCI(instances[i].getUrl()));
				minHeap.add(instances[i]);
			}
		}
	}
	
	//comparator for min heap
	private class compareCPU implements Comparator<DataCenterInstance>{
		@Override
		public int compare(DataCenterInstance first, DataCenterInstance second) {
			return (int)(first.getcpuUtil() - second.getcpuUtil());
		}
	}
	
	//send query to sepcific DCI to get cpu util
	public Double queryDCI(String dciDNS) throws IOException {
			String url = dciDNS + ":8080/info/cpu";
			URL dciQuery = new URL(url);
			HttpURLConnection dciQueryConnection = (HttpURLConnection) dciQuery.openConnection();

			Double cpuUtil = 0d;
			String inputLine = null;
			BufferedReader in = new BufferedReader(new InputStreamReader(
									dciQueryConnection.getInputStream()));
			
			//parse the html for cpu util		
			if ((inputLine = in.readLine()) != null) {
				int pre = inputLine.indexOf("<body>") + "<body>".length();
				int pos = inputLine.indexOf("</body>");
				String cpuUtilStr = inputLine.substring(pre, pos);
				try {
					cpuUtil = Double.parseDouble(cpuUtilStr);
				} catch(NumberFormatException nfe) {
					//if return null, just set it to be really high
					in.close();
					return 200d;
				}
			}
			in.close();

			return cpuUtil;
	}
}
