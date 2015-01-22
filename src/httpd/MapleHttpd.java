package httpd;

import java.io.File;
import java.io.IOException;
import java.net.ServerSocket;

public class MapleHttpd {
	private int myTcpPort;
    private final ServerSocket myServerSocket;
    private Thread myThread;
	
	public MapleHttpd(int port) throws IOException {
        myTcpPort = port;
        myServerSocket = new ServerSocket(myTcpPort);
        myThread = new Thread(new Runnable() {
            public void run() {
                try {
                    while (true)
                        new HTTPSession(myServerSocket.accept());
                } catch (IOException ioe) {
                }
            }
        });
        myThread.setDaemon(true);
        myThread.start();
    }
	
	public void stop() {
        try {
            myServerSocket.close();
            myThread.join();
        } catch (IOException ioe) {
        } catch (InterruptedException e) {
        }
    }
}
