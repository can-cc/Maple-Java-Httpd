package httpd;

import java.io.File;
import java.io.IOException;

public class Main {
	public static void main(String[] args) {
		
        int lopt = -1;
        
        int port = 8080;
        if (args.length > 0 && lopt != 0)
            port = Integer.parseInt(args[0]);

        try {
            new MapleHttpd(port);
        } catch (IOException ioe) {
            System.err.println("Couldn't start server:\n" + ioe);
            System.exit(-1);
        }

        System.out.println("Now serving files in port " + port + " from \"" +
                new File("").getAbsolutePath() + "\"");
        while (true) {
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
	}
}
