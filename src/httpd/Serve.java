package httpd;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.URLEncoder;
import java.util.Enumeration;
import java.util.Hashtable;
import java.util.Locale;
import java.util.Properties;
import java.util.StringTokenizer;
import java.util.TimeZone;

public class Serve {
	
	private String encodeUri(String uri) {
        String newUri = "";
        StringTokenizer st = new StringTokenizer(uri, "/ ", true);
        while (st.hasMoreTokens()) {
            String tok = st.nextToken();
            if (tok.equals("/"))
                newUri += "/";
            else if (tok.equals(" "))
                newUri += "%20";
            else {
                newUri += URLEncoder.encode(tok);
            }
        }
        return newUri;
    }

	public Response serve(String uri, String method, Properties header,
			Properties parms, Properties files) {
		System.out.println(method + " '" + uri + "' ");

		Enumeration e = header.propertyNames();
		while (e.hasMoreElements()) {
			String value = (String) e.nextElement();
			System.out.println("  HDR: '" + value + "' = '"
					+ header.getProperty(value) + "'");
		}
		e = parms.propertyNames();
		while (e.hasMoreElements()) {
			String value = (String) e.nextElement();
			System.out.println("  PRM: '" + value + "' = '"
					+ parms.getProperty(value) + "'");
		}
		e = files.propertyNames();
		while (e.hasMoreElements()) {
			String value = (String) e.nextElement();
			System.out.println("  UPLOADED: '" + value + "' = '"
					+ files.getProperty(value) + "'");
		}

		return serveFile(uri, header, new File("."), true);
	}

	public Response serveFile(String uri, Properties header, File homeDir,
			boolean allowDirectoryListing) {
		// Make sure we won't die of an exception later
		if (!homeDir.isDirectory())
			return new Response(HttpStatus.INTERNALERROR, MIMEType.PLAINTEXT,
					"INTERNAL ERRROR: serveFile(): given homeDir is not a directory.");

		// Remove URL arguments
		uri = uri.trim().replace(File.separatorChar, '/');
		if (uri.indexOf('?') >= 0)
			uri = uri.substring(0, uri.indexOf('?'));

		// Prohibit getting out of current directory
		if (uri.startsWith("..") || uri.endsWith("..")
				|| uri.indexOf("../") >= 0)
			return new Response(HttpStatus.FORBIDDEN, MIMEType.PLAINTEXT,
					"FORBIDDEN: Won't serve ../ for security reasons.");

		File f = new File(homeDir, uri);
		if (!f.exists())
			return new Response(HttpStatus.NOTFOUND, MIMEType.PLAINTEXT,
					"Error 404, file not found.");

		// List the directory, if necessary
		if (f.isDirectory()) {
			// Browsers get confused without '/' after the
			// directory, send a redirect.
			if (!uri.endsWith("/")) {
				uri += "/";
				Response r = new Response(HttpStatus.REDIRECT, MIMEType.HTML,
						"<html><body>Redirected: <a href=\"" + uri + "\">"
								+ uri + "</a></body></html>");
				r.addHeader("Location", uri);
				return r;
			}

			// First try index.html and index.htm
			if (new File(f, "index.html").exists())
				f = new File(homeDir, uri + "/index.html");
			else if (new File(f, "index.htm").exists())
				f = new File(homeDir, uri + "/index.htm");

			// No index file, list the directory
			else if (allowDirectoryListing) {
				String[] files = f.list();
				String msg = "<html><body><h1>Directory " + uri + "</h1><br/>";

				if (uri.length() > 1) {
					String u = uri.substring(0, uri.length() - 1);
					int slash = u.lastIndexOf('/');
					if (slash >= 0 && slash < u.length())
						msg += "<b><a href=\"" + uri.substring(0, slash + 1)
								+ "\">..</a></b><br/>";
				}

				for (int i = 0; i < files.length; ++i) {
					File curFile = new File(f, files[i]);
					boolean dir = curFile.isDirectory();
					if (dir) {
						msg += "<b>";
						files[i] += "/";
					}

					msg += "<a href=\"" + encodeUri(uri + files[i]) + "\">"
							+ files[i] + "</a>";

					// Show file size
					if (curFile.isFile()) {
						long len = curFile.length();
						msg += " &nbsp;<font size=2>(";
						if (len < 1024)
							msg += len + " bytes";
						else if (len < 1024 * 1024)
							msg += len / 1024 + "." + (len % 1024 / 10 % 100)
									+ " KB";
						else
							msg += len / (1024 * 1024) + "." + len
									% (1024 * 1024) / 10 % 100 + " MB";

						msg += ")</font>";
					}
					msg += "<br/>";
					if (dir)
						msg += "</b>";
				}
				msg += "</body></html>";
				return new Response(HttpStatus.OK, MIMEType.HTML, msg);
			} else {
				return new Response(HttpStatus.FORBIDDEN, MIMEType.PLAINTEXT,
						"FORBIDDEN: No directory listing.");
			}
		}

		try {
			// Get MIME type from file name extension, if possible
			String mime = null;
			int dot = f.getCanonicalPath().lastIndexOf('.');
			if (dot >= 0)
				mime = (String) MIMEType.theMimeFileTypes.get(f.getCanonicalPath()
						.substring(dot + 1).toLowerCase());
			if (mime == null)
				mime = MIMEType.DEFAULT_BINARY;

			// Support (simple) skipping:
			long startFrom = 0;
			String range = header.getProperty("range");
			if (range != null) {
				if (range.startsWith("bytes=")) {
					range = range.substring("bytes=".length());
					int minus = range.indexOf('-');
					if (minus > 0)
						range = range.substring(0, minus);
					try {
						startFrom = Long.parseLong(range);
					} catch (NumberFormatException nfe) {
					}
				}
			}

			FileInputStream fis = new FileInputStream(f);
			fis.skip(startFrom);
			Response r = new Response(HttpStatus.OK, mime, fis);
			r.addHeader("Content-length", "" + (f.length() - startFrom));
			r.addHeader("Content-range", "" + startFrom + "-"
					+ (f.length() - 1) + "/" + f.length());
			return r;
		} catch (IOException ioe) {
			return new Response(HttpStatus.FORBIDDEN, MIMEType.PLAINTEXT,
					"FORBIDDEN: Reading file failed.");
		}
	}
}
