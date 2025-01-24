package biz.playr;

// can't get this import to work without duplicate class compile errors....
//import static androidx.core.content.ContextCompat.getString;
import static androidx.preference.PreferenceManager.getDefaultSharedPreferences;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;

import java.util.Date;

public class BootUpReceiver extends BroadcastReceiver{
	private static final String className = "biz.playr.BootUpReceive";
	private static final int identifier = 12345;
	/* this class is used to force a (re)start of the MainActivity after the device is rebooted
	 * for instance when Android has updated
	 * see the <receiver> section of the AndroidManifest file
	 * @see android.content.BroadcastReceiver#onReceive(android.content.Context, android.content.Intent)
	 */
	@Override
	public void onReceive(Context context, Intent intent) {
		Log.i(className, "override onReceive");
		Log.i(className, ".onReceive, received intent:     " + intent.getAction());
		Log.i(className, ".onReceive, activity state:      " + getActivityState(context));
		Date createdAt = getActivityCreatedAt(context);
		Log.i(className, ".onReceive, activity started at: " + createdAt.getTime() + " or " + createdAt);

		storeBootCompletedAt(context);

		// do not check the intent.getAction() value since the QUICKBOOT_POWERON actions
		// possibly do not have an intent.ACTION_... equivalent
		// if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction()) || Intent.ACTION_REBOOT.equals(intent.getAction()) ||
		//		Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(intent.getAction())) {
			/*
			 * The Intent is kept in sync with the Manifest and DefaultExceptionhandler
			 */
			// both these variants should work
			// Intent activityIntent = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
			// activityIntent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP
			//		| Intent.FLAG_ACTIVITY_CLEAR_TASK
			//		| Intent.FLAG_ACTIVITY_NEW_TASK);

//			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
//				Intent activityIntent = new Intent(context, MainActivity.class);
//				activityIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP
//						| Intent.FLAG_ACTIVITY_CLEAR_TASK
//						| Intent.FLAG_ACTIVITY_NEW_TASK);
//				activityIntent.setAction(Intent.ACTION_MAIN);
//				activityIntent.addCategory(Intent.CATEGORY_LAUNCHER);
//				// As of S/31 FLAG_IMMUTABLE/FLAG_MUTABLE is required
//				PendingIntent localPendingIntent = PendingIntent.getActivity(context, 0, activityIntent, PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_IMMUTABLE);
//				try {
//					localPendingIntent.send(context, identifier, activityIntent);
//				} catch (PendingIntent.CanceledException e) {
//					Log.e(className, ".onReceive: PendingIntent cancelled: " + e.getMessage());
//				}
//				Log.i(className, ".onReceive: called PendingIntent.send()");
//			} else {
//				Intent activityIntent = new Intent(context, MainActivity.class);
//				activityIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP
//						| Intent.FLAG_ACTIVITY_CLEAR_TASK
//						| Intent.FLAG_ACTIVITY_NEW_TASK);
//				activityIntent.setAction(Intent.ACTION_MAIN);
//				activityIntent.addCategory(Intent.CATEGORY_LAUNCHER);
//				context.startActivity(activityIntent);
//				Log.i(className, ".onReceive: called context.startActivity()");
//			}

		if (context.getResources().getBoolean(R.bool.auto_start)) {
			Intent activityIntent = new Intent(context, MainActivity.class);
			activityIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP
					| Intent.FLAG_ACTIVITY_CLEAR_TASK
					| Intent.FLAG_ACTIVITY_NEW_TASK);
			activityIntent.setAction(Intent.ACTION_MAIN);
			activityIntent.addCategory(Intent.CATEGORY_LAUNCHER);
			context.startActivity(activityIntent);
			Log.i(className, ".onReceive MainActivity started");
		} else {
			// Pro Display usage; no auto start of the MainActivity
			Log.i(className, ".onReceive MainActivity NOT started");
		}
// }
		Log.i(className, ".onReceive: end");
	}

	private Date getBootCompletedAt(Context context) {
		SharedPreferences defaultSharedPreferences = getDefaultSharedPreferences(context);
//		return new Date(defaultSharedPreferences.getLong(getString(context, R.string.boot_completed_at_key), 0));
		return new Date(defaultSharedPreferences.getLong("bootCompletedAt", 0));
	}

	private void storeBootCompletedAt(Context context) {
		// Using Instant would be easier but Instant.now() is only available from API level 26
		Date now = new Date();
		long bootCompletedAt = now.getTime();

		SharedPreferences defaultSharedPreferences = getDefaultSharedPreferences(context);
		if (defaultSharedPreferences != null) {
			SharedPreferences.Editor editor = defaultSharedPreferences.edit();
//			editor.putLong(getString(context, R.string.boot_completed_at_key), bootCompletedAt);
			editor.putLong("bootCompletedAt", bootCompletedAt);
			editor.apply(); // when this preferences value is used consider synchronous alternative: commit();
			Log.i(className, ".storeBootCompletedAt: received (and stored) BOOT_COMPLETED at " + bootCompletedAt + " or " + now);
		} else {
			Log.e(className, ".storeBootCompletedAt: default shared preferences not found!");
		}
	}

	private Date getActivityCreatedAt(Context context) {
		SharedPreferences defaultSharedPreferences = getDefaultSharedPreferences(context);
//		return new Date(defaultSharedPreferences.getLong(getString(context, R.string.activity_created_at_key), 0));
		return new Date(defaultSharedPreferences.getLong("activityCreatedAt", 0));
	}

	private int getActivityState(Context context) {
		SharedPreferences defaultSharedPreferences = getDefaultSharedPreferences(context);
//		return defaultSharedPreferences.getInt(getString(context, R.string.activity_state_key), -1);
		return defaultSharedPreferences.getInt("activityState", -1);
	}

	private void storeActivityCreatedAt(Context context) {
		// Using Instant would be easier but Instant.now() is only available from API level 26
		Date now = new Date();
		long createdAt = now.getTime();

		SharedPreferences defaultSharedPreferences = getDefaultSharedPreferences(context);
		if (defaultSharedPreferences != null) {
			SharedPreferences.Editor editor = defaultSharedPreferences.edit();
//			editor.putInt(getString(context, R.string.activity_state_key), 1);
//			editor.putLong(getString(context, R.string.activity_created_at_key), createdAt);
			editor.putInt("activityState", 1);
			editor.putLong("activityCreatedAt", createdAt);
			editor.apply(); // when this preferences value is used consider synchronous alternative: commit();
			Log.i(className, ".storeActivityCreatedAt: activity state (1) and activity created at " + createdAt + " were stored");
		} else {
			Log.e(className, ".storeActivityCreatedAt: default shared preferences not found!");
		}
	}
}