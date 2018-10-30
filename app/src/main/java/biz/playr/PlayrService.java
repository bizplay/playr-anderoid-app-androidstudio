package biz.playr;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.util.Log;

public class PlayrService extends Service{
	private static final String className = "biz.playr.PlayrService";

	@Override
	public void onCreate() {
		Log.i(className,"override onCreate");
		super.onCreate();
	}
	@Override
	public int onStartCommand (Intent intent, int flags, int startId) {
		Log.i(className,"override onStartCommand");
		return super.onStartCommand(intent, flags, startId);
	}
	@Override
	public void onDestroy() {
		Log.i(className,"override onDestroy");
		super.onDestroy();
	}
	@Override
	public IBinder onBind(Intent intent) {
		Log.i(className,"override onBind");
		// TODO start MainActivity
		return null;
	}
}