package httpd;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.Socket;
import java.util.Date;
import java.util.Enumeration;
import java.util.Locale;
import java.util.Properties;
import java.util.StringTokenizer;
import java.util.TimeZone;
import java.util.Vector;

class HTTPSession implements Runnable {
	private static java.text.SimpleDateFormat gmtFrmt;
	
    private Socket mySocket;
    private Serve serve;
    static {
        gmtFrmt = new java.text.SimpleDateFormat("E, d MMM yyyy HH:mm:ss 'GMT'", Locale.US);
        gmtFrmt.setTimeZone(TimeZone.getTimeZone("GMT"));
    }
	
	
	
    public HTTPSession(Socket s) {
    	serve = new Serve();
        mySocket = s;
        Thread t = new Thread(this);
        t.setDaemon(true);
        t.start();
    }

    public void run() {
        try {
            InputStream is = mySocket.getInputStream();
            if (is == null) return;

            // Read the first 8192 bytes.
            // The full header should fit in here.
            // Apache's default header limit is 8KB.
            int bufsize = 8192;
            byte[] buf = new byte[bufsize];
            int rlen = is.read(buf, 0, bufsize);
            if (rlen <= 0) return;

            // Create a BufferedReader for parsing the header.
            ByteArrayInputStream hbis = new ByteArrayInputStream(buf, 0, rlen);
            BufferedReader hin = new BufferedReader(new InputStreamReader(hbis));
            Properties pre = new Properties();
            Properties parms = new Properties();
            Properties header = new Properties();
            Properties files = new Properties();

            // Decode the header into parms and header java properties
            decodeHeader(hin, pre, parms, header);
            String method = pre.getProperty("method");
            String uri = pre.getProperty("uri");

            long size = 0x7FFFFFFFFFFFFFFFl;
            String contentLength = header.getProperty("content-length");
            if (contentLength != null) {
                try {
                    size = Integer.parseInt(contentLength);
                } catch (NumberFormatException ex) {
                }
            }

            // We are looking for the byte separating header from body.
            // It must be the last byte of the first two sequential new lines.
            int splitbyte = 0;
            boolean sbfound = false;
            while (splitbyte < rlen) {
                if (buf[splitbyte] == '\r' && buf[++splitbyte] == '\n' && buf[++splitbyte] == '\r' && buf[++splitbyte] == '\n') {
                    sbfound = true;
                    break;
                }
                splitbyte++;
            }
            splitbyte++;

            // Write the part of body already read to ByteArrayOutputStream f
            ByteArrayOutputStream f = new ByteArrayOutputStream();
            if (splitbyte < rlen) f.write(buf, splitbyte, rlen - splitbyte);

            // While Firefox sends on the first read all the data fitting
            // our buffer, Chrome and Opera sends only the headers even if
            // there is data for the body. So we do some magic here to find
            // out whether we have already consumed part of body, if we
            // have reached the end of the data to be sent or we should
            // expect the first byte of the body at the next read.
            if (splitbyte < rlen)
                size -= rlen - splitbyte + 1;
            else if (!sbfound || size == 0x7FFFFFFFFFFFFFFFl)
                size = 0;

            // Now read all the body and write it to f
            buf = new byte[512];
            while (rlen >= 0 && size > 0) {
                rlen = is.read(buf, 0, 512);
                size -= rlen;
                if (rlen > 0)
                    f.write(buf, 0, rlen);
            }

            // Get the raw body as a byte []
            byte[] fbuf = f.toByteArray();

            // Create a BufferedReader for easily reading it as string.
            ByteArrayInputStream bin = new ByteArrayInputStream(fbuf);
            BufferedReader in = new BufferedReader(new InputStreamReader(bin));

            // If the method is POST, there may be parameters
            // in data section, too, read it:
            if (method.equalsIgnoreCase("POST")) {
                String contentType = "";
                String contentTypeHeader = header.getProperty("content-type");
                StringTokenizer st = new StringTokenizer(contentTypeHeader, "; ");
                if (st.hasMoreTokens()) {
                    contentType = st.nextToken();
                }

                if (contentType.equalsIgnoreCase("multipart/form-data")) {
                    // Handle multipart/form-data
                    if (!st.hasMoreTokens())
                        sendError(HttpStatus.BADREQUEST, "BAD REQUEST: Content type is multipart/form-data but boundary missing. Usage: GET /example/file.html");
                    String boundaryExp = st.nextToken();
                    st = new StringTokenizer(boundaryExp, "=");
                    if (st.countTokens() != 2)
                        sendError(HttpStatus.BADREQUEST, "BAD REQUEST: Content type is multipart/form-data but boundary syntax error. Usage: GET /example/file.html");
                    st.nextToken();
                    String boundary = st.nextToken();

                    decodeMultipartData(boundary, fbuf, in, parms, files);
                } else {
                    // Handle application/x-www-form-urlencoded
                    String postLine = "";
                    char pbuf[] = new char[512];
                    int read = in.read(pbuf);
                    while (read >= 0 && !postLine.endsWith("\r\n")) {
                        postLine += String.valueOf(pbuf, 0, read);
                        read = in.read(pbuf);
                    }
                    postLine = postLine.trim();
                    decodeParms(postLine, parms);
                }
            }

            // Ok, now do the serve()
            Response r = serve.serve(uri, method, header, parms, files);
            if (r == null)
                sendError(HttpStatus.INTERNALERROR, "SERVER INTERNAL ERROR: Serve() returned a null response.");
            else
                sendResponse(r.status, r.mimeType, r.header, r.data);

            in.close();
            is.close();
            if (method.equalsIgnoreCase("GET")) {
                if (parms.getProperty("exit") != null) {
                    System.exit(0);
                }
            }
        } catch (IOException ioe) {
            try {
                sendError(HttpStatus.INTERNALERROR, "SERVER INTERNAL ERROR: IOException: " + ioe.getMessage());
            } catch (Throwable t) {
            }
        } catch (InterruptedException ie) {
            // Thrown by sendError, ignore and exit the thread.
        }
    }

    /**
     * Decodes the sent headers and loads the data into
     * java Properties' key - value pairs
     */
    private void decodeHeader(BufferedReader in, Properties pre, Properties parms, Properties header)
            throws InterruptedException {
        try {
            // Read the request line
            String inLine = in.readLine();
            if (inLine == null) return;
            StringTokenizer st = new StringTokenizer(inLine);
            if (!st.hasMoreTokens())
                sendError(HttpStatus.BADREQUEST, "BAD REQUEST: Syntax error. Usage: GET /example/file.html");

            String method = st.nextToken();
            pre.put("method", method);

            if (!st.hasMoreTokens())
                sendError(HttpStatus.BADREQUEST, "BAD REQUEST: Missing URI. Usage: GET /example/file.html");

            String uri = st.nextToken();

            // Decode parameters from the URI
            int qmi = uri.indexOf('?');
            if (qmi >= 0) {
                decodeParms(uri.substring(qmi + 1), parms);
                uri = decodePercent(uri.substring(0, qmi));
            } else uri = decodePercent(uri);

            // If there's another token, it's protocol version,
            // followed by HTTP headers. Ignore version but parse headers.
            // NOTE: this now forces header names lowercase since they are
            // case insensitive and vary by client.
            if (st.hasMoreTokens()) {
                String line = in.readLine();
                while (line != null && line.trim().length() > 0) {
                    int p = line.indexOf(':');
                    if (p >= 0)
                        header.put(line.substring(0, p).trim().toLowerCase(), line.substring(p + 1).trim());
                    line = in.readLine();
                }
            }

            pre.put("uri", uri);
        } catch (IOException ioe) {
            sendError(HttpStatus.INTERNALERROR, "SERVER INTERNAL ERROR: IOException: " + ioe.getMessage());
        }
    }

    /**
     * Decodes the Multipart Body data and put it
     * into java Properties' key - value pairs.
     */
    private void decodeMultipartData(String boundary, byte[] fbuf, BufferedReader in, Properties parms, Properties files)
            throws InterruptedException {
        try {
            int[] bpositions = getBoundaryPositions(fbuf, boundary.getBytes());
            int boundarycount = 1;
            String mpline = in.readLine();
            while (mpline != null) {
                if (mpline.indexOf(boundary) == -1)
                    sendError(HttpStatus.BADREQUEST, "BAD REQUEST: Content type is multipart/form-data but next chunk does not start with boundary. Usage: GET /example/file.html");
                boundarycount++;
                Properties item = new Properties();
                mpline = in.readLine();
                while (mpline != null && mpline.trim().length() > 0) {
                    int p = mpline.indexOf(':');
                    if (p != -1)
                        item.put(mpline.substring(0, p).trim().toLowerCase(), mpline.substring(p + 1).trim());
                    mpline = in.readLine();
                }
                if (mpline != null) {
                    String contentDisposition = item.getProperty("content-disposition");
                    if (contentDisposition == null) {
                        sendError(HttpStatus.BADREQUEST, "BAD REQUEST: Content type is multipart/form-data but no content-disposition info found. Usage: GET /example/file.html");
                    }
                    StringTokenizer st = new StringTokenizer(contentDisposition, "; ");
                    Properties disposition = new Properties();
                    while (st.hasMoreTokens()) {
                        String token = st.nextToken();
                        int p = token.indexOf('=');
                        if (p != -1)
                            disposition.put(token.substring(0, p).trim().toLowerCase(), token.substring(p + 1).trim());
                    }
                    String pname = disposition.getProperty("name");
                    pname = pname.substring(1, pname.length() - 1);

                    String value = "";
                    if (item.getProperty("content-type") == null) {
                        while (mpline != null && mpline.indexOf(boundary) == -1) {
                            mpline = in.readLine();
                            if (mpline != null) {
                                int d = mpline.indexOf(boundary);
                                if (d == -1)
                                    value += mpline;
                                else
                                    value += mpline.substring(0, d - 2);
                            }
                        }
                    } else {
                        if (boundarycount > bpositions.length)
                            sendError(HttpStatus.INTERNALERROR, "Error processing request");
                        int offset = stripMultipartHeaders(fbuf, bpositions[boundarycount - 2]);
                        String path = saveTmpFile(fbuf, offset, bpositions[boundarycount - 1] - offset - 4);
                        files.put(pname, path);
                        value = disposition.getProperty("filename");
                        value = value.substring(1, value.length() - 1);
                        do {
                            mpline = in.readLine();
                        } while (mpline != null && mpline.indexOf(boundary) == -1);
                    }
                    parms.put(pname, value);
                }
            }
        } catch (IOException ioe) {
            sendError(HttpStatus.INTERNALERROR, "SERVER INTERNAL ERROR: IOException: " + ioe.getMessage());
        }
    }

    /**
     * Find the byte positions where multipart boundaries start.
     */
    public int[] getBoundaryPositions(byte[] b, byte[] boundary) {
        int matchcount = 0;
        int matchbyte = -1;
        Vector matchbytes = new Vector();
        for (int i = 0; i < b.length; i++) {
            if (b[i] == boundary[matchcount]) {
                if (matchcount == 0)
                    matchbyte = i;
                matchcount++;
                if (matchcount == boundary.length) {
                    matchbytes.addElement(new Integer(matchbyte));
                    matchcount = 0;
                    matchbyte = -1;
                }
            } else {
                i -= matchcount;
                matchcount = 0;
                matchbyte = -1;
            }
        }
        int[] ret = new int[matchbytes.size()];
        for (int i = 0; i < ret.length; i++) {
            ret[i] = ((Integer) matchbytes.elementAt(i)).intValue();
        }
        return ret;
    }

    /**
     * Retrieves the content of a sent file and saves it
     * to a temporary file.
     * The full path to the saved file is returned.
     */
    private String saveTmpFile(byte[] b, int offset, int len) {
        String path = "";
        if (len > 0) {
            String tmpdir = System.getProperty("java.io.tmpdir");
            try {
                File temp = File.createTempFile("NanoHTTPd", "", new File(tmpdir));
                OutputStream fstream = new FileOutputStream(temp);
                fstream.write(b, offset, len);
                fstream.close();
                path = temp.getAbsolutePath();
            } catch (Exception e) { // Catch exception if any
                System.err.println("Error: " + e.getMessage());
            }
        }
        return path;
    }


    /**
     * It returns the offset separating multipart file headers
     * from the file's data.
     */
    private int stripMultipartHeaders(byte[] b, int offset) {
        int i = 0;
        for (i = offset; i < b.length; i++) {
            if (b[i] == '\r' && b[++i] == '\n' && b[++i] == '\r' && b[++i] == '\n')
                break;
        }
        return i + 1;
    }

    /**
     * Decodes the percent encoding scheme. <br/>
     * For example: "an+example%20string" -> "an example string"
     */
    private String decodePercent(String str) throws InterruptedException {
        try {
            StringBuffer sb = new StringBuffer();
            for (int i = 0; i < str.length(); i++) {
                char c = str.charAt(i);
                switch (c) {
                    case '+':
                        sb.append(' ');
                        break;
                    case '%':
                        sb.append((char) Integer.parseInt(str.substring(i + 1, i + 3), 16));
                        i += 2;
                        break;
                    default:
                        sb.append(c);
                        break;
                }
            }
            return sb.toString();
        } catch (Exception e) {
            sendError(HttpStatus.BADREQUEST, "BAD REQUEST: Bad percent-encoding.");
            return null;
        }
    }

    /**
     * Decodes parameters in percent-encoded URI-format
     * ( e.g. "name=Jack%20Daniels&pass=Single%20Malt" ) and
     * adds them to given Properties. NOTE: this doesn't support multiple
     * identical keys due to the simplicity of Properties -- if you need multiples,
     * you might want to replace the Properties with a Hashtable of Vectors or such.
     */
    private void decodeParms(String parms, Properties p)
            throws InterruptedException {
        if (parms == null)
            return;

        StringTokenizer st = new StringTokenizer(parms, "&");
        while (st.hasMoreTokens()) {
            String e = st.nextToken();
            int sep = e.indexOf('=');
            p.put(
                    decodePercent((sep >= 0) ? e.substring(0, sep) : e).trim(),
                    (sep >= 0) ? decodePercent(e.substring(sep + 1)) : ""
            );
        }
    }

    /**
     * Returns an error message as a HTTP response and
     * throws InterruptedException to stop further request processing.
     */
    private void sendError(String status, String msg) throws InterruptedException {
        sendResponse(status, MIMEType.PLAINTEXT, null, new ByteArrayInputStream(msg.getBytes()));
        throw new InterruptedException();
    }

    /**
     * Sends given response to the socket.
     */
    private void sendResponse(String status, String mime, Properties header, InputStream data) {
        try {
            if (status == null)
                throw new Error("sendResponse(): Status can't be null.");

            OutputStream out = mySocket.getOutputStream();
            PrintWriter pw = new PrintWriter(out);
            pw.print("HTTP/1.0 " + status + " \r\n");

            if (mime != null)
                pw.print("Content-Type: " + mime + "\r\n");

            if (header == null || header.getProperty("Date") == null)
                pw.print("Date: " + gmtFrmt.format(new Date()) + "\r\n");

            if (header != null) {
                Enumeration e = header.keys();
                while (e.hasMoreElements()) {
                    String key = (String) e.nextElement();
                    String value = header.getProperty(key);
                    pw.print(key + ": " + value + "\r\n");
                }
            }

            pw.print("\r\n");
            pw.flush();

            if (data != null) {
                byte[] buff = new byte[2048];
                while (true) {
                    int read = data.read(buff, 0, 2048);
                    if (read <= 0)
                        break;
                    out.write(buff, 0, read);
                }
            }
            out.flush();
            out.close();
            if (data != null)
                data.close();
        } catch (IOException ioe) {
            // Couldn't write? No can do.
            try {
                mySocket.close();
            } catch (Throwable t) {
            }
        }
    }
}