package biz.playr;

import android.app.Application;
import android.util.Log;

/** Initializes native components when the user starts the application. */
public class MainApplication extends Application {
	private static final String className =  BuildConfig.APP_NAMESPACE + ".MainApplicati";

	// Singleton instance
	private static MainApplication instance = null;

	@Override
	public void onCreate() {
		Log.i(className,"override onCreate");
		super.onCreate();
		Log.i(className,".onCreate: Setup singleton instance");
		instance = this;
		// Do not start a foreground service or MainActivity from here.
		// Application.onCreate runs while the process is still in a cached/background
		// state (e.g. TV leaving standby). Android 14 then rejects
		// startForegroundService() (ForegroundServiceStartNotAllowedException) and
		// the process dies before the firmware's MainActivity start can complete.
		// Boot auto-start is handled by BootUpReceiver. Pro Display / firmware
		// starts MainActivity itself.
		Log.i(className,".onCreate end");
	}

	// Getter to access Singleton instance
	public static MainApplication getInstance() {
		return instance ;
	}
}
