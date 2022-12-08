package biz.playr;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

public class BootUpReceiver extends BroadcastReceiver{
	private static final String className = "biz.playr.BootUpReceive";
	private static final int identifier = 12345;
	/* this class is used to force a restart of the MainActivity after the device is rebooted
	 * for instance when Android has updated
	 * see the <receiver> section of the AndroidManifest file
	 * @see android.content.BroadcastReceiver#onReceive(android.content.Context, android.content.Intent)
	 */
	@Override
	public void onReceive(Context context, Intent intent) {
		Log.i(className,"override onReceive");
		Log.i(className,".onReceive, received intent:" + intent.getAction().toString());
		// do not check the intent.getAction() value since the QUICKBOOT_POWERON actions
		// possibly do not have an intent.ACTION_... equivalent
		// if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction()) || Intent.ACTION_REBOOT.equals(intent.getAction()) ||
		//		Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(intent.getAction())) {
			/*
			 * The Intent is kept in synch with the Manifest and DefaultExceptionhandler
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

			Intent activityIntent = new Intent(context, MainActivity.class);
			activityIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP
					| Intent.FLAG_ACTIVITY_CLEAR_TASK
					| Intent.FLAG_ACTIVITY_NEW_TASK);
			activityIntent.setAction(Intent.ACTION_MAIN);
			activityIntent.addCategory(Intent.CATEGORY_LAUNCHER);
			context.startActivity(activityIntent);
			Log.i(className, ".onReceive MainActivity started");
		// }
		Log.i(className, ".onReceive: end");
	}
}