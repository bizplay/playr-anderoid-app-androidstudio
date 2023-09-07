package biz.playr;

import static android.os.SystemClock.sleep;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Timer;
import java.util.TimerTask;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
//import android.content.pm.PackageInfo;
import android.content.SharedPreferences;
import android.os.Binder;
import android.os.IBinder;
//import android.os.PowerManager;
import android.util.Log;

// If this service should run in a separate process the communication used here will not do
// Instead use a Broadcast and BroadcastReceiver to communicate between Service and MainActivity
public class CheckRestartService extends Service {
	private static final String className = "biz.playr.CheckRestartS";
	// use type long since the second and third parameter for
	// scheduleAtFixedRate is of type long
	private static final long initialDelay = 30000; // 30 seconds in milliseconds
	// private static final long intervalBetweenRestartChecks = 300000; // 5 minutes in milliseconds
	private static final long intervalBetweenRestartChecks = 180000; // 3 minutes in milliseconds
	private static final String rebootResponse = "1";
	private boolean stopTask = false;
	private Timer timer = null;
	//
	// Alternative for using Timer. Could even replace this service by running this from the MainActivity ???
	// see; https://developer.android.com/reference/android/os/Handler.html
	//      https://developer.android.com/reference/java/lang/Runnable.html
	//      https://developer.android.com/reference/android/os/Looper.html
	// make sure the threading model works in this situation
	// private Handler handler = new Handler();

	// private Runnable myRunnable = new Runnable() {
	// 	public void run() {
	// 		// do stuff

	// 		// run again
	// 		handler.postDelayed(myRunnable, intervalBetweenRestartChecks);
	// 	}
	// };

	//
	// trigger the runnable
	// handler.postDelayed(myRunnable, intervalBetweenRestartChecks);
	//
	// END alternative for timer
	//


	// see https://stackoverflow.com/a/23587641/813660 answer for https://stackoverflow.com/questions/23586031/calling-activity-class-method-from-service-class
	// on how to enable calling a method on an Activity from a Service
	// having a direct reference is considered very bad practise
	// Binder given to clients
	private final IBinder binder = new LocalBinder();
	// Registered callbacks
	private IServiceCallbacks serviceCallbacks;

	// Class used for the client Binder.
	class LocalBinder extends Binder {
		CheckRestartService getService() {
			Log.i(className, "getService");
			// Return this instance of CheckRestartService so clients can call public methods
			return CheckRestartService.this;
		}
	}

	@Override
	public IBinder onBind(Intent intent) {
		Log.i(className, "override onBind");
		return binder;
	}

	public void setCallbacks(IServiceCallbacks callbacks) {
		Log.i(className, "setCallbacks");
		this.serviceCallbacks = callbacks;
	}
	// end of code to link to Activity

	public CheckRestartService() {
		Log.i(className, "default constructor");
	}

	@Override
	public void onCreate() {
		Log.i(className, "override onCreate");
		super.onCreate();

		stopTask = false;

		// Start polling check for restart task
		TimerTask task = new TimerTask() {
			private static final String className = "biz.playr.TimerTask";

			@Override
			public void run() {
				// Log.i(className, "override run");
				// If you wish to stop the task/polling
				if (stopTask) {
					this.cancel();
				}

				if (serviceCallbacks != null) {
					// trigger saving of web content in MainActivity
					Log.i(className, "############################################################");
					Log.i(className, "###                                                      ###");
					Log.i(className, "### .run: trigger MainActivity to save screenshot        ###");
					Log.i(className, "###                                                      ###");
					Log.i(className, "###########################################################");
					serviceCallbacks.saveScreenshot();

					// check the server if restart is needed
					boolean restartMainActivity = checkServerForRestart();
					if (restartMainActivity) {
						Log.i(className, ".run: restarting MainActivity");
						serviceCallbacks.restartActivityWithDelay();
					} else {
						Log.i(className, ".run: MainActivity does not need to be restarted");
					}
				} else {
					Log.e(className, ".run: calling methods on MainActivity impossible; serviceCallbacks is null");
				}
			}
		};
		timer = new Timer();
		timer.scheduleAtFixedRate(task, initialDelay, intervalBetweenRestartChecks);
		Log.i(className, ".onCreate: timer was started with delay: " + initialDelay/1000 + " (s) and interval: " + intervalBetweenRestartChecks/1000 + " (s)");

		// To end the application
		// Log.e(className,"onCreate: System.exit(2) !!! End application !!!");
		// System.exit(2);
	}

	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {
		Log.i(className, "override onStartCommand");
		return super.onStartCommand(intent, flags, startId);
	}

	@Override
	public void onDestroy() {
		Log.i(className, "override onDestroy");
		stopCheck();
		if (timer != null) {
			timer.cancel();
			timer = null;
		}
		super.onDestroy();
	}

	public void stopCheck() {
		stopTask = true;
	}

	private boolean checkServerForRestart() {
		String response = "";
		String playerId = "";
		HttpURLConnection urlConnection = null;

		if (serviceCallbacks != null) {
			playerId = serviceCallbacks.getPlayerId();
		} else {
			Log.e(className, ".checkServerForRestart serviceCallbacks is null");
		}

		if (!playerId.isEmpty()) {
			try {
				URL url = new URL("https://ajax.playr.biz/watchdogs/" + playerId + "/command");
				Log.i(className, ".checkServerForRestart URL: " + url.toString());
				urlConnection = (HttpURLConnection) url.openConnection();
				InputStream inputStream = new BufferedInputStream(urlConnection.getInputStream());
				response = readStream(inputStream).trim();
			} catch (MalformedURLException e) {
				Log.e(className, ".checkServerForRestart: malformed URL exception opening connection; " + e.getMessage());
			} catch (IOException e) {
				Log.e(className, ".checkServerForRestart: IO exception opening connection; " + e.getMessage());
			} finally {
				urlConnection.disconnect();
			}
			Log.i(className, ".checkServerForRestart response: " + response);
		} else {
			Log.e(className, ".checkServerForRestart playerId is empty");
		}
		return (rebootResponse.equals(response));
	}

	private String readStream(InputStream inputStream) {
		try {
			BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream));
			StringBuilder total = new StringBuilder(inputStream.available());
			String line;
			while ((line = reader.readLine()) != null) {
				total.append(line).append('\n');
			}

			return total.toString();
		} catch (IOException e) {
			Log.e(className, ".readStream: IO exception reading inputStream; " + e.getMessage());
		}
		return "";
	}
}