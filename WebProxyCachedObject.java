import java.io.ByteArrayOutputStream;

public class WebProxyCachedObject {	
	public String filename = null;
	public String date = null;
	public ByteArrayOutputStream textCache = null;
	public boolean isText = false;
	
	WebProxyCachedObject(String date, String filename, boolean isText, ByteArrayOutputStream textCache) {
		this.date = date;
		this.filename = filename;
		this.isText = isText;
		if(isText) this.textCache = textCache;
	}
}
