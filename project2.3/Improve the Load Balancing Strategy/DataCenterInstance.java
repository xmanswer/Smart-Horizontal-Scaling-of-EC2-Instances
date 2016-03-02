import java.io.IOException;
import java.net.URL;
import java.net.URLConnection;

public class DataCenterInstance {
	private final String name;
	private final String url;
	private Double cpuUtil; //cpu utilization for this DCI
	
	public DataCenterInstance(String name, String url) {
		this.name = name;
		this.url = url;
	}
	
	public String getName() {
		return name;
	}

	public String getUrl() {
		return url;
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
