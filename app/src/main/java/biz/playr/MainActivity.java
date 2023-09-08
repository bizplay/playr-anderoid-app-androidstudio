package biz.playr;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.Calendar;
import java.util.Date;
import java.util.UUID;

import android.app.ActivityManager;
import android.app.UiModeManager;
import android.content.ComponentCallbacks2;
import android.content.ComponentName;
import android.content.ServiceConnection;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.BaseBundle;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.PersistableBundle;
import android.provider.Settings;
import android.security.NetworkSecurityPolicy;
import android.util.Log;
import android.view.MotionEvent;
import android.view.PixelCopy;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.ActionBar;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.view.View;
import android.os.Handler;
import android.webkit.ConsoleMessage;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebResourceError;
import android.webkit.WebView;
import android.webkit.WebSettings;
import android.webkit.WebViewClient;
//import android.Manifest;
//import android.graphics.Canvas;
//import android.media.ThumbnailUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.browser.customtabs.CustomTabsCallback;
import androidx.browser.customtabs.CustomTabsClient;
import androidx.browser.customtabs.CustomTabsIntent;
import androidx.browser.customtabs.CustomTabsServiceConnection;
import androidx.browser.customtabs.CustomTabsSession;
import androidx.browser.customtabs.TrustedWebUtils;
import androidx.webkit.WebViewCompat;
//import androidx.core.app.ActivityCompat;
//import androidx.webkit.WebResourceRequestCompat;
//import androidx.webkit.WebSettingsCompat;
//import androidx.webkit.WebViewClientCompat;
//import javax.net.ssl.HandshakeCompletedListener;

public class MainActivity extends Activity implements IServiceCallbacks {
	private WebView webView = null;
	private Bundle webViewContent = null;
	private static final String webArchiveFileName = "WebViewWebArchive.mht";
	private static final String screenShotFileName = "ScreenShot.jpg";
	private static final int jpegQuality = 85;
	private static final String className = "biz.playr.MainActivity";
	private CheckRestartService checkRestartService;
	private boolean bound = false;
	private ServiceConnection serviceConnection = null;
	// Memory reporting
	private ActivityManager.MemoryInfo firstMemoryInfo = null;
	private long firstAvailableHeapSizeInMB = 0;
	private Handler memoryCheckHandler = null;
	private Runnable memoryCheckRunner = null;
	private boolean continueMemoryCheck = true;
	private Handler pixelCopyHandler = null;
	private Bitmap screenshotAsBitmap = null;
	private static final long MB = 1048576L;
	private static final long memoryCheckInterval = 5*60*1000; // 5 minutes
	// TWA related
	private boolean chromeVersionChecked = false;
	private boolean twaWasLaunched = false;
	private Bundle currentSavedInstanceState;
	private PersistableBundle persistableWebViewState;
	private String PersistenceFileName = "PersistedWebViewState";
	private static final int SESSION_ID = 96375;
	private static final String TWA_WAS_LAUNCHED_KEY = "android.support.customtabs.trusted.TWA_WAS_LAUNCHED_KEY";
	private static final int REQUEST_OVERLAY_PERMISSION = 1;

	@Nullable
	private MainActivity.TwaCustomTabsServiceConnection twaServiceConnection;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		Log.i(className, "override onCreate, savedInstanceState: " + (savedInstanceState == null ? "null" : "not null"));
		currentSavedInstanceState = savedInstanceState;
		super.onCreate(savedInstanceState);

		reportSystemInformation();
		// Setup restarting of the app when it crashes
		Log.i(className, "onCreate: setup restarting of app on crash");
		Thread.setDefaultUncaughtExceptionHandler(new DefaultExceptionHandler(this));

		// Test exception handling by throwing an exception 20 seconds from now
		// Handler handler = new Handler();
		// handler.postDelayed(new Runnable() {
		// public void run() {
		// throw new IllegalArgumentException("Test exception");
		// }
		// }, 20000);

		// Find out how much memory is available; availMem, totalMem, threshold and lowMemory are available as values
		this.firstMemoryInfo = getAvailableMemory();
		Runtime runtime = Runtime.getRuntime();
		this.firstAvailableHeapSizeInMB = (runtime.maxMemory()/MB) - ((runtime.totalMemory() - runtime.freeMemory())/MB);
		this.startMemoryCheckingAtInterval(memoryCheckInterval);

		// start the watchdog service
//		CheckRestartService crs = CheckRestartService.new();
//		crs.startService();

		// Set up looks of the view
		getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
//		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
//			getWindow().addFlags(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);
//		}
		requestWindowFeature(Window.FEATURE_NO_TITLE);
		View decorView = getWindow().getDecorView();
		decorView.setOnSystemUiVisibilityChangeListener(new View.OnSystemUiVisibilityChangeListener() {
			@Override
			public void onSystemUiVisibilityChange(int visibility) {
				// Note that system bars will only be "visible" if none of the
				// LOW_PROFILE, HIDE_NAVIGATION, or FULLSCREEN flags are set.
				if ((visibility & View.SYSTEM_UI_FLAG_FULLSCREEN) == 0) {
					// bars are visible => user touched the screen, make the bars disappear again in 2 seconds
					Handler handler = new Handler();
					handler.postDelayed(new Runnable() {
						public void run() {
							hideBars();
						}
					}, 2000);
				} else {
					// The system bars are NOT visible => do nothing
				}
			}
		});
		decorView.setKeepScreenOn(true);

		// request overlay permission needed for 'auto start' ability
		requestManageOverlayPermission(getApplicationContext());

		// create Trusted Web Access or fall back to a WebView
		Log.i(className, "onCreate: create Trusted Web Access or fall back to a WebView");
		openBrowserView(false,
						(savedInstanceState != null && savedInstanceState.getBoolean(MainActivity.TWA_WAS_LAUNCHED_KEY)),
						retrieveOrGeneratePlayerId());
	}

	@Override
	public void onActivityResult(int requestCode, int resultCode, Intent data)
	{
		Log.i(className, "override onActivityResult");
		if (resultCode == Activity.RESULT_CANCELED) {
			Log.i(className, "onActivityResult: RESULT_CANCELED - activity was cancelled, resultCode: " + resultCode);
			// code to handle cancelled state
		}
		else if (requestCode == REQUEST_OVERLAY_PERMISSION) {
			Log.i(className, "onActivityResult: REQUEST_OVERLAY_PERMISSION - overlay permission granted! resultCode: " + resultCode);
			openBrowserView(false,
					(currentSavedInstanceState != null && currentSavedInstanceState.getBoolean(MainActivity.TWA_WAS_LAUNCHED_KEY)),
					retrieveOrGeneratePlayerId());
		}
	}
	public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
		if (requestCode == REQUEST_OVERLAY_PERMISSION) {
			if (grantResults.length == 1 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
				Log.i(className, "onRequestPermissionsResult: REQUEST_OVERLAY_PERMISSION - overlay permission granted! permission: " + permissions[0]);
			} else {
				// Permission request was denied.
				Log.e(className, "onRequestPermissionsResult: REQUEST_OVERLAY_PERMISSION - overlay permission NOT granted! permission: " + permissions[0]);
			}
		} else {
			// nothing currently
		}
	}

	protected CustomTabsSession getSession(CustomTabsClient client) {
		return client.newSession((CustomTabsCallback)null, SESSION_ID);
	}

    protected CustomTabsIntent getCustomTabsIntent(CustomTabsSession session) {
        CustomTabsIntent.Builder builder = new CustomTabsIntent.Builder(session);
        return builder.build();
    }

	public void handleUncaughtException(Thread paramThread,
		Throwable paramThrowable) {
		Log.e(className, "handleUncaughtException; paramThread: " + paramThread
				+ ", paramThrowable: " + paramThrowable);
		// restartActivity();
		recreate();
	}

	public void restartDelayed() {
		Log.i(className, "restartDelayed");

		// the context of the activityIntent might need to be the running PlayrService
		// keep the Intent in sync with the Manifest and DefaultExceptionHandler
//		PendingIntent localPendingIntent = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_ONE_SHOT);
		Intent activityIntent = new Intent(MainActivity.this.getBaseContext(), biz.playr.MainActivity.class);
		activityIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP
				| Intent.FLAG_ACTIVITY_CLEAR_TASK
				| Intent.FLAG_ACTIVITY_NEW_TASK);
		activityIntent.setAction(Intent.ACTION_MAIN);
		activityIntent.addCategory(Intent.CATEGORY_LAUNCHER);
		// As of S/31 FLAG_IMMUTABLE/FLAG_MUTABLE is required
		PendingIntent localPendingIntent = PendingIntent.getActivity(MainActivity.this.getBaseContext(), 0, activityIntent, PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_IMMUTABLE);
		// delay start so this activity can be ended before the new one starts
		// Following code will restart application after DefaultExceptionHandler.restartDelay milliseconds
		AlarmManager mgr =  (AlarmManager) getSystemService(Context.ALARM_SERVICE);
		if (mgr != null) {
			Log.i(className, "restartDelayed: setting alarm manager to restart with a delay of " +  DefaultExceptionHandler.restartDelay/1000 + " seconds");
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
				try {
					mgr.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + DefaultExceptionHandler.restartDelay, localPendingIntent);
					Log.i(className, "restartDelayed: called setExactAndAllowWhileIdle");
				} catch (SecurityException ex) {
					Log.e(className, "restartDelayed: setExactAndAllowWhileIdle caused security exception: " + ex);
				}
			} else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
				try {
					mgr.setExact(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + DefaultExceptionHandler.restartDelay, localPendingIntent);
					Log.i(className, "restartDelayed: called setExact");
				} catch (SecurityException ex) {
					Log.e(className, "restartDelayed: setExactAndAllowWhileIdle caused security exception: " + ex);
					throw ex;
				}
			} else {
				mgr.set(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + DefaultExceptionHandler.restartDelay, localPendingIntent);
				Log.i(className, "restartDelayed: called set");
			}
		}
		Log.i(className, "restartDelayed: end");
	}

	// implement the ComponentCallbacks2 interface
	/**
	 * Release memory when the UI becomes hidden or when system resources become low.
	 * @param level the memory-related event that was raised.
	 */
	@Override
	public void onTrimMemory(int level) {
		Log.i(className, "override onTrimMemory");
		super.onTrimMemory(level);
		Log.e(className, "********************\n*** onTrimMemory - level: " + level + "\n*** memory status: " + analyseMemoryStatus() + "\n****************************************");

		// Determine which lifecycle or system event was raised.
		switch (level) {
			case ComponentCallbacks2.TRIM_MEMORY_BACKGROUND:
			case ComponentCallbacks2.TRIM_MEMORY_MODERATE:
			case ComponentCallbacks2.TRIM_MEMORY_COMPLETE:
                // Release as much memory as the process can.
			case ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN:
				// while testing it turned out that moments after available
				// memory was at 112% of threshold (in VM) the highest level
				// reported was TRIM_MEMORY_RUNNING_CRITICAL
			case ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL:
				// Release any UI objects that currently hold memory.
				// The user interface has moved to the background.
				// ==>> restart the application
				this.restartActivity();
				break;
			case ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW:
				// ==>> dump browser view and recreate it
				this.recreateBrowserView();
				break;
			case ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE:
				// ==>> reload browser view
				this.reloadBrowserView();
				break;
			default:
                // Release any non-critical data structures.
				//
                // The app received an unrecognized memory level value
                // from the system. Treat this as a generic low-memory message.
				// ==>> reload browser view
				Log.e(className, "onTrimMemory - Non standard level detected: " + level);
				break;
		}
	}
	// end of implementation ComponentCallbacks2

	// implement the IServiceCallbacks interface
	public void restartActivityWithDelay() {
		this.restartActivity();
	}

	public String getPlayerId() {
		return getStoredPlayerId();
	}
	// end of implementation IServiceCallbacks

	public void restartActivity() {
		Log.i(className, "restartActivity: setting up delayed restart");
		restartDelayed();
		Log.i(className, "restartActivity: killing this process");
		setResult(RESULT_OK);
		Log.i(className, "restartActivity: calling finish()");
		finish();
//		Log.i(className, "restartActivity: calling killProcess()");
//		android.os.Process.killProcess(android.os.Process.myPid());
//		Log.i(className, "restartActivity: calling exit(2)");
//		System.exit(2);
	}

	public void persistWebContent() {
		Bundle webContent = new Bundle();
		Log.i(className, "persistWebContent");

		if (webView != null) {
			webView.post(new Runnable() {
				@Override
				public void run() {
					Log.i(className, "persistWebContent webView.post .run");

//					webView.saveState(webContent);
//					saveBundle(webContent);
//					webViewContent = new Bundle(webContent);
					webView.saveWebArchive(webArchiveFilePath());

					Log.i(className, "persistWebContent webView.post .run done");
				}
			});
		}
	}
	public void restoreWebContent() {
		Log.i(className, "restoreWebContent");
//		Bundle webContent = retrieveBundle();

		if (webView != null) {
			webView.post(new Runnable() {
				@Override
				public void run() {
					Log.i(className, "restoreWebContent webView.post .run");
					webView.loadUrl("file://" + webArchiveFilePath());
//					webView.restoreState(webContent);
//					webView.restoreState(webViewContent);
					Log.i(className, "restoreWebContent webView.post .run done");
				}
			});
		}
	}

	// See https://stackoverflow.com/a/49857641
	private static PersistableBundle toPersistableBundle(Bundle bundle) {
		if (bundle == null) {
			return null;
		}
		PersistableBundle persistableBundle = null;
		if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
			persistableBundle = new PersistableBundle();
			for (String key : bundle.keySet()) {
				Object value = bundle.get(key);
				if (isPersistableBundleType(value)) {
					putIntoBundle(persistableBundle, key, value);
				}
			}
		}
		return persistableBundle;
	}
	private static Bundle toBundle(PersistableBundle persistableBundle) {
		if (persistableBundle == null) {
			return null;
		}
		if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
			return new Bundle(persistableBundle);
		} else {
			return null;
		}
	}
	private static boolean isPersistableBundleType(Object value) {
		if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
			return ((value instanceof PersistableBundle) ||
					(value instanceof Integer) || (value instanceof int[]) ||
					(value instanceof Long) || (value instanceof long[]) ||
					(value instanceof Double) || (value instanceof double[]) ||
					(value instanceof String) || (value instanceof String[]) ||
					(value instanceof Boolean) || (value instanceof boolean[])
			);
		} else {
			return false;
		}
	}
	private static void putIntoBundle(BaseBundle baseBundle, String key, Object value) throws IllegalArgumentException {
		if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
			if (value == null) {
				throw new IllegalArgumentException("Unable to determine type of null values");
			} else if (value instanceof Integer) {
				baseBundle.putInt(key, (int) value);
			} else if (value instanceof int[]) {
				baseBundle.putIntArray(key, (int[]) value);
			} else if (value instanceof Long) {
				baseBundle.putLong(key, (long) value);
			} else if (value instanceof long[]) {
				baseBundle.putLongArray(key, (long[]) value);
			} else if (value instanceof Double) {
				baseBundle.putDouble(key, (double) value);
			} else if (value instanceof double[]) {
				baseBundle.putDoubleArray(key, (double[]) value);
			} else if (value instanceof String) {
				baseBundle.putString(key, (String) value);
			} else if (value instanceof String[]) {
				baseBundle.putStringArray(key, (String[]) value);
			} else if (value instanceof PersistableBundle) {
				if (baseBundle instanceof PersistableBundle)
					((PersistableBundle) baseBundle).putPersistableBundle(key, (PersistableBundle) value);
				else if (baseBundle instanceof Bundle)
					((Bundle) baseBundle).putBundle(key, toBundle((PersistableBundle) value));
			} else if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP_MR1) {
				if (value instanceof Boolean) {
					baseBundle.putBoolean(key, (boolean) value);
				} else if (value instanceof boolean[]) {
					baseBundle.putBooleanArray(key, (boolean[]) value);
				}
			} else {
				Log.e(className, "putIntoBundle; Objects of type " + value.getClass().getSimpleName() +
						" can not be put into a " + BaseBundle.class.getSimpleName());
				throw new IllegalArgumentException("Objects of type " + value.getClass().getSimpleName()
						+ " can not be put into a " + BaseBundle.class.getSimpleName());
			}
		}
	}

//	private PersistableBundle retrievePersistedBundle() {
////		String persistedBundleFileName = getApplicationContext().getFilesDir().getAbsolutePath() + "/persistence/" + PersistenceFileName;
//		Log.i(className, "retrievePersistedBundle");
//		FileInputStream fileInputStream;
//		PersistableBundle bundle = null;
//		try
//		{
//			fileInputStream = openFileInput(PersistenceFileName);
//			if (fileInputStream != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
//				Log.i(className, "retrievePersistedBundle readFromStream");
//				bundle = PersistableBundle.readFromStream(fileInputStream);
//			}
//			fileInputStream.close();
//			Log.i(className, "retrievePersistedBundle done");
//		} catch (Exception ex)
//		{
//			Log.e(className, "PersistableBundle; Error opening persistent storage: " + ex);
//		}
//		return bundle;
//	}
	private Bundle retrieveBundle() {
		Log.i(className, "retrieveBundle");
		FileInputStream fileInputStream;
		Parcel parcel = Parcel.obtain();
		Bundle bundle = null;
		ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
		byte[] byteArray = new byte[1024];
		int bytesRead = 0;

		try
		{
			fileInputStream = openFileInput(PersistenceFileName);
			if (fileInputStream != null) {
				Log.i(className, "retrieveBundle; readFromFileInputStream");
				while ((bytesRead = fileInputStream.read(byteArray)) != -1) {
					byteArrayOutputStream.write(byteArray, 0, bytesRead);
				}
				byte[] data = byteArrayOutputStream.toByteArray();
				Log.i(className, "retrieveBundle; data of length " + (data != null ? data.length : 0) + " has been read");
				parcel.unmarshall(data, 0, data.length);
				bundle = Bundle.CREATOR.createFromParcel(parcel);
				Log.i(className, "retrieveBundle; data: " + (data != null && data.length > 0 ? data.toString() : "<null>"));
				Log.i(className, "retrieveBundle; bundle: " + (bundle != null ? bundle.toString() : "<null>"));
			}
			fileInputStream.close();
			Log.i(className, "retrieveBundle; done");
		} catch (Exception ex)
		{
			Log.e(className, "PersistableBundle; Error opening persistent storage: " + ex);
		}
		return bundle;
	}

//	private void savePersistableBundle(PersistableBundle bundle) {
////		String persistedBundleFileName = getApplicationContext().getFilesDir().getAbsolutePath() + "/persistence/" + PersistenceFileName;
////		String dir = Environment.getExternalStorageDirectory().toString() + "/biz.playr/";
////		File newdir = new File(dir);
////		newdir.mkdirs();
////		File dataFile = new File(newdir, PersistenceFileName);
//		Log.i(className, "savePersistableBundle");
//		FileOutputStream fileOutputStream;
//		try
//		{
////			fileOutputStream = new FileOutputStream(imageFileName);
//			fileOutputStream = openFileOutput(PersistenceFileName, MODE_PRIVATE);
//			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
//				Log.i(className, "savePersistableBundle writeToStream");
//				bundle.writeToStream(fileOutputStream);
//			}
//			fileOutputStream.flush();
//			fileOutputStream.close();
//			Log.i(className, "savePersistableBundle done");
////			fileOutputStream = null;
//		} catch (Exception ex)
//		{
//			Log.e(className, "savePersistableBundle; Error opening persistent storage: " + ex);
//		}
//	}
	private void saveBundle(Bundle bundle) {
		Log.i(className, "saveBundle");
		FileOutputStream fileOutputStream;
		Parcel parcel = Parcel.obtain();
		try
		{
			fileOutputStream = openFileOutput(PersistenceFileName, MODE_PRIVATE);
			bundle.writeToParcel(parcel, 0);
			byte[] data = parcel.marshall();
			fileOutputStream.write(data);
			fileOutputStream.flush();
			fileOutputStream.close();
			Log.i(className, "saveBundle; bundle was written, data length: " + (data != null ? data.length : 0));
			Log.i(className, "saveBundle; data: " + (data != null && data.length > 0 ? data.toString() : "<null>"));
			Log.i(className, "saveBundle; bundle: " + (bundle != null ? bundle.toString() : "<null>"));
		} catch (Exception ex)
		{
			Log.e(className, "saveBundle; Error opening persistent storage: " + ex);
		}
	}
	private void saveWebViewContent() {
		if (webView != null) {
			webView.saveWebArchive(webArchiveFilePath());
		}
	}

	@Override
	protected void onSaveInstanceState(Bundle outState) {
		Log.i(className, "#######################################################");
		Log.i(className, "####                                                ###");
		Log.i(className, "####                                                ###");
		Log.i(className, "override onSaveInstanceState, outState: " + (outState == null ? "null" : "not null"));
		Log.i(className, "####                                                ###");
		Log.i(className, "####                                                ###");
		Log.i(className, "#######################################################");
		if (webView != null) { webView.saveState(outState); }
		outState.putBoolean(MainActivity.TWA_WAS_LAUNCHED_KEY, this.twaWasLaunched);
		super.onSaveInstanceState(outState);
	}

	@Override
	protected void onRestoreInstanceState(Bundle savedInstanceState) {
		Log.i(className, "################################################################");
		Log.i(className, "####                                                         ###");
		Log.i(className, "####                                                         ###");
		Log.i(className, "override onRestoreInstanceState, savedInstanceState: " + (savedInstanceState == null ? "null" : "not null"));
		Log.i(className, "####                                                         ###");
		Log.i(className, "####                                                         ###");
		Log.i(className, "################################################################");
		super.onRestoreInstanceState(savedInstanceState);
		if (savedInstanceState != null && !savedInstanceState.isEmpty()) {
			if (currentSavedInstanceState == null) {
				Log.i(className, ".onRestoreInstanceState, set currentSavedInstanceState");
				currentSavedInstanceState = savedInstanceState;
			}
			if (webView != null) { webView.restoreState(savedInstanceState); }
			this.twaWasLaunched = savedInstanceState.getBoolean(MainActivity.TWA_WAS_LAUNCHED_KEY);
		}
	}

	@Override
	protected void onResume() {
		Log.i(className, "override onResume");
		super.onResume();

		hideBars();
		if (webView != null) { webView.onResume(); }
	}

	@Override
	public void onWindowFocusChanged(boolean hasFocus) {
		Log.i(className, "override onWindowFocusChanged");
		super.onWindowFocusChanged(hasFocus);
		if (hasFocus) {
			hideBars();
		}
	}

	@Override
	protected void onStart() {
		Log.i(className, "override onStart");
		super.onStart();
		if (this.twaServiceConnection != null) {
			// bind to CheckRestartService
			Intent intent = new Intent(this, CheckRestartService.class);
//			intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
			bindService(intent, (ServiceConnection) this.twaServiceConnection, Context.BIND_AUTO_CREATE);
			// checkRestartService = TODO how do we point this attribute to the service instance
			Log.i(className, "onStart: restart service is bound to twaServiceConnection (TWA is used) [BIND_AUTO_CREATE]");
			bound = true;
		} else if (this.webView != null) {
			if (this.checkRestartService == null) {
				Log.e(className, "onStart: webView is defined but checkRestartService is null.");
			}
			Intent intent = new Intent(this, CheckRestartService.class);
//			intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
			bindService(intent, this.serviceConnection, Context.BIND_AUTO_CREATE);
			Log.i(className, "onStart: restart service is bound to serviceConnection (WebView is used) [BIND_AUTO_CREATE]");
			bound = true;
			if (this.checkRestartService == null) {
				Log.e(className, "onStart: checkRestartService is null after bindService.");
			}
		} else {
			Log.e(className, "onStart: twaServiceConnection and webView are null; restart service not bound");
		}
	}

	@Override
	protected void onRestart() {
		Log.i(className, "override onRestart");
		super.onRestart();
		if (this.twaWasLaunched) {
			this.finish();
		}
	}

	@Override
	protected void onPause() {
		Log.i(className, "override onPause");
		if (webView != null) { webView.onPause(); }
		super.onPause();
	}

	protected void onStop() {
		Log.i(className, "override onStop");
		this.unBindServiceConnection();
		// The application is pushed into the background
		// This method is also called when the device is turned (portrait/landscape
		// switch) and will result in repeated restart of the app
		// detecting rotation to prevent unnecessary calls to restartDelayed is
		// supposed to be complex and may require logic that spans onStop and onCreate
		// since these are called by the Android system when the screen is rotated
		// see: https://stackoverflow.com/questions/6896243/how-can-i-detect-screen-rotation
		// and: https://stackoverflow.com/questions/4843809/how-do-i-detect-screen-rotation
		// restartDelayed();
		super.onStop();
		Log.i(className, "onStop: end");
	}

	@Override
	protected void onDestroy() {
		Log.i(className, "override onDestroy");

		// since onDestroy is called when the device changes aspect ratio
		// (which is possible on tablets) this method cannot be used to force
		// a restart of the application when this method is called.
		// Having this logic here causes a restart loop when the device changes
		// aspect the ratio.
		// Log.e(className, "onDestroy: Prepare to restart the app.");
		// Intent intent = new Intent(this, biz.playr.MainActivity.class);
		//
		// intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP
		// | Intent.FLAG_ACTIVITY_CLEAR_TASK
		// | Intent.FLAG_ACTIVITY_NEW_TASK);
		//
		// PendingIntent pendingIntent = PendingIntent.getActivity(
		// biz.playr.MainApplication.getInstance().getBaseContext(), 0, intent, intent.getFlags());
		//
		// //Following code will restart your application after <delay> seconds
		// AlarmManager mgr = (AlarmManager) biz.playr.MainApplication.getInstance().getBaseContext().getSystemService(Context.ALARM_SERVICE);
		// mgr.set(AlarmManager.RTC, System.currentTimeMillis() +
		// DefaultExceptionHandler.restartDelay, pendingIntent);
		//

		// TODO: properly handling the delayed restart can only be done by letting the system handle less onDestroy events:
		// see: https://developer.android.com/guide/topics/resources/runtime-changes#java
		// this would mean:
		// 1) configure onDestroy not to be called when the screen is rotated
		// 2) calling restartDelayed() here
		// 3) handling the screen rotation in an overridden onConfigurationChanged implementation

		// ! restart is turned off for now since the restarts can trigger a recurring restart
		// ! the option to auto reboot a player when it is detected to not play should be
		// ! sufficient to keep players active
		 Log.i(className,"onDestroy: Delayed restart of the application!!!");
		 restartDelayed();

		Log.i(className, "onDestroy: stopMemoryChecking");
		this.stopMemoryChecking();
		// the onStop method should have unbound the service already, but just to be sure
		if (bound) {
			unBindServiceConnection();
		} else {
			Log.i(className, "onDestroy: connection is unbound");
		}
		Log.i(className, "onDestroy: destroyBrowserView");
		this.destroyBrowserView();
		super.onDestroy();
		Log.i(className, "onDestroy: end");
	}

	@SuppressLint("InlinedApi")
	protected void hideBars() {
		if (getWindow() != null) {
			View decorView = getWindow().getDecorView();
			// Hide both the navigation bar and the status bar.
			// SYSTEM_UI_FLAG_FULLSCREEN is available from Android 4.1 (API 16) and higher, but as
			// a general rule, you should design your app to hide the status bar whenever you
			// hide the navigation bar.
			// SYSTEM_UI_FLAG_HIDE_NAVIGATION is available from API level 14
			// SYSTEM_UI_FLAG_IMMERSIVE_STICKY is available from API 18
			int uiOptions =   View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
							| View.SYSTEM_UI_FLAG_FULLSCREEN
							| View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
			// Enables regular immersive mode.
			// For "lean back" mode, remove SYSTEM_UI_FLAG_IMMERSIVE.
			// Or for "sticky immersive," replace it with SYSTEM_UI_FLAG_IMMERSIVE_STICKY
//					| View.SYSTEM_UI_FLAG_IMMERSIVE;
			decorView.setSystemUiVisibility(uiOptions);
		}
		// Remember that you should never show the action bar if the
		// status bar is hidden, so hide that too if necessary.
		ActionBar actionBar = getActionBar();
		if (actionBar != null) {
			actionBar.hide();
		}
	}

	@Override
	/* Navigate the WebView's history when the user presses the Back key. */
	public void onBackPressed() {
		if (webView != null) {
			if (webView.canGoBack()) {
				webView.goBack();
			} else {
				super.onBackPressed();
			}
		} else {
			super.onBackPressed();
		}
	}

	/*
	 * PRIVATE methods
	 */
	private void reportSystemInformation() {
		Log.e(className, "***************************************");
		Log.e(className, "***          API level: " + Build.VERSION.SDK_INT + "          ***");
		Log.e(className, "***************************************");
	}

	private void unBindServiceConnection() {
		if (checkRestartService != null) {
			checkRestartService.setCallbacks(null);
			Log.i(className, "unBindServiceConnection: callbacks set to null on restart service");
		}
		if (bound) {
			if (this.twaServiceConnection != null) {
				unbindService(this.twaServiceConnection);
				bound = false;
				Log.i(className, "unBindServiceConnection: TWA service connection was unbound");
			} else if (webView != null) {
				unbindService(this.serviceConnection);
				bound = false;
				Log.i(className, "unBindServiceConnection: service connection (webView fall back) was unbound");
			}
		}
	}

	private void openBrowserView(boolean initialiseWebContent, boolean twaWasLaunched, String playerId) {
		setContentView(R.layout.activity_main);
		// create Trusted Web Access or fall back to a WebView
		String chromePackage = CustomTabsClient.getPackageName(this, TrustedWebUtils.SUPPORTED_CHROME_PACKAGES, true);
		// fall back to WebView since TWA is currently (Chrome 83) not working well enough to replace TWA
		// * URL bar stays visible
		// * button bar stays visible
		// * video's with sound do not play even though they do in Chrome (when playing the same channel)
		if (false) {
//		if (chromePackage != null) {
			Log.i(className, "openBrowserView chromePackage is not null");
			if (!chromeVersionChecked) {
				Log.i(className, "openBrowserView !chromeVersionChecked");
				TrustedWebUtils.promptForChromeUpdateIfNeeded(this, chromePackage);
				chromeVersionChecked = true;
			}

			twaServiceConnection = openTWAView(twaWasLaunched, chromePackage);
		} else {
			// fall back to WebView
			Log.i(className, "openBrowserView chromePackage is null; fall back to WebView");
			webView = openWebView(initialiseWebContent, playerId);
		}
	}

	private TwaCustomTabsServiceConnection openTWAView(boolean twaWasLaunched, String chromePackage) {
		TwaCustomTabsServiceConnection result = null;

		if (twaWasLaunched) {
			Log.i(className, "openTWAView TWA was launched => finish");
			this.finish();
		} else {
			Log.i(className, "openTWAView launching TWA");
			result = new MainActivity.TwaCustomTabsServiceConnection();
//				TwaCustomTabsServiceConnection twaServiceConnection = new TwaCustomTabsServiceConnection();
			CustomTabsClient.bindCustomTabsService(this, chromePackage, result);
		}
		return result;
	}

	private void startMemoryCheckingAtInterval(long interval) {
		// see; https://developer.android.com/reference/android/os/Handler.html
		//      https://developer.android.com/reference/java/lang/Runnable.html
		//      https://developer.android.com/reference/android/os/Looper.html
		memoryCheckHandler = new Handler();
		continueMemoryCheck = true;

		memoryCheckRunner = () -> {
			freeMemoryWhenNeeded(analyseMemoryStatus());
			if (continueMemoryCheck) {
				// run again
				memoryCheckHandler.postDelayed(memoryCheckRunner, interval);
			}
		 };
		// initial run
		memoryCheckHandler.postDelayed(memoryCheckRunner, interval);
	}

	private void stopMemoryChecking() {
		if (memoryCheckHandler != null) {
			// TODO do we have to stop the callbacks, is the use of continueMemoryCheck enough?
			continueMemoryCheck = false;
			Log.i(className, "stopMemoryChecking remove Callbacks from memoryCheckHandler");
			memoryCheckHandler.removeCallbacks(memoryCheckRunner);
			Log.i(className, "stopMemoryChecking set memoryCheckHandler to null");
			memoryCheckHandler= null;
		}
	}

	private WebView openWebView(boolean initialiseWebContent, String playerId) {
		WebView result = (WebView) findViewById(R.id.mainUiView);
		Log.i(className, "openWebView; webView is " +
						(result == null ? "null" : "not null") + ", initialiseWebContent: " +
						(initialiseWebContent ? "true" : "false") +
						", playerId: " + playerId);
		setupWebView(result);
		result.setWebChromeClient(createWebChromeClient());
		result.setWebViewClient(createWebViewClient());
		result.setKeepScreenOn(true);
		// Make sure necessary cookies are accepted
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
			CookieManager cookieManager = CookieManager.getInstance();
			cookieManager.setAcceptCookie(true);
			cookieManager.setAcceptThirdPartyCookies(result, true);
			if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q) {
				CookieManager.setAcceptFileSchemeCookies(true);
			}
		}

		// Callbacks for service binding, passed to bindService()
		serviceConnection = new ServiceConnection() {
			private static final String className = "biz.playr.ServiceConnec";

			@Override
			public void onServiceConnected(ComponentName componentName, IBinder service) {
//			public void onServiceConnected(ComponentName componentName, android.os.BinderProxy service) {
				Log.i(className, "override onServiceConnected");
				// cast the IBinder and get CheckRestartService instance
				// service is an android.os.BinderProxy
				CheckRestartService.LocalBinder binder = (CheckRestartService.LocalBinder) service;
				checkRestartService = binder.getService();
//				service.isBinderAlive();
				checkRestartService.setCallbacks(MainActivity.this); // bind IServiceCallbacks
				bound = true;
				Log.i(className, "onServiceConnected: service bound");
			}

			@Override
			public void onServiceDisconnected(ComponentName componentName) {
				Log.i(className, "override onServiceDisconnected");
				checkRestartService.setCallbacks(null);
				bound = false;
			}
		};

////		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
//			Bundle persistedWebViewContent = retrieveBundle();
//			Log.i(className, "openWebView; persistedWebViewContent: " + (persistedWebViewContent == null ? "null" : "not null") + ", persistedWebViewContent: " + (persistedWebViewContent != null && !persistedWebViewContent.isEmpty() ? "not empty" : "empty"));
//			if (initialiseWebContent || persistedWebViewContent == null || persistedWebViewContent.isEmpty()) {
//				Log.i(className, "openWebView; initialising WebView");
////				result.loadUrl("http://playr.biz/1160/84");
//				result.loadDataWithBaseURL("file:///android_asset/",
//											initialHtmlPage(playerId, result.getSettings().getUserAgentString()),
//								  "text/html", "UTF-8", null);
//			} else {
//				Log.i(className, "openWebView; restoring WebView from saved state");
//				result.restoreState(persistedWebViewContent);
//			}
////		} else {
////			Log.i(className, "openWebView; API level < lollipop, initialising WebView");
//////			result.loadUrl("http://playr.biz/1160/84");
////			result.loadUrl("https://nu.nl");
////		}
////		persistWebContent();

		Log.i(className, "openWebView; initialising WebView, isNetworkAvailable: " + isNetworkAvailable() + ", isServerAvailable: " + isServerAvailable() + ", isScreenshotAvailable: " + isScreenshotAvailable() + ", isScreenshotRecent: " + isScreenshotRecent());
		result.loadDataWithBaseURL("file:///android_asset/",
									initialHtmlPage(playerId, result.getSettings().getUserAgentString(), !isNetworkAvailable() && isScreenshotAvailable() && isScreenshotRecent()),
						  "text/html", "UTF-8", null);
		return result;
	}
	private String webArchiveFilePath() {
		return getFilesDir() + "/" + webArchiveFileName;
	}
	private boolean isNetworkAvailable() {
		ConnectivityManager connectivityManager = (ConnectivityManager) this.getSystemService(Context.CONNECTIVITY_SERVICE);

		// API level 29 or higher
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
			NetworkCapabilities capabilities = connectivityManager.getNetworkCapabilities(connectivityManager.getActiveNetwork());
			if (capabilities == null) return false;
			if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return true;
			if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) return true;
			if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) return true;
		}
		// API level below 29
		else {
			NetworkInfo activeNetworkInfo = connectivityManager.getActiveNetworkInfo();
			return activeNetworkInfo != null && activeNetworkInfo.isConnectedOrConnecting();
		}
		return false;
	}
	private boolean isServerAvailable() {
		return true;
//		HttpURLConnection urlConnection = null;
//		String response = null;
//
//		try {
//			URL url = new URL(Uri.parse("https://www.playr.biz/playr_loader_test_image.png").buildUpon()
//								.appendQueryParameter("ts", String.valueOf(System.currentTimeMillis())).build().toString());
//			Log.i(className, ".isServerAvailable; url: " + url);
//			urlConnection = (HttpURLConnection) url.openConnection();
//			InputStream inputStream = new BufferedInputStream(urlConnection.getInputStream());
//			response = readStream(inputStream).trim();
//		} catch (MalformedURLException e) {
//			Log.e(className, ".isServerAvailable; malformed URL exception opening connection; " + e.getMessage());
//		} catch (IOException e) {
//			Log.e(className, ".isServerAvailable; IO exception opening connection; " + e.getMessage());
//		} finally {
//			if (urlConnection != null) {
//				urlConnection.disconnect();
//			}
//		}
//		return (response != null && response.length() > 0);
	}
//	private String readStream(InputStream inputStream) {
//		try {
//			BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream));
//			StringBuilder total = new StringBuilder(inputStream.available());
//			String line;
//			while ((line = reader.readLine()) != null) {
//				total.append(line).append('\n');
//			}
//
//			return total.toString();
//		} catch (IOException e) {
//			Log.e(className, ".readStream: IO exception reading inputStream; " + e.getMessage());
//		}
//		return "";
//	}

	private boolean isScreenshotAvailable() {
		File file = new File(screenShotFilePath());
		Log.i(className, ".isScreenshotAvailable; file: " + (file == null ? "null" : "not null") + ", file.exists: " + file.exists());
		return file.exists();
	}
	private Date lastModifiedDateScreenshotFile() {
		if (isScreenshotAvailable()) {
			File file = new File(screenShotFilePath());
			return new Date(file.lastModified());
		} else {
			return null;
		}
	}
	private boolean isScreenshotRecent(){
		Calendar twoHoursAgo = Calendar.getInstance();
		twoHoursAgo.add(Calendar.HOUR, -2);
		Date twoHoursAgoDate = twoHoursAgo.getTime();

		Calendar lastModified = Calendar.getInstance();
		Date lastModifiedFileDate = lastModifiedDateScreenshotFile();
		if (lastModifiedFileDate == null) { return false; }
		lastModified.setTime(lastModifiedFileDate);
		Date lastModifiedDate = lastModified.getTime();

		return twoHoursAgoDate.before(lastModifiedDate);
	}
	private Calendar twoHoursBefore(Calendar dateTime) {
//		dateTime.add();
//		Calendar twoHoursAgo = Calendar.getInstance();
//		if (dateTime.get(Calendar.HOUR_OF_DAY)) > 2
//		twoHoursAgo.set(dateTime.get(Calendar.YEAR),
//						dateTime.get(Calendar.MONTH),
//						dateTime.get(Calendar.DATE),
//						(dateTime.get(Calendar.HOUR_OF_DAY) - 2),
//						dateTime.get(Calendar.MINUTE),
//						dateTime.get(Calendar.SECOND));
		return Calendar.getInstance();
	}


//	following two methods were found here: https://stackoverflow.com/a/5651242
//	private void takeScreenshot() {
//		Date now = new Date();
//		android.text.format.DateFormat.format("yyyy-MM-dd_hh:mm:ss", now);
//
//		try {
//			// image naming and path  to include sd card  appending name you choose for file
//			String mPath = Environment.getExternalStorageDirectory().toString() + "/" + now + ".jpg";
//
//			// create bitmap screen capture
//			View v1 = getWindow().getDecorView().getRootView();
//			v1.setDrawingCacheEnabled(true);
//			Bitmap bitmap = Bitmap.createBitmap(v1.getDrawingCache());
//			v1.setDrawingCacheEnabled(false);
//
//			File imageFile = new File(mPath);
//
//			FileOutputStream outputStream = new FileOutputStream(imageFile);
//			int quality = 100;
//			bitmap.compress(Bitmap.CompressFormat.JPEG, quality, outputStream);
//			outputStream.flush();
//			outputStream.close();
//
//			openScreenshot(imageFile);
//		} catch (Throwable e) {
//			// Several error may come out with file handling or DOM
//			e.printStackTrace();
//		}
//	}
//	private void openScreenshot(File imageFile) {
//		Intent intent = new Intent();
//		intent.setAction(Intent.ACTION_VIEW);
//		Uri uri = Uri.fromFile(imageFile);
//		intent.setDataAndType(uri, "image/*");
//		startActivity(intent);
//	}
// following code for converting a bitmap to a byte array (which can be persisted
// using a FileOutputStream) was found here: https://stackoverflow.com/a/34165515
// *** Bitmap to Byte-array
// int size = bitmap.getRowBytes() * bitmap.getHeight();
// ByteBuffer byteBuffer = ByteBuffer.allocate(size);
// bitmap.copyPixelsToBuffer(byteBuffer);
// byte[] byteArray = byteBuffer.array();
//
//// Somehow save (and later load) these as well:
// int width = bitmap.getWidth();
// int height = bitmap.getHeight();
// String format = bitmap.getConfig().name();
// *** Byte-array to Bitmap
// Bitmap.Config configBmp = Bitmap.Config.valueOf(format);
// Bitmap bitmap_tmp = Bitmap.createBitmap(width, height, configBmp);
// ByteBuffer buffer = ByteBuffer.wrap(byteArray);
// bitmap_tmp.copyPixelsFromBuffer(buffer);

//	private void saveScreenshot() {
	public void saveScreenshot() {
//		FileOutputStream fileOutputStream;
//		try {
			// create bitmap screen capture
			View view = getWindow().getDecorView().getRootView();

			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
				// This method of screenshot creation will also show video content
				Bitmap.Config config = Bitmap.Config.ARGB_8888;
				screenshotAsBitmap = Bitmap.createBitmap(view.getWidth(), view.getHeight(), config);
				if (pixelCopyHandler == null) {pixelCopyHandler = new Handler(Looper.getMainLooper()); }
				Log.e(className, ".saveScreenshot; doing PixelCopy.request...");
				PixelCopy.request(getWindow(), screenshotAsBitmap, new PixelCopyFinishedProcessor(), pixelCopyHandler);
			} else {
				// This method of screenshot creation will work on older versions of Android
				// but it will NOT capture video content
				try {
					view.setDrawingCacheEnabled(true);
					screenshotAsBitmap = Bitmap.createBitmap(view.getDrawingCache());
					view.setDrawingCacheEnabled(false);
					FileOutputStream fileOutputStream = openFileOutput(screenShotFileName, MODE_PRIVATE);
					// alternatively: bitmap.compress(Bitmap.CompressFormat.PNG, 100, fileOutputStream);
					screenshotAsBitmap.compress(Bitmap.CompressFormat.JPEG, jpegQuality, fileOutputStream);
					fileOutputStream.flush();
					fileOutputStream.close();
				} catch (Throwable ex) {
					Log.e(className, "saveScreenshot; Error during creating a screenshot file: " + ex);
					//		ex.printStackTrace();
				}
			}

//			Bitmap newBitmap = ThumbnailUtils.extractThumbnail(bitmap, bitmap.getWidth(), bitmap.getHeight());

//			File imageFile = new File(screenShotFilePath());
//			FileOutputStream fileOutputStream = new FileOutputStream(imageFile);
			// remove existing screenshot
//			File file = new File(screenShotFileName);
//			if (file.exists()) {
//				if (file.delete()) {
//					Log.i(className, "saveScreenshot; delete Screenshot file was successful");
//				} else {
//					Log.e(className, "saveScreenshot; delete Screenshot file was NOT successful");
//				}
//			} else {
//				Log.w(className, "saveScreenshot; Screenshot file was not found");
//			}
//			boolean success = deleteFile(screenShotFileName);
//			Log.w(className, "saveScreenshot; Screenshot file was " + (success ? "deleted" : "NOT deleted"));

//			fileOutputStream = openFileOutput(screenShotFileName, MODE_PRIVATE);
//			// alternatively: bitmap.compress(Bitmap.CompressFormat.PNG, 100, fileOutputStream);
//			screenshotAsBitmap.compress(Bitmap.CompressFormat.JPEG, jpegQuality, fileOutputStream);
//			fileOutputStream.flush();
//			fileOutputStream.close();
// show the bitmap...
//			Canvas canvas = new Canvas(bitmap);
//		} catch (Throwable ex) {
//			Log.e(className, "saveScreenshot; Error during creating a screenshot file: " + ex);
//	//		ex.printStackTrace();
//		}
	}

	@RequiresApi(api = Build.VERSION_CODES.N)
	private class PixelCopyFinishedProcessor implements PixelCopy.OnPixelCopyFinishedListener {
		private static final String className = "biz.playr.PixelCopyFini";

		// copyResult Contains the resulting status of the copy request. This will either be
		// PixelCopy#SUCCESS or one of the PixelCopy.ERROR_* values.
		// Value is PixelCopy.SUCCESS, PixelCopy.ERROR_UNKNOWN, PixelCopy.ERROR_TIMEOUT,
		// PixelCopy.ERROR_SOURCE_NO_DATA, PixelCopy.ERROR_SOURCE_INVALID, or
		// PixelCopy.ERROR_DESTINATION_INVALID
		public void	onPixelCopyFinished(int copyResult) {
			Log.i(className, ".onPixelCopyFinished start");
			if (copyResult == PixelCopy.SUCCESS) {
				try {
					FileOutputStream fileOutputStream = openFileOutput(screenShotFileName, MODE_PRIVATE);
					// alternatively: bitmap.compress(Bitmap.CompressFormat.PNG, 100, fileOutputStream);
					screenshotAsBitmap.compress(Bitmap.CompressFormat.JPEG, jpegQuality, fileOutputStream);
					fileOutputStream.flush();
					fileOutputStream.close();
				} catch (Exception ex) {
					Log.e(className, ".onPixelCopyFinished; Error in file io: " + ex);
				}
			} else {
				Log.e(className, ".onPixelCopyFinished: PixelCopy returned the error: " + copyResult);
			}
			Log.i(className, ".onPixelCopyFinished done");
		}
	}
//	private void openScreenshot() {
	public void openScreenshot() {
		Intent intent = new Intent();
		intent.setAction(Intent.ACTION_VIEW);
		intent.setDataAndType(Uri.parse("file://" + screenShotFilePath()), "image/*");
//		intent.setDataAndType(Uri.parse("content://" + screenShotFileName), "image/*");
		startActivity(intent);
	}
	private String screenShotFilePath() {
//		return getFilesDir() + "/" + screenShotFileName;
		return getFileStreamPath(screenShotFileName).getAbsolutePath();
	}
	private void requestManageOverlayPermission(Context context) {
		// currently (players using Android 11 and 12 in production) it
		// seems not necessary to request the overlay permission
		// in order to have the auto-start work based on "catching" the
		// 'boot completed' action in the BootUpReceiver
		if (false) {
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !isAndroidTV()) {
				if (!Settings.canDrawOverlays(context)) {
					Log.i(className, "requestManageOverlayPermission: requesting overlay permission");
					Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
							Uri.parse("package:" + getPackageName()));
					startActivityForResult(intent, REQUEST_OVERLAY_PERMISSION);
				}
//      	    // old way
//				if (!Settings.canDrawOverlays(getApplicationContext())) {
//					startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION));
//				}
			}
		}
	}

	@RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
	private boolean isAndroidTV() {
		UiModeManager uiModeManager = (UiModeManager) getSystemService(UI_MODE_SERVICE);
		return uiModeManager.getCurrentModeType() == Configuration.UI_MODE_TYPE_TELEVISION || getPackageManager().hasSystemFeature(PackageManager.FEATURE_LEANBACK);
	}

	private String retrieveOrGeneratePlayerId() {
		String result = getStoredPlayerId();
		if (result == null || result.length() == 0) {
			result = UUID.randomUUID().toString();
			storePlayerId(result);
			Log.i(className, "generated and stored playerId: " + result);
		} else {
			Log.i(className, "retrieved stored playerId: " + result);
		}
		return result;
	};

	private WebChromeClient createWebChromeClient() {
		return new WebChromeClient() {
			private String className = "biz.playr.WebChromeClie";
			// private int count = 0;

			@Override
			public void onShowCustomView(View view, CustomViewCallback callback) {
				Log.i(className, "override onShowCustomView");
				super.onShowCustomView(view, callback);
			}

			@Override
			public boolean onConsoleMessage(ConsoleMessage consoleMessage) {
//				Log.i(className, "override onConsoleMessage");
				// Log.i(className,"override onConsoleMessage: " +
				// consoleMessage.message());
				// count++;
				// if (count == 10) {
				// Log.i(className, ">>>>>> override onConsoleMessage, throw Exception");
				// // throw new IllegalArgumentException("Test exception");
				// } else {
				// Log.i(className, ">>>>>> override onConsoleMessage, count = " + count);
				// }
				return super.onConsoleMessage(consoleMessage);
			}
		};
	};

	private WebViewClient createWebViewClient() {
		return new WebViewClient() {
			private static final String className = "biz.playr.WebViewClient";

			public boolean shouldOverrideUrlLoading(WebView view, String url) {
				Log.i(className, "shouldOverrideUrlLoading");
				// Return false from the callback instead of calling view.loadUrl.
				// Calling loadUrl introduces a subtle bug where if you
				// have any iframe within the page with a custom scheme URL
				// (say <iframe src="tel:123"/>) it will navigate your app's
				// main frame to that URL most likely breaking the app as a side effect.
				// http://stackoverflow.com/questions/4066438/android-webview-how-to-handle-redirects-in-app-instead-of-opening-a-browser
				return false; // then it is not handled by default action
			}

			/*
			 * this version of this method is deprecated from API version 23
			 */
			@Override
			public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
				// Log.i(className, "override onReceivedError");
				// Toast.makeText(getActivity(), "WebView Error" + description(), Toast.LENGTH_SHORT).show();
				Log.e(className, "onReceivedError: WebView(Client) error - " + description + " code; " + String.valueOf(errorCode) + " URL; " + failingUrl);
				if ("net::".equals(description.subSequence(0,5))) {
					// ignore network errors
				} else {
					Log.e(className, "===>>> !!! WebViewClient.onReceivedError Reloading Webview !!! <<<===");
					// super.onReceivedError(view, request, error);
					view.reload();
				}
			}

			/*
			 * Added in API level 23 (use these when we set android:targetSdkVersion to 23)
			 */
			@Override
			public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
				// Log.i(className, "override onReceivedError");
				if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
					// Toast.makeText(getActivity(), "WebView Error" + error.getDescription(), Toast.LENGTH_SHORT).show();
					Log.e(className, "onReceivedError: WebView error - " + error.getDescription()
							+ " code; " + String.valueOf(error.getErrorCode()) + " URL; " + request.getUrl().toString());
				}
				if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
					if ("net::".equals(error.getDescription().subSequence(0,5))) {
						// ignore network errors
					} else {
						Log.e(className, "===>>> !!! WebViewClient.onReceivedError Reloading Webview !!! <<<===");
						// super.onReceivedError(view, request, error);
						view.reload();
					}
				}
			}

			@Override
			public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse errorResponse) {
				Log.i(className, "override onReceivedHttpError");
				if (android.os.Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
					// Toast.makeText(getActivity(), "WebView Error" + errorResponse.getReasonPhrase(), Toast.LENGTH_SHORT).show();
					Log.e(className, "onReceivedHttpError WebView http error: " + errorResponse.getReasonPhrase()
							+ " URL: " + request.getUrl().toString());
				}
				super.onReceivedHttpError(view, request, errorResponse);
			}
		};
	};

	private String initialHtmlPage(String playerId, String webviewUserAgent, boolean includeScreenshot) {
// PRODUCTION
		return "<html><head><script type=\"text/javascript\" charset=\"utf-8\">window.location = \""
				+ pageUrl(playerId, webviewUserAgent, includeScreenshot) + "\"</script><head><body/></html>";
// TEST, light channel
//		return "<html><head><script type=\"text/javascript\" charset=\"utf-8\">window.location=\"http://playr.biz/1160/79410\" </script><head><body/></html>";
// TEST, heavy channel
//		return "<html><head><script type=\"text/javascript\" charset=\"utf-8\">window.location=\"http://playr.biz/1160/84\" </script><head><body/></html>";
	};

	// We use the ActivityManager.MemoryInfo.threshold: The threshold of availMem at which we consider
	// memory to be low and start killing background services and other non-extraneous processes.
	// MemoryStatus.OK       if available memory >  125% of threshold
	// MemoryStatus.MEDIUM   if available memory <= 125% and > 115% of threshold
	// MemoryStatus.LOW      if available memory <= 115% and > 105% of threshold
	// memoryStatus.CRITICAL if available memory <= 105%of threshold
	private MemoryStatus analyseMemoryStatus() {
		MemoryStatus result = MemoryStatus.OK;

		// Find out how much memory is available; availMem, totalMem, threshold and lowMemory are available as values
		ActivityManager.MemoryInfo memoryInfo = getAvailableMemory();
		Runtime runtime = Runtime.getRuntime();
		long availableHeapSizeInMB = (runtime.maxMemory()/MB) - ((runtime.totalMemory() - runtime.freeMemory())/MB);
		result = calculateMemoryStatus(memoryInfo.availMem, memoryInfo.threshold);
		Log.e(className, ".\n***************************************************************************************\n" +
				"*** total memory: " + memoryInfo.totalMem/MB + " MB\n" +
				"*** available memory: " + memoryInfo.availMem/MB + " (" + this.firstMemoryInfo.availMem/MB + ", " + (memoryInfo.availMem - this.firstMemoryInfo.availMem)/MB + ") [MB]\n" +
				"*** used memory: " + (memoryInfo.totalMem - memoryInfo.availMem)/MB + " MB [" + (memoryInfo.totalMem - memoryInfo.availMem)*100/memoryInfo.totalMem + "%] (initially: " + (memoryInfo.totalMem - this.firstMemoryInfo.availMem)/MB + " MB [" + (memoryInfo.totalMem - this.firstMemoryInfo.availMem)*100/memoryInfo.totalMem + "%])\n" +
				"*** threshold: " + memoryInfo.threshold/MB + " MB\n" +
				"*** low memory?: " + memoryInfo.lowMemory + " (" + this.firstMemoryInfo.lowMemory + ")\n" +
				"*** available heap size: " + availableHeapSizeInMB + " (" + this.firstAvailableHeapSizeInMB + ", " + (availableHeapSizeInMB - this.firstAvailableHeapSizeInMB) + ") [MB]\n" +
				"***************************************************************************************\n" +
				"*** available memory: " + Math.round(100*memoryInfo.availMem/this.firstMemoryInfo.availMem) + "% of initial available and " + Math.round(100*memoryInfo.availMem/memoryInfo.threshold) + "% of threshold => result: " + result  + "\n" +
				"***************************************************************************************");
		return result;
	}

	private MemoryStatus calculateMemoryStatus(long availableMemory, long threshold) {
		MemoryStatus result = MemoryStatus.OK;
		if (availableMemory > 1.25*threshold) {
			result = MemoryStatus.OK;
		} else if (availableMemory <= 1.25*threshold && availableMemory > 1.15*threshold) {
			result = MemoryStatus.MEDIUM;
		} else if (availableMemory <= 1.15*threshold && availableMemory > 1.05*threshold) {
			result = MemoryStatus.LOW;
		} else { // availableMemory <= 1.05*threshold
			result = MemoryStatus.CRITICAL;
		}
		return result;
	}

	private void freeMemoryWhenNeeded(MemoryStatus status) {
		switch (status) {
			case CRITICAL:
				// Release as much memory as the process can.
				// ==>> restart the activity
				Log.i(className, ".freeMemoryWhenNeeded; CRITICAL => restartActivity");
				this.restartActivity();
				break;
			case LOW:
				// Release any UI objects that currently hold memory.
				// ==>> dump browser view and recreate it
				Log.i(className, ".freeMemoryWhenNeeded; LOW => recreateBrowserView");
				this.recreateBrowserView();
			case MEDIUM:
				// Release any memory that your app doesn't need to run.
				// ==>> reload browser view
				Log.i(className, ".freeMemoryWhenNeeded; MEDIUM => reloadBrowserView");
				this.reloadBrowserView();
				break;
			case OK:
			default:
				// no action needs to be taken
				break;
		}
	}

	private String pageUrl(String playerId, String webviewUserAgent, boolean includeScreenshot) {
		String webviewVersion = "Android System WebView not installed";
		String appVersion = "app version not found";
		PackageManager pm = getPackageManager();
		PackageInfo pi;
		PackageInfo pi2;
		Log.i(className, "pageUrl; player_id: " + playerId + ", includeScreenshot: " + includeScreenshot);
		try {
			pi = pm.getPackageInfo("com.google.android.webview", 0);
			if (pi != null) {
				webviewVersion = "Version-name: " + pi.versionName
						+ " -code: " + pi.versionCode;
			}
			if (android.os.Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
//				pi2 = WebView.getCurrentWebViewPackage();
				pi2 = WebViewCompat.getCurrentWebViewPackage(MainActivity.this);
				if (pi2 != null && pi != null) {
					webviewVersion += " (Version-name: " + pi2.versionName
							+ " -code: " + pi2.versionCode + ")";
				} else if (pi2 != null && pi == null) {
					webviewVersion = "Version-name: " + pi2.versionName
							+ " -code: " + pi2.versionCode;
				}
			}
		} catch (PackageManager.NameNotFoundException e) {
			Log.e(className, "Android System WebView is not found");
		}
		try {
			pi = pm.getPackageInfo(getPackageName(), 0);
			if (pi != null) {
				appVersion = pi.versionName;
			}
		} catch (PackageManager.NameNotFoundException e) {
			Log.e(className, getPackageName() + " is not found");
		}
		// ignore preference of the OS for use of https
		if (includeScreenshot) {
			return Uri.parse("playr_loader.html").buildUpon()
					.appendQueryParameter("player_id", playerId)
					.appendQueryParameter("webview_user_agent", webviewUserAgent)
					.appendQueryParameter("webview_version", webviewVersion)
					.appendQueryParameter("https_required", httpsRequired())
					.appendQueryParameter("screenshot_path", ("file://" + screenShotFilePath()))
					.appendQueryParameter("app_version", appVersion).build()
					.toString();
		} else {
			return Uri.parse("playr_loader.html").buildUpon()
					.appendQueryParameter("player_id", playerId)
					.appendQueryParameter("webview_user_agent", webviewUserAgent)
					.appendQueryParameter("webview_version", webviewVersion)
					.appendQueryParameter("https_required", httpsRequired())
					.appendQueryParameter("app_version", appVersion).build()
					.toString();
		}
	};

	private class TwaCustomTabsServiceConnection extends CustomTabsServiceConnection {
		private static final String className = "biz.playr.TwaCusTbsSrvC";

		public void onCustomTabsServiceConnected(ComponentName componentName, CustomTabsClient client) {
			Log.i(className, " override onCustomTabsServiceConnected");

			CustomTabsSession session = MainActivity.this.getSession(client);
			CustomTabsIntent intent = MainActivity.this.getCustomTabsIntent(session);
			Uri url = Uri.parse("http://play.playr.biz"); // <<<===### TODO: use the url for the player_loader page

			Log.i(className, "Launching Trusted Web Activity." + url.toString());
			// build twa
			TrustedWebUtils.launchAsTrustedWebActivity(MainActivity.this, intent, url);
			MainActivity.this.twaWasLaunched = true;
		}

		public void onServiceDisconnected(ComponentName componentName) {
			Log.i(className, "override onServiceDisconnected");
		}
	}

	// Get a MemoryInfo object for the device's current memory status.
	private ActivityManager.MemoryInfo getAvailableMemory() {
		ActivityManager activityManager = (ActivityManager) this.getSystemService(ACTIVITY_SERVICE);
		ActivityManager.MemoryInfo memoryInfo = new ActivityManager.MemoryInfo();
		activityManager.getMemoryInfo(memoryInfo);
		return memoryInfo;
	}

	private String getStoredPlayerId() {
		SharedPreferences sharedPreferences = getPreferences(Context.MODE_PRIVATE);
		return sharedPreferences.getString(getString(R.string.player_id_store), "");
	}

	@SuppressLint("SetJavaScriptEnabled")
	/* Configure the Webview for usage as the application's window. */
	private void setupWebView(WebView webView) {
		Log.i(className, "setupWebView");
		WebSettings webSettings = webView.getSettings();
		webSettings.setJavaScriptEnabled(true);
		// available for android.os.Build.VERSION.SDK_INT > android.os.Build.VERSION_CODES.JELLY_BEAN
		webSettings.setMediaPlaybackRequiresUserGesture(false);
		webSettings.setJavaScriptCanOpenWindowsAutomatically(true);
		webSettings.setLoadWithOverviewMode(true);
		webSettings.setUseWideViewPort(true);
		webSettings.setAllowContentAccess(true);
		webSettings.setAllowFileAccess(true);
		webSettings.setAllowFileAccessFromFileURLs(true);
		webSettings.setAllowUniversalAccessFromFileURLs(true);
		webSettings.setLoadsImagesAutomatically(true);
		webSettings.setBlockNetworkImage(false);
		webSettings.setTextZoom(100);
		webSettings.setMediaPlaybackRequiresUserGesture(false);
		// TODO check if these font related settings are needed to ensure correct font rendering
		//webSettings.setDefaultFixedFontSize();
		//webSettings.setDefaultFontSize();
		//webSettings.setMinimumFontSize();
		//webSettings.setMinimumLogicalFontSize();
		// available for android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.JELLY_BEAN
		webSettings.setBuiltInZoomControls(false);
		webSettings.setSupportZoom(false);
		// available for android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.KITKAT
		// webSettings.setPluginState(PluginState.ON);
		// Caching of web content:
		// When navigating back, content is not revalidated, instead the content is just retrieved
		// from the cache. Disable the cache to fix this.
		// We do not have back navigation nor use content validation
		// * Default cache usage mode; LOAD_DEFAULT. If the navigation type doesn't impose any
		// specific behavior, use cached resources when they are available and not expired,
		// otherwise load resources from the network.
		// * Use cache when needed; LOAD_CACHE_ELSE_NETWORK. Use cached resources when they are
		// available, even if they have expired. Otherwise load resources from the network.
		// Aim is to allow playback start even if there is no internet connection
		webSettings.setCacheMode(WebSettings.LOAD_CACHE_ELSE_NETWORK);
		webSettings.setDomStorageEnabled(true);
		// deprecated
		// webSettings.setAppCachePath(getApplicationContext().getFilesDir().getAbsolutePath() + "/cache");
		webSettings.setDatabaseEnabled(true);
		// deprecated
		// webSettings.setDatabasePath(getApplicationContext().getFilesDir().getAbsolutePath() + "/databases");
		webView.resumeTimers();
		webView.setLayerType(View.LAYER_TYPE_HARDWARE, null);
		setTouchHandling(webView);
	}

	private void setTouchHandling(WebView webView) {
		// set long click handling to prevent text selection
		webView.setOnLongClickListener(new View.OnLongClickListener() {
			@Override
			public boolean onLongClick(View v) {
				return true;
			}
		});
		webView.setLongClickable(false);
		// set touch handling to enable touch events in the webview to allow dynamic content control
		webView.setOnTouchListener(new View.OnTouchListener() {
			public final static int FINGER_RELEASED = 0;
			public final static int FINGER_TOUCHED = 1;
			public final static int FINGER_DRAGGING = 2;
			public final static int FINGER_UNDEFINED = 3;

			private int fingerState = FINGER_RELEASED;

			@Override
			public boolean onTouch(View view, MotionEvent motionEvent) {
				Log.i(className, "override onTouch (webView onTouchListener)");
				switch (motionEvent.getAction()) {
					case MotionEvent.ACTION_DOWN:
						if (fingerState == FINGER_RELEASED) {
							fingerState = FINGER_TOUCHED;
							Log.i(className, "onTouch: fingerState == FINGER_RELEASED");
						} else {
							fingerState = FINGER_UNDEFINED;
							Log.i(className, "onTouch: fingerState != FINGER_RELEASED");
						}
						break;
					case MotionEvent.ACTION_UP:
						if(fingerState != FINGER_DRAGGING) {
							fingerState = FINGER_RELEASED;

							Log.i(className, "onTouch: fingerState != FINGER_DRAGGING, return true");
							// handle click/touch here if the webview does not handle it correctly
							//webView.performClick();
							return true;
						}
						else if (fingerState == FINGER_DRAGGING) {
							fingerState = FINGER_RELEASED;
							Log.i(className, "onTouch: fingerState == FINGER_DRAGGING");
						} else {
							fingerState = FINGER_UNDEFINED;
							Log.i(className, "onTouch: else; fingerState = FINGER_UNDEFINED");
						}
						break;
					case MotionEvent.ACTION_MOVE:
						if (fingerState == FINGER_TOUCHED || fingerState == FINGER_DRAGGING) {
							fingerState = FINGER_DRAGGING;
							Log.i(className, "onTouch: fingerState == FINGER_TOUCHED || fingerState == FINGER_DRAGGING");
						} else {
							fingerState = FINGER_UNDEFINED;
							Log.i(className, "onTouch: !(fingerState == FINGER_TOUCHED || fingerState == FINGER_DRAGGING)");
						}
						break;
					default:
						fingerState = FINGER_UNDEFINED;
						Log.i(className, "onTouch: default; fingerState = FINGER_UNDEFINED");
				}
				Log.i(className, "onTouch: return false");
				return false;
			}
		});
	}

	private void storePlayerId(String value) {
		SharedPreferences sharedPreferences = getPreferences(Context.MODE_PRIVATE);
		SharedPreferences.Editor editor = sharedPreferences.edit();
		editor.putString(getString(R.string.player_id_store), value);
		editor.commit();
	}

	private void recreateBrowserView() {
		Log.i(className, "recreateBrowserView");
		this.unBindServiceConnection();
		this.destroyBrowserView();
		this.openBrowserView(true, false, this.retrieveOrGeneratePlayerId());
	}

	private void reloadBrowserView() {
		Log.i(className, "reloadBrowserView");
		if (webView != null) {
			webView.reload();
		} else if (twaServiceConnection != null) {
			// TODO reload TWA
		}
	}

	private void destroyBrowserView() {
		Log.i(className, "destroyBrowserView");
		if (webView != null) {
			destroyWebView(webView);
			webView = null;
		} else if (twaServiceConnection != null) {
			destroyTWAView(twaServiceConnection);
		}
	}

	private void destroyWebView(WebView webView) {
		ViewGroup viewGroup;

		if (webView != null) {
			viewGroup = (ViewGroup) webView.getParent();
			if (viewGroup != null)
			{
				Log.i(className, "destroyWebView: remove view(s) from viewGroup");
				// viewGroup.removeView(webView);
				// to be sure remove all and not just the webView
				viewGroup.removeAllViews();

			}

			Log.i(className, "destroyWebView: prepare webView.destroy()");
			webView.clearHistory();

			// NOTE: clears RAM cache, if you pass true, it will also clear the disk cache.
			webView.clearCache(false);

			// Loading a blank page is optional, but will ensure that the WebView isn't doing anything when you destroy it.
			webView.loadUrl("about:blank");

			webView.onPause();
			webView.removeAllViews();
			webView.destroyDrawingCache();

			// NOTE: This pauses JavaScript execution for ALL WebViews,
			// do not use if you have other WebViews still alive.
			// If you create another WebView after calling this,
			// make sure to call mWebView.resumeTimers().
			webView.pauseTimers();

			// NOTE: This can occasionally cause a segfault below API 17 (4.2)
			Log.i(className, "destroyWebView: webView.destroy()");
			webView.destroy();
		} else {
			Log.i(className, "destroyWebView: webView is null!");
		}
	}

	private void destroyTWAView(TwaCustomTabsServiceConnection twaServiceConnection) {
		Log.i(className, "destroyTWAView");
		// TODO destroy the TWA view analog to destroyWebView
	}

	private void finishAndRemoveTaskCompat() {
		if (android.os.Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
			this.finishAndRemoveTask();
		} else {
			this.finish();
		}
	}

	private String httpsRequired() {
		if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.M) {
			return "no";
		} else {
			return NetworkSecurityPolicy.getInstance().isCleartextTrafficPermitted() ? "no" : "yes";
		}
	}
}
