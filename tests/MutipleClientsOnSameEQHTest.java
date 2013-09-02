//package clientTests;

import com.mellanox.*;
import java.util.Random;


public class MutipleClientsOnSameEQHTest implements Runnable{

	public void run(){
		///////////////////// Test 3 /////////////////////
		// Multiple session client on the same EQH
		TestClient.print("*** Test 3: Multiple session client on the same EQH *** ");
		
		// Setup Multiple Clients Parameters
		String url;
		JXIOEventQueueHandler eqh;
		MySesClient[] sClientArray;
		int numOfSessionClients = 3;
		
		// Setting up a Event Queue Hanler
		eqh = new JXIOEventQueueHandler(TestClient.eqhSize);
		
		// Setting up a multiple session clients
		TestClient.print("----- Setting up a multiple session clients...");
		Random portGenerator = new Random();
		sClientArray = new MySesClient[numOfSessionClients];
		for (int i = 0; i < numOfSessionClients; i++){
			// Randomize Port
			int port = portGenerator.nextInt(TestClient.portRange) + 1;
			
			// Get url
			url = "rdma://" + TestClient.hostname + ":" + port;		
			
			// Setup Client
			sClientArray[i] = new MySesClient(eqh, url);
		}
		
		// Closing the session clients
		TestClient.print("------ Closing the session client...");
		for (int i = 0; i < numOfSessionClients; i++){
			sClientArray[i].close();
		}
		
		TestClient.setSuccess(3);
		TestClient.print("*** Test 3 Passed! *** ");
	}
}