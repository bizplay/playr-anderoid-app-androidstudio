package biz.playr;

import java.util.List;
import java.util.Timer;
import java.util.TimerTask;

import android.app.ActivityManager;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;
//import android.os.PowerManager;
import android.util.Log;

public class PersistService extends Service {
	// see http://stackoverflow.com/questions/18276355/how-to-keep-a-foreground-app-running-24-7
	static final long intervalBetweenForegroundChecks = 30000; // 30 seconds in milliseconds
	private static final String className = "PersistService";
	//private static final String YOUR_APP_PACKAGE_NAME = "biz.playr";

	// see http://stackoverflow.com/questions/6446221/get-context-in-a-service
	// and http://stackoverflow.com/questions/7619917/how-to-get-context-in-android-service-class
	private static Context relevantContext;
	private static boolean stopTask;
	private Timer timer = null;
//	private PowerManager.WakeLock mWakeLock = null;

	// getting list of running apps has become less trivial
	// see http://stackoverflow.com/questions/31156313/activitymanager-getrunningtasks-is-deprecated-android
	// backup version of below method
//	private static boolean isForegroundApp(Context context) {
//		ActivityManager activityManager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
//		List<ActivityManager.RunningAppProcessInfo> runningProcesses = activityManager.getRunningAppProcesses();
//		for (ActivityManager.RunningAppProcessInfo processInfo : runningProcesses) {
//			if (processInfo.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND) {
//				for (String activeProcess : processInfo.pkgList) {
//					if (activeProcess.equals(context.getPackageName())) {
//						return true;
//					}
//				}
//			}
//		}
//
//		return false;
//	}

	// backup version of above method
	private static boolean isForegroundApp(Context context) {
	    // Get the Activity Manager
	    ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);

	    // Get a list of running tasks, we are only interested in the last one,
	    // the top most so we give a 1 as parameter so we only get the topmost.
	    List<ActivityManager.RunningAppProcessInfo> task = manager.getRunningAppProcesses();

	    // Get the info we need for comparison.
	    ComponentName componentInfo = task.get(0).importanceReasonComponent;

	    // Check if it matches our package name.
	    if(componentInfo.getPackageName().equals(context.getPackageName()))
	        return true;

	    // If not then our app is not on the foreground.
	    return false;
	}


	@Override
	public void onCreate() {
		Log.i(className,"override onCreate");
		super.onCreate();

		relevantContext = this;
		stopTask = false;

		// Optional: Screen Always On Mode!
		// Screen will never switch off this way
//		mWakeLock = null;
//		if (settings.pmode_scrn_on){
//			PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
//			mWakeLock = pm.newWakeLock(PowerManager.SCREEN_DIM_WAKE_LOCK, "a_tag");
			// alternatively
//			mWakeLock = pm.newWakeLock(PowerManager.FULL_WAKE_LOCK, "a_tag");
//			mWakeLock.acquire();
//		}

		// Start your (polling) task
		TimerTask task = new TimerTask() {
			@Override
			public void run() {
				Log.i(className,".onCreate TimerTask task.run()");
				// If you wish to stop the task/polling
				if (stopTask){
					this.cancel();
				}

				// The first in the list of RunningTasks is always the foreground task.
//				ActivityManager activityManager = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
//				RunningTaskInfo foregroundTaskInfo = activityManager.getRunningTasks(1).get(0);
//				String foregroundTaskPackageName = foregroundTaskInfo.topActivity.getPackageName();

				// Check foreground app: If it is not in the foreground... bring it!
//				if (!foregroundTaskPackageName.equals(YOUR_APP_PACKAGE_NAME)){
				if (!isForegroundApp(relevantContext)){
					Intent LaunchIntent = getPackageManager().getLaunchIntentForPackage(relevantContext.getPackageName());
					startActivity(LaunchIntent);
				}
			}
		};
		timer = new Timer();
		timer.scheduleAtFixedRate(task, intervalBetweenForegroundChecks, intervalBetweenForegroundChecks);
	}

	@Override
	public void onDestroy(){
		Log.i(className,"override onDestroy");
		stopTask = true;
//		if (mWakeLock != null) {
//			mWakeLock.release();
//		}
		if (timer != null) {
			timer.cancel();
			timer = null;
		}
		if (relevantContext != null) {
			relevantContext = null;
		}
		super.onDestroy();
	}

	@Override
	public IBinder onBind(Intent arg0) {
		Log.i(className,"override onBind");
		// TODO Auto-generated method stub
		return null;
	}
}
