package biz.playr;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

public class BootUpReceiver extends BroadcastReceiver{
	private static final String className = "bz.playr.BootUpReceiver";
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