import java.io.IOException;
import java.net.URL;
import java.net.URLConnection;

public class DataCenterInstance {
	private final String name; //name is Instance ID
	private final String url;
	private final String dns;
	private Double cpuUtil; //cpu utilization for this DCI
	
	public DataCenterInstance(String name, String url) {
		this.name = name;
		this.url = url;
		this.dns = url.substring(7); //get rid of http:// in url
	}
	
	public String getName() {
		return name;
	}

	public String getUrl() {
		return url;
	}
	
	public String getDns() {
		return dns;
	}
	
	//set the cpu utilization
	public void setcpuUtil(Double cpuUtil) {
		this.cpuUtil = cpuUtil;
	}
	
	//return the cpu utilization
	public Double getcpuUtil() {
		return cpuUtil;
	}
	/**
	 * Execute the request on the Data Center Instance
	 * @param path
	 * @return URLConnection
	 * @throws IOException
	 */
	public URLConnection executeRequest(String path) throws IOException {
		URLConnection conn = openConnection(path);
		return conn;
	}

	/**
	 * Open a connection with the Data Center Instance
	 * @param path
	 * @return URLConnection
	 * @throws IOException
	 */
	private URLConnection openConnection(String path) throws IOException {
		URL url = new URL(path);
		URLConnection conn = url.openConnection();
		conn.setDoInput(true);
		conn.setDoOutput(false);
		return conn;
	}
}
