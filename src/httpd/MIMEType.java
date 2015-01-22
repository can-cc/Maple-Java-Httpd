package httpd;

import java.util.Hashtable;
import java.util.StringTokenizer;

public final class MIMEType {
	public static final String
	PLAINTEXT = "text/plain",
    HTML = "text/html",
    DEFAULT_BINARY = "application/octet-stream",
    XML = "text/xml";
	
	static Hashtable<String, String> theMimeFileTypes = new Hashtable<String, String>();
    static {
        StringTokenizer st = new StringTokenizer(
                		"css		text/css " +
                        "js			text/javascript " +
                        "htm		text/html " +
                        "html		text/html " +
                        "txt		text/plain " +
                        "asc		text/plain " +
                        "gif		image/gif " +
                        "jpg		image/jpeg " +
                        "jpeg		image/jpeg " +
                        "png		image/png " +
                        "mp3		audio/mpeg " +
                        "m3u		audio/mpeg-url " +
                        "pdf		application/pdf " +
                        "doc		application/msword " +
                        "ogg		application/x-ogg " +
                        "zip		application/octet-stream " +
                        "exe		application/octet-stream " +
                        "class		application/octet-stream ");
        while (st.hasMoreTokens())
        	theMimeFileTypes.put(st.nextToken(), st.nextToken());
    }

}
