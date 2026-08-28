package biz.playr;

import java.lang.Thread.UncaughtExceptionHandler;
import android.app.Activity;
import android.util.Log;
/*
 * see http://chintanrathod.com/auto-restart-application-after-crash-forceclose-in-android/
 */
class DefaultExceptionHandler implements UncaughtExceptionHandler {
	private static final String className = BuildConfig.APP_NAMESPACE + ".DefaultExcept";
	private final Activity activity;
	private final Thread.UncaughtExceptionHandler defaultUEH;

	DefaultExceptionHandler(Activity activity) {
		Log.i(className,"constructor");
		this.defaultUEH = Thread.getDefaultUncaughtExceptionHandler();
		this.activity = activity;
	}
	private Activity getActivity() {
		return this.activity;
	}

	@Override
	public void uncaughtException(Thread thread, Throwable ex) {

		// try {
			// Log the exception
			Log.e(className, ".uncaughtException: Uncaught exception handling started.");
			Log.e(className, "Exception message: " + ex.getMessage());
			Log.e(className, "Exception: " + ex.toString());
			Log.e(className, "Stack trace:");
			StackTraceElement[] arr = ex.getStackTrace();
			for (StackTraceElement element : arr) {
				Log.e(className, "    " + element.toString() + "\n");
			}
			// If the exception was thrown in a background thread inside
			// AsyncTask, then the actual exception can be found with getCause
			Throwable cause = ex.getCause();
			if (cause != null) {
				Log.e(className, "Cause: " + cause.toString());
				arr = cause.getStackTrace();
				for (StackTraceElement element : arr) {
					Log.e(className, "    " + element.toString() + "\n");
				}
			}

			if (getActivity().getApplicationContext().getResources().getBoolean(R.bool.restart)) {
				AppRestarter.restartAfterUncaughtException(getActivity(), false);
				Log.e(className, "uncaughtException: activity.finish() !!! About to restart application !!!");
				getActivity().finish();
				defaultUEH.uncaughtException(thread, ex);
			} else {
				// Pro Display usage; no restart of the MainActivity
				Log.i(className, ".uncaughtException MainActivity NOT started");
				defaultUEH.uncaughtException(thread, ex);
			}
//		} catch (Exception e) {
//			Log.e(className,".uncaughtException catch block: Exception message: " + e.getMessage());
//			e.printStackTrace();
//		}
	}
}
