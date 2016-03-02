import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.URL;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.services.ec2.AmazonEC2Client;
import com.amazonaws.services.ec2.model.CreateTagsRequest;
import com.amazonaws.services.ec2.model.Instance;
import com.amazonaws.services.ec2.model.Reservation;
import com.amazonaws.services.ec2.model.RunInstancesRequest;
import com.amazonaws.services.ec2.model.RunInstancesResult;
import com.amazonaws.services.ec2.model.StartInstancesRequest;
import com.amazonaws.services.ec2.model.StopInstancesRequest;
import com.amazonaws.services.ec2.model.Tag;

public class LoadBalancer {
	private static final int THREAD_POOL_SIZE = 4;
	private final ServerSocket socket;
	private final DataCenterInstance[] instances;
	private static Properties properties;
	private static BasicAWSCredentials bawsc;
	private static AmazonEC2Client ec2;
	private ArrayList<DataCenterInstance> runningList;
	private PriorityQueue<DataCenterInstance> minHeap;
	private static String subnetID = null;
	private static String sgID = null;
	private static ArrayList<String> waitingDCIIDs;
	private static final int CHECK_COUNTS = 10;
	private static final int FAIL_COUNTS = 3;

	public LoadBalancer(ServerSocket socket, DataCenterInstance[] instances) throws IOException {
		this.socket = socket;
		this.instances = instances;
		
		properties = new Properties();
		properties.load(LoadBalancer.class
				.getResourceAsStream("/AwsCredentials.properties"));
		bawsc = new BasicAWSCredentials(
				properties.getProperty("accessKey"),
				properties.getProperty("secretKey"));
		ec2 = new AmazonEC2Client(bawsc);
		
		//get subnet ID and security group ID
		List<Reservation> reservations = ec2.describeInstances()
				.getReservations();
		// iterate instances in reservations
		for (int i = 0; i < reservations.size(); i++) {
			List<Instance> ist = reservations.get(i).getInstances();
			if(ist.get(0).getState().getName().equals("running")) {
				subnetID = ist.get(0).getSubnetId();
				sgID = ist.get(0).getSecurityGroups().get(0).getGroupId();
				break;
			}
		}
		
		
		/* runningList is used to hold current healthy running DCIs
		   minHeap is used to keep running DCIs in CPU utilization order */
		minHeap = new PriorityQueue<DataCenterInstance>(3, new compareCPU());
		runningList = new ArrayList<DataCenterInstance>(3);
		for(int i = 0; i < this.instances.length; i++) {
			runningList.add(this.instances[i]);
			this.instances[i].setcpuUtil((double)i);
			minHeap.add(this.instances[i]);
		}
		
		//hold newly created DCI IDs awaiting for ready state 
		waitingDCIIDs = new ArrayList<String>();
	}

	// Complete this function
	public void start() throws IOException {
		ExecutorService executorService = Executors.newFixedThreadPool(THREAD_POOL_SIZE);
		while (true) {
			int i = 0;
			while(minHeap.size() > 0) {
				DataCenterInstance currInstance = minHeap.poll();
				for(int j = 0; j < 2; j++) { //send requests to each of them 2 times
					Runnable requestHandler = new RequestHandler(socket.accept(), currInstance);
					executorService.execute(requestHandler);
				}
			}
			
			//check all DCIs in running list for health
			for(i = 0; i < runningList.size(); i++) {
				DataCenterInstance currInstance = runningList.get(i);
				if(isHealthy(currInstance.getDns()) == false) {
					stopDCI(currInstance.getName()); //stop this DCI
					String stoppedID = null; //find stopped DCI
					if((stoppedID = getStoppedID()) != null) {
						startDCI(stoppedID);
						waitingDCIIDs.add(stoppedID);
					}
					else { //no stopped DCI, created a new one
						waitingDCIIDs.add(createDCI());
					}
					runningList.remove(i);
					break;
				}
			}
			
			//get CPU util for the remaining healthy DCIs and add to min heap
			for(i = 0; i < runningList.size(); i++) {
				DataCenterInstance currInstance = runningList.get(i);
				currInstance.setcpuUtil(queryDCI(currInstance.getDns()));
				minHeap.add(currInstance);
			}
			
			//check if any ready DCIs in the waiting list 
			for(i = 0; i < waitingDCIIDs.size(); i++) {
				String currDCIID = waitingDCIIDs.get(i);
				String currDNS = null;
				if((currDNS = getDNS(currDCIID)) != null) {
					//if DCI is already healthy, add to both runningList and minHeap 
					if(isHealthy(currDNS)) {
						String url = "http://" + currDNS;
						DataCenterInstance newDCI = new DataCenterInstance(currDCIID, url);
						runningList.add(newDCI);
						newDCI.setcpuUtil(queryDCI(newDCI.getDns()));
						minHeap.add(newDCI);
						waitingDCIIDs.remove(i);
						break;
					}
				}
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
			String url = "http://" + dciDNS + ":8080/info/cpu";
			URL dciQuery = new URL(url);
			HttpURLConnection dciQueryConnection = (HttpURLConnection) dciQuery.openConnection();

			Double cpuUtil = 0d;
			String inputLine = null;
			try {
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
			} catch(IOException ioe) {
				return 200d;
			}
			return cpuUtil;
	}
	
	//check if DCI with given DNS is healthy
	public static boolean isHealthy(String dciDNS) throws IOException {
		String url = "http://" + dciDNS + ":80/load?id=1505";
		URL dciQuery = new URL(url);
		HttpURLConnection dciQueryConnection = (HttpURLConnection) dciQuery.openConnection();
		dciQueryConnection.setConnectTimeout(100);
		int counter = 0;
		int failCounter = 0;
		while(counter < CHECK_COUNTS) {
			try {
				dciQueryConnection.setRequestMethod("HEAD");
			
				int responseCode = dciQueryConnection.getResponseCode();
				//System.out.println("response code: " + Integer.toString(responseCode));
				if(responseCode != 200) {
					failCounter++;
					if(failCounter > FAIL_COUNTS) 
						return false;
				}
			} catch (IOException ioe) {
				failCounter++;
				if(failCounter > FAIL_COUNTS) 
					return false;
			}
			counter++;
		}
		
		return (failCounter <= FAIL_COUNTS);
	}
	
	//stop a DCI
	public static void stopDCI(String dciID) {
		StopInstancesRequest stopReq = new StopInstancesRequest().withInstanceIds(dciID);
		ec2.stopInstances(stopReq);
	}
	
	//restart a stopped DCI
	public static void startDCI(String dciID) {
		StartInstancesRequest startReq = new StartInstancesRequest().withInstanceIds(dciID);
		ec2.startInstances(startReq);
	}
	
	/* create a DCI */
	public static String createDCI() throws IOException {

		RunInstancesRequest runDCIRequest = new RunInstancesRequest()
				.withImageId("ami-ed80c388")
				.withInstanceType("m3.medium")
				.withSubnetId(subnetID)
				.withMinCount(1).withMaxCount(1).withKeyName("15619project2")
				.withSecurityGroupIds(sgID);
		
		RunInstancesResult runDCIResult = ec2.runInstances(runDCIRequest);
		Instance newDCI = runDCIResult.getReservation().getInstances().get(0);
		String newDCIID = newDCI.getInstanceId();
		
		CreateTagsRequest createTagsRequest = new CreateTagsRequest();
		createTagsRequest.withResources(newDCIID).withTags(
				new Tag("Project", "2.3"));
		ec2.createTags(createTagsRequest);
		
		return newDCIID;
	}
	
	//get DNS based on dci ID, if not available, return null
	public static String getDNS(String dciID) {
		String dciDNS = null;
		List<Reservation> reservations = ec2.describeInstances()
				.getReservations();
		// iterate instances in reservations
		for (int i = 0; i < reservations.size(); i++) {
			List<Instance> instances = reservations.get(i).getInstances();
			// Return the Object Reference of the Instance just Launched
			Instance dci = instances.get(0);
			if(dci.getInstanceId().equals(dciID) && dci.getState().getName().equals("running")) {
				dciDNS = dci.getPublicDnsName();
				break;
			}
		}
		return dciDNS;
	}
	
	//get DNS based on dci ID, if not available, return null
	public static String getStoppedID() {
		String stoppedID = null;
		List<Reservation> reservations = ec2.describeInstances()
				.getReservations();
		// iterate instances in reservations
		for (int i = 0; i < reservations.size(); i++) {
			List<Instance> instances = reservations.get(i).getInstances();
			// Return the Object Reference of the Instance just Launched
			Instance dci = instances.get(0);
			if(dci.getState().getName().equals("stopped")) {
				stoppedID = dci.getInstanceId();
				break;
			}
		}
		return stoppedID;
	}
}