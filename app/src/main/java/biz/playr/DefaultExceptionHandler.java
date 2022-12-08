package biz.playr;

import java.lang.Thread.UncaughtExceptionHandler;
import android.app.Activity;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.util.Log;
/*
 * see http://chintanrathod.com/auto-restart-application-after-crash-forceclose-in-android/
 */
class DefaultExceptionHandler implements UncaughtExceptionHandler {
	private static final String className = "biz.playr.DefaultExcept";
	// the restart delay is relatively long because this also affects the
	// time a user has when changing a setting or using any other app
	// since that will trigger a restart in the MainActivity that uses
	// the same restartDelay
	static final long restartDelay = 30000; // 30 seconds in milliseconds
	private Activity activity;
	private Thread.UncaughtExceptionHandler defaultUEH;

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
		Log.i(className,"uncaughtException handling -> restart after delay");

		// try {
			// first log the exception
			Log.e(className, "uncaughtException: Uncaught exception handling started.");
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
			Intent intent = new Intent(activity, biz.playr.MainActivity.class);

			intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP
							| Intent.FLAG_ACTIVITY_CLEAR_TASK
							| Intent.FLAG_ACTIVITY_NEW_TASK);
			intent.setAction(Intent.ACTION_MAIN);
			intent.addCategory(Intent.CATEGORY_LAUNCHER);

			// As of S/31 FLAG_IMMUTABLE/FLAG_MUTABLE is required
			PendingIntent pendingIntent = PendingIntent.getActivity(
					biz.playr.MainApplication.getInstance().getBaseContext(), 0, intent, PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_IMMUTABLE);

			//Following code will restart your application after <delay> seconds
			AlarmManager mgr = (AlarmManager) biz.playr.MainApplication.getInstance().getBaseContext().getSystemService(Context.ALARM_SERVICE);
			mgr.set(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + restartDelay, pendingIntent);

			//This will finish your activity manually
			Log.e(className,"uncaughtException: activity.finish() !!! About to restart application !!!");
			getActivity().finish();

			defaultUEH.uncaughtException(thread, ex);
			//This will stop your application and take out from it.
//			Log.e(className,"uncaughtException: System.exit(2) !!! About to restart application !!!");
//			System.exit(2);
//		} catch (Exception e) {
//			Log.e(className,".uncaughtException catch block: Exception message: " + e.getMessage());
//			e.printStackTrace();
//		}
	}
}
