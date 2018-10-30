package biz.playr;

import android.app.Application;
import android.util.Log;

/** Initializes native components when the user starts the application. */
public class MainApplication extends Application {
	private static final String className = "MainApplication";

	// Singleton instance
	private static MainApplication instance = null;

	@Override
	public void onCreate() {
		Log.i(className,"override onCreate");
		super.onCreate();

		// Setup singleton instance
		instance = this;
	}

	// Getter to access Singleton instance
	public static MainApplication getInstance() {
		return instance ;
	}
}