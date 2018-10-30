package biz.playr;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

public class BootUpReceiver extends BroadcastReceiver{
	private String className = "BootUpReceiver";
	/* this class is used to force a restart of the MainActivity after the device is rebooted
	 * for instance when Android has updated
	 * see the <receiver> section of the AndroidManifest file
	 * @see android.content.BroadcastReceiver#onReceive(android.content.Context, android.content.Intent)
	 */
	@Override
	public void onReceive(Context context, Intent intent) {
		Log.i(className,"override onReceive");
		if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
			/*
			 * The Intent is kept in synch with the Manifest and DefaultExceptionhandler
			 */
			Intent activityIntent = new Intent(context, MainActivity.class);
			activityIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP
					| Intent.FLAG_ACTIVITY_CLEAR_TASK
					| Intent.FLAG_ACTIVITY_NEW_TASK);
			activityIntent.setAction(Intent.ACTION_MAIN);
			activityIntent.addCategory(Intent.CATEGORY_LAUNCHER);
			context.startActivity(activityIntent);
			Log.i(className,"service started");
		}
		Log.i(className,"onReceive: end");
	}
}