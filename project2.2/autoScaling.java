import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

import com.amazonaws.AmazonServiceException;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.services.autoscaling.AmazonAutoScalingClient;
import com.amazonaws.services.autoscaling.model.CreateAutoScalingGroupRequest;
import com.amazonaws.services.autoscaling.model.CreateLaunchConfigurationRequest;
import com.amazonaws.services.autoscaling.model.DeleteAutoScalingGroupRequest;
import com.amazonaws.services.autoscaling.model.DeleteLaunchConfigurationRequest;
import com.amazonaws.services.autoscaling.model.PutScalingPolicyRequest;
import com.amazonaws.services.autoscaling.model.PutScalingPolicyResult;
import com.amazonaws.services.autoscaling.model.UpdateAutoScalingGroupRequest;
import com.amazonaws.services.cloudwatch.AmazonCloudWatchClient;
import com.amazonaws.services.cloudwatch.model.ComparisonOperator;
import com.amazonaws.services.cloudwatch.model.Dimension;
import com.amazonaws.services.cloudwatch.model.PutMetricAlarmRequest;
import com.amazonaws.services.cloudwatch.model.StandardUnit;
import com.amazonaws.services.cloudwatch.model.Statistic;
import com.amazonaws.services.ec2.AmazonEC2Client;
import com.amazonaws.services.ec2.model.AuthorizeSecurityGroupIngressRequest;
import com.amazonaws.services.ec2.model.CreateSecurityGroupRequest;
import com.amazonaws.services.ec2.model.CreateSecurityGroupResult;
import com.amazonaws.services.ec2.model.CreateTagsRequest;
import com.amazonaws.services.ec2.model.DeleteSecurityGroupRequest;
import com.amazonaws.services.ec2.model.DescribeInstanceStatusRequest;
import com.amazonaws.services.ec2.model.DescribeInstanceStatusResult;
import com.amazonaws.services.ec2.model.DescribeSecurityGroupsRequest;
import com.amazonaws.services.ec2.model.DescribeSecurityGroupsResult;
import com.amazonaws.services.ec2.model.Instance;
import com.amazonaws.services.ec2.model.InstanceStatus;
import com.amazonaws.services.ec2.model.IpPermission;
import com.amazonaws.services.ec2.model.Reservation;
import com.amazonaws.services.ec2.model.RunInstancesRequest;
import com.amazonaws.services.ec2.model.RunInstancesResult;
import com.amazonaws.services.ec2.model.SecurityGroup;
import com.amazonaws.services.ec2.model.Tag;
import com.amazonaws.services.ec2.model.TerminateInstancesRequest;
import com.amazonaws.services.elasticloadbalancing.model.ConfigureHealthCheckRequest;
import com.amazonaws.services.elasticloadbalancing.model.ConfigureHealthCheckResult;
import com.amazonaws.services.elasticloadbalancing.model.CreateLoadBalancerResult;
import com.amazonaws.services.elasticloadbalancing.model.DeleteLoadBalancerRequest;
import com.amazonaws.services.elasticloadbalancing.model.DescribeInstanceHealthRequest;
import com.amazonaws.services.elasticloadbalancing.model.DescribeInstanceHealthResult;
import com.amazonaws.services.elasticloadbalancing.model.DescribeLoadBalancersRequest;
import com.amazonaws.services.elasticloadbalancing.model.DescribeLoadBalancersResult;
import com.amazonaws.services.elasticloadbalancing.model.HealthCheck;
import com.amazonaws.services.elasticloadbalancing.model.InstanceState;
import com.amazonaws.services.elasticloadbalancing.model.Listener;
import com.amazonaws.services.elasticloadbalancing.AmazonElasticLoadBalancingClient;
import com.amazonaws.services.elasticloadbalancing.model.CreateLoadBalancerRequest;

public class autoScaling {
	public static Properties properties; // store credentials info
	public static String password = null; // store submission password
	
	public static AmazonEC2Client ec2; // ec2 client
	public static AmazonElasticLoadBalancingClient elb; // elb client
	public static AmazonAutoScalingClient as; //as client
	public static AmazonCloudWatchClient cw; //cloud watch client 
	public static String subnetID = null;
	public static String loadGenID = null;
	public static String securityGroupID = null;
	public static String loadGenDNS = null;
	public static String elbDNS = null;
	public static String elbName = "Project2ELB";
	public static String lauchConfigName = "Project2aslc";
	public static String asGroupName = "Project2asGroup";
	
	// store IDs of started instances
	public static HashSet<String> instanceIDSet = new HashSet<String>();

	public static void main(String[] args) throws IOException,
			InterruptedException {
		// Load the Properties File with AWS Credentials
		properties = new Properties();
		properties.load(autoScaling.class
				.getResourceAsStream("/AwsCredentials.properties"));
		BasicAWSCredentials bawsc = new BasicAWSCredentials(
				properties.getProperty("accessKey"),
				properties.getProperty("secretKey"));
		password = properties.getProperty("submissionPassword");
		
		// Create an Amazon EC2 Client
		ec2 = new AmazonEC2Client(bawsc);
		
		//create an security group
		securityGroupID = createSecurityGroup("all_in_out");
		
		//create an Amazon ELB Client
		elb = new AmazonElasticLoadBalancingClient(bawsc);
				
		//create an Amazon auto scaling client
		as = new AmazonAutoScalingClient(bawsc);
				
		//create an Amazon cloud watch client
		cw = new AmazonCloudWatchClient(bawsc);
				
		//create load generator
		createLoadGen();
		
		//submit password to load generator
		submitPassword();
		
		//create ELB
		String pingPath = "HTTP:80/heartbeat?lg=" + loadGenDNS;
		elbDNS = createELB(pingPath);
		
		//create auto scaling configuration
		autoScaleLaunchConfig();
		
		//create auto scaling group
		createAutoScalingGroup();
		
		//wait for ELB ready
		waitForELBready();
		
		//warm up ELB
		startTest(true);
		startTest(true);
		startTest(true);
		startTest(true);
		startTest(true);
		startTest(true);
		updateAutoScaling(3, 3, 3);
		startTest(true);
		startTest(true);
		startTest(true);
		//start junior test
		updateAutoScaling(3, 6, 6);
		startTest(false);
		
		//terminate all services
		termination();
	}
	
	/* create a load generator and wait for it to be ready */
	public static void createLoadGen() {
		RunInstancesRequest runLoadGenRequest = new RunInstancesRequest();
		// Configure load gen Instance Request
		runLoadGenRequest.withImageId("ami-312b5154")
				.withInstanceType("m3.medium")
				.withMinCount(1).withMaxCount(1).withKeyName("15619project2")
				.withSecurityGroups("alltraffic_anywhere");

		// Launch load gen Instance
		RunInstancesResult runLoadGenResult = ec2.runInstances(runLoadGenRequest);
		// Return the Object Reference of the load gen Instance just Launched
		Instance loadGen = runLoadGenResult.getReservation().getInstances().get(0);
		loadGenID = loadGen.getInstanceId();
		System.out.println("Instance Load Gen with ID: " + loadGenID + " started...");
		instanceIDSet.add(loadGenID);

		// create tags for load gen
		CreateTagsRequest createTagsRequest = new CreateTagsRequest();
		createTagsRequest.withResources(loadGenID).withTags(
				new Tag("Project", "2.2"));
		ec2.createTags(createTagsRequest);
		
		DescribeInstanceStatusRequest describeLGRequest = new DescribeInstanceStatusRequest()
				.withInstanceIds(loadGenID);
		String loadGenstate = null;
		System.out.println("wait for running state ready...");
		
		do { // if not running, repeat status checking
			try { // sleep for 5 sec before every checking
				TimeUnit.SECONDS.sleep(5);
			} catch (InterruptedException ie) {
				ie.printStackTrace();
			}
			// create request for load gen instance statuses
			DescribeInstanceStatusResult describeLGResult = ec2.describeInstanceStatus(describeLGRequest);
			List<InstanceStatus> loadGenstatuses = describeLGResult.getInstanceStatuses();
			// wait for load gen status to be generated
			while (loadGenstatuses.size() <= 0) {
				describeLGResult = ec2
						.describeInstanceStatus(describeLGRequest);
				loadGenstatuses = describeLGResult.getInstanceStatuses();
			}
			// check if load gen state is running
			loadGenstate = loadGenstatuses.get(0).getInstanceState().getName();
		} while (!loadGenstate.equals("running"));
		
		System.out.println("running state ready, 180 sec waiting period...");
		
		try { // sleep for 180 sec for all checks pass
			TimeUnit.SECONDS.sleep(180);
		} catch (InterruptedException ie) {
			ie.printStackTrace();
		}
		
		System.out.println("load generator ready");
	}
	
	/* submit password to load gen through http form */
	public static void submitPassword() throws IOException {
		Instance loadGen = null;
		String loadGenID = null;
		// Obtain a list of Reservations
		List<Reservation> reservations = ec2.describeInstances()
				.getReservations();
		// iterate instances in reservations
		for (int i = 0; i < reservations.size(); i++) {
			List<Instance> instances = reservations.get(i).getInstances();
			// Return the Object Reference of the Instance just Launched
			loadGen = instances.get(0);
			loadGenID = loadGen.getInstanceId();
			// break if find the load gen instance
			if (loadGen.getState().getName().equals("running")
					&& loadGen.getImageId().equals("ami-312b5154"))
				break;
		}

		subnetID = loadGen.getSubnetId();
		// get the load gen DNS and add its ID to a global hashset
		loadGenDNS = loadGen.getPublicDnsName();
		instanceIDSet.add(loadGenID);

		System.out.println("subnet ID: " + subnetID);
		System.out.println("Load Gen ID: " + loadGenID);
		System.out.println("Load Gen DNS: " + loadGenDNS);
		System.out.println("Load Gen Image ID: " + loadGen.getImageId());
		System.out.println("Load Gen Tags: " + loadGen.getTags());
		System.out.println("Submit password to load gen...");

		// generate http request to submit password to load gen
		String url = "http://" + loadGenDNS + "/password?passwd=" + password;
		System.out.println("submit url request: " + url);
		URL loadGenSubmitPassword = new URL(url);
		URLConnection LGconnection = loadGenSubmitPassword.openConnection();
		BufferedReader in = new BufferedReader(new InputStreamReader(
				LGconnection.getInputStream()));
		String inputLine;

		while ((inputLine = in.readLine()) != null)
			System.out.println(inputLine);
		in.close();

		System.out.println("Submit finished");
	}
	
	/* create a ELB with pingPath
	 * return the dns of this elb */
	public static String createELB(String pingPath) {
		CreateLoadBalancerRequest clbr = new CreateLoadBalancerRequest();
		
		//config listener
		List<Listener> listeners = new ArrayList<Listener>();
		listeners.add(new Listener("HTTP", 80, 80));
		
		//config load balancer request
		clbr.withLoadBalancerName(elbName)
			.withListeners(listeners)
			.withSecurityGroups(securityGroupID)
			.withSubnets(subnetID)
			.withTags(new com.amazonaws.services.elasticloadbalancing.model.Tag().withKey("Project").withValue("2.2"));
		
		CreateLoadBalancerResult elbResult = elb.createLoadBalancer(clbr);
		
		//config health check
		HealthCheck healthCheck = new HealthCheck()
			.withTarget(pingPath)
			.withUnhealthyThreshold(5) //originally 2
			.withHealthyThreshold(10)
			.withInterval(30)
			.withTimeout(5);
		
		ConfigureHealthCheckRequest chcr = new ConfigureHealthCheckRequest()
	    	.withHealthCheck(healthCheck)
	    	.withLoadBalancerName(elbName);
		
		ConfigureHealthCheckResult healthResult = elb.configureHealthCheck(chcr);
		
		System.out.println("ELB created, DNS name is: " + elbResult.getDNSName());
		
		return elbResult.getDNSName();
	}
	
	/* create auto scaling launch configuration with given name*/
	public static void autoScaleLaunchConfig() {
		System.out.println("auto scale launch configuring...");
		
		CreateLaunchConfigurationRequest clcr = new CreateLaunchConfigurationRequest();
		clcr.withLaunchConfigurationName(lauchConfigName)
			.withImageId("ami-3b2b515e")
			.withInstanceType("m3.medium")
			.withInstanceMonitoring(new com.amazonaws.services.autoscaling.model.InstanceMonitoring().withEnabled(true))
			.withSecurityGroups(securityGroupID)
			.withKeyName("15619project2");
		as.createLaunchConfiguration(clcr);
		
		System.out.println("auto scale launch config finished");
	}
	
	/* create auto scaling group, set metric policies */
	public static void createAutoScalingGroup() {
		System.out.println("creating auto scaling group...");
		
		//set max and min and desired size to be 1 to begin with
		CreateAutoScalingGroupRequest casg = new CreateAutoScalingGroupRequest();
		casg.withAutoScalingGroupName(asGroupName)
			.withLaunchConfigurationName(lauchConfigName)
			.withVPCZoneIdentifier(subnetID)
			.withDesiredCapacity(6)
			.withMaxSize(6)
			.withMinSize(6)
			.withLoadBalancerNames(elbName)
			.withHealthCheckType("ELB")
			.withHealthCheckGracePeriod(120)
			.withTags(new com.amazonaws.services.autoscaling.model.Tag().withKey("Project").withValue("2.2"));
		
		as.createAutoScalingGroup(casg);
		
		System.out.println("auto scaling group created, setting scaling policy");
		
		Dimension dimension = new Dimension()
		.withName("AutoScalingGroupName")
		.withValue(asGroupName);
		
		//set scale out policy
		PutScalingPolicyRequest scaleOut = new PutScalingPolicyRequest()
				.withAdjustmentType("ExactCapacity")
				.withScalingAdjustment(6) //adjust two every time
				.withPolicyName("scaleOut")
				.withAutoScalingGroupName(asGroupName)
				.withCooldown(120); //wait 120 sec inbetween scaling operations
		
		PutScalingPolicyResult scaleOutResult = as.putScalingPolicy(scaleOut);
		String scaleOutARN = scaleOutResult.getPolicyARN();
		System.out.println("scaleOutARN: " + scaleOutARN);
		
		//set up alarm request, scale out when average CPU util > 80%
		PutMetricAlarmRequest scaleOutAlarm = new PutMetricAlarmRequest()
				.withAlarmName("High-CPU-Alarm")
				.withMetricName("CPUUtilization")
				.withStatistic(Statistic.Average)
				.withUnit(StandardUnit.Percent)
				.withNamespace("AWS/EC2")
				.withPeriod(120) //check standard every 120 sec 
				.withEvaluationPeriods(1)
				.withThreshold(70d)
				.withComparisonOperator(ComparisonOperator.GreaterThanThreshold)
				.withDimensions(dimension)
				.withAlarmActions(scaleOutARN)
				.withActionsEnabled(true);	
		
		cw.putMetricAlarm(scaleOutAlarm);
		
		//set scale in policy
		PutScalingPolicyRequest scaleIn = new PutScalingPolicyRequest()
			.withAdjustmentType("ExactCapacity")
			.withScalingAdjustment(3)
			.withPolicyName("scaleIn")
			.withAutoScalingGroupName(asGroupName)
			.withCooldown(60);

		PutScalingPolicyResult scaleInResult = as.putScalingPolicy(scaleIn);
		String scaleInARN = scaleInResult.getPolicyARN();
		System.out.println("scaleInARN: " + scaleInARN);
		
		//set up alarm request, scale out when average CPU util < 50%
		PutMetricAlarmRequest scaleInAlarm = new PutMetricAlarmRequest()
			.withAlarmName("Low-CPU-Alarm")
			.withMetricName("CPUUtilization")
			.withStatistic(Statistic.Average)
			.withUnit(StandardUnit.Percent)
			.withNamespace("AWS/EC2")
			.withPeriod(120) //check standard every 120 sec 
			.withEvaluationPeriods(1)
			.withThreshold(50d)
			.withComparisonOperator(ComparisonOperator.LessThanThreshold)
			.withDimensions(dimension)
			.withAlarmActions(scaleInARN)
			.withActionsEnabled(true);	

		cw.putMetricAlarm(scaleInAlarm);
		
		System.out.println("auto scaling policy created");
	}
	
	public static void waitForELBready() {
		System.out.println("wait elb instances in service");
		
		DescribeLoadBalancersRequest elbDescribe = new DescribeLoadBalancersRequest()
			.withLoadBalancerNames(elbName);
	
		DescribeLoadBalancersResult elbResult = elb.describeLoadBalancers(elbDescribe);
		List<com.amazonaws.services.elasticloadbalancing.model.Instance> elbInstances = elbResult
				.getLoadBalancerDescriptions().get(0).getInstances();
	
		while(elbInstances.size() <= 0) { //need at least 1 instance in elb
			try { // sleep for 5 sec before every checking
				TimeUnit.SECONDS.sleep(5);
			} catch (InterruptedException ie) {
				ie.printStackTrace();
			}
			
			elbResult = elb.describeLoadBalancers(elbDescribe);
			elbInstances = elbResult
					.getLoadBalancerDescriptions().get(0).getInstances();
		}
		
		boolean elbReady = true;
		do {
			try { // sleep for 5 sec before every checking
				TimeUnit.SECONDS.sleep(5);
			} catch (InterruptedException ie) {
				ie.printStackTrace();
			}
			
			elbReady = true;
			
			DescribeInstanceHealthRequest healthRequest = new DescribeInstanceHealthRequest();
			healthRequest.withInstances(elbInstances).withLoadBalancerName(elbName);
			
			List<InstanceState> healthStates = elb.describeInstanceHealth(healthRequest)
					.getInstanceStates();
			
			//need this instance to be in service
			for(InstanceState healthState : healthStates) {
				if(healthState.getState().equals("InService")) {
					continue;
				}
				else {
					elbReady = false;
					break;
				}
			}
		} while(!elbReady);
	}
	
	public static void startTest(boolean isWarmUp) throws IOException {
		String url = null;
		if(isWarmUp) {
			System.out.println("warming up elb...");
			//submit elbDNS to warmUp test
			url = "http://" + loadGenDNS + "/warmup?dns=" + elbDNS;
		}
		else {
			System.out.println("start junior testing...");
			//submit elbDNS to junior test
			url = "http://" + loadGenDNS + "/junior?dns=" + elbDNS;
		}
		
		System.out.println("submit url request: " + url);
		
		int errorCNT = 0;
		String testID = null;
		while(errorCNT < 100) { //catch error for 100 times
			try {
				URL warmUpURL = new URL(url);
				URLConnection warmUpConnection = warmUpURL.openConnection();
				BufferedReader in = new BufferedReader(new InputStreamReader(
						warmUpConnection.getInputStream()));
				String inputLine;

				while ((inputLine = in.readLine()) != null) {
					System.out.println(inputLine);
					if (inputLine.contains("name=test.")) { //read testID
						String pre = "name=test.";
						String pos = ".log";
						int i1 = inputLine.indexOf(pre) + pre.length();
						int i2 = inputLine.indexOf(pos);
						testID = inputLine.substring(i1, i2);
					}
				}
				in.close();
				break;
			} catch (IOException ioe) {
				System.out.println("elb url is not ready yet x " + Integer.toString(errorCNT) + ", try again...");
				errorCNT++;
				try { // sleep for 5 sec after each error
					TimeUnit.SECONDS.sleep(10);
				} catch (InterruptedException ie) {
					ie.printStackTrace();
				}
			}
		}
		
		System.out.println("testID is: " + testID);
		
		boolean finished = false;
		
		do {
			try { // sleep for 60 sec before every checking
				TimeUnit.SECONDS.sleep(60);
			} catch (InterruptedException ie) {
				ie.printStackTrace();
			}
			
			System.out.println("read log...");
			url = "http://" + loadGenDNS + "/log?name=test." + testID + ".log";
			System.out.println("submit url request: " + url);
			
			URL readLog = new URL(url);
			URLConnection readLogConnection = readLog.openConnection();
			BufferedReader in = new BufferedReader(new InputStreamReader(
					readLogConnection.getInputStream()));
			String inputLine;
			while ((inputLine = in.readLine()) != null) {
				System.out.println(inputLine);
				if (inputLine.contains("Test finished") || inputLine.contains("Test End")) {
					finished = true;
				}
			}
		} while(!finished);
		
		System.out.println("test finished...");
	}
	
	/* reconfig auto scaling policy */
	public static void updateAutoScaling(int min, int des, int max) {
		System.out.println("update auto scaling");
		UpdateAutoScalingGroupRequest uasgr = new UpdateAutoScalingGroupRequest()
			.withMinSize(min)
			.withDesiredCapacity(des)
			.withMaxSize(max)
			.withAutoScalingGroupName(asGroupName);
		as.updateAutoScalingGroup(uasgr);
	}
	
	public static void termination() {
		System.out.println("terminating everything!");
		//terminate load gen
		//TerminateInstancesRequest terminateLG = new TerminateInstancesRequest()
			//.withInstanceIds(loadGenID);
		//ec2.terminateInstances(terminateLG);
		//terminate all auto scaling group instances
		updateAutoScaling(0,0,0);
		
		try { // sleep for 120 sec before deleting other services
			TimeUnit.SECONDS.sleep(120);
		} catch (InterruptedException ie) {
			ie.printStackTrace();
		}
		
		//delete elb
		DeleteLoadBalancerRequest deleteELB = new DeleteLoadBalancerRequest()
			.withLoadBalancerName(elbName);
		elb.deleteLoadBalancer(deleteELB);
		//delete auto scaling group
		DeleteAutoScalingGroupRequest deleteASG = new DeleteAutoScalingGroupRequest()
			.withAutoScalingGroupName(asGroupName);
		as.deleteAutoScalingGroup(deleteASG);
		//delete auto scaling launch configuration
		DeleteLaunchConfigurationRequest deleteLaunchConfig = new DeleteLaunchConfigurationRequest()
			.withLaunchConfigurationName(lauchConfigName);
		as.deleteLaunchConfiguration(deleteLaunchConfig);
		try { // sleep for 180 sec before deleting other services
			TimeUnit.SECONDS.sleep(180);
		} catch (InterruptedException ie) {
			ie.printStackTrace();
		}
		//delete security group
		DeleteSecurityGroupRequest deleteSG = new DeleteSecurityGroupRequest().withGroupId(securityGroupID);
		int errorCNT = 0;
		while(errorCNT < 100)
		try {
			ec2.deleteSecurityGroup(deleteSG);
			break;
		} catch (AmazonServiceException ase) {
			try { // sleep for 5 sec before deleting other services
				TimeUnit.SECONDS.sleep(5);
			} catch (InterruptedException ie) {
				ie.printStackTrace();
			}
			errorCNT++;
			ase.printStackTrace();
		}
	}
	
	/* create a security group with all in/out traffic enabled
	 * take given group name, return the created group ID */
	public static String createSecurityGroup(String groupName) {
		CreateSecurityGroupRequest csgr = new CreateSecurityGroupRequest();
		csgr.withGroupName(groupName).withDescription("allow all traffics");
		String sgID = null;
		try {
			CreateSecurityGroupResult csgResult = ec2.createSecurityGroup(csgr);
			sgID = csgResult.getGroupId();
		} catch(AmazonServiceException ase) {
			System.out.println("security group exists");
			DescribeSecurityGroupsRequest dsgr = new DescribeSecurityGroupsRequest()
				.withGroupNames(groupName);
			SecurityGroup sg = ec2.describeSecurityGroups(dsgr)
				.getSecurityGroups().get(0);
			return sg.getGroupId();
		}
		
		IpPermission ipPermission = new IpPermission();
		
		//set IP permission to be all ports all protocols
		ipPermission.withIpRanges("0.0.0.0/0")
					.withIpProtocol("-1").withFromPort(-1).withToPort(-1);
		
		AuthorizeSecurityGroupIngressRequest asgiq = new AuthorizeSecurityGroupIngressRequest();

		asgiq.withGroupName(groupName)
			.withIpPermissions(ipPermission);
		
		//send request to create security group
		ec2.authorizeSecurityGroupIngress(asgiq);
		
		return sgID;
	}
}
