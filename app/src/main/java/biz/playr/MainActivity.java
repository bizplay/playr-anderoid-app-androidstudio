package biz.playr;

import java.util.Date;
import java.util.HashMap;
import java.util.UUID;

import static android.view.WindowInsets.Type.navigationBars;
import static android.view.WindowInsets.Type.statusBars;
import static androidx.preference.PreferenceManager.getDefaultSharedPreferences;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.ActionBar;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.UiModeManager;
import android.content.ComponentCallbacks2;
import android.content.ComponentName;
import android.content.ServiceConnection;
import android.content.res.Configuration;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.security.NetworkSecurityPolicy;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.webkit.ConsoleMessage;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebResourceError;
import android.webkit.WebView;
import android.webkit.WebSettings;
import android.webkit.WebViewClient;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.browser.customtabs.CustomTabsCallback;
import androidx.browser.customtabs.CustomTabsClient;
import androidx.browser.customtabs.CustomTabsIntent;
import androidx.browser.customtabs.CustomTabsServiceConnection;
import androidx.browser.customtabs.CustomTabsSession;
import androidx.browser.customtabs.TrustedWebUtils;
import androidx.core.content.pm.PackageInfoCompat;
import androidx.webkit.WebViewAssetLoader;
import androidx.webkit.WebViewCompat;

public class MainActivity extends Activity implements IServiceCallbacks {
	private WebView webView = null;
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
	private static final long MB = 1048576L;
	private static final long memoryCheckInterval = 5*60*1000; // 5 minutes
	// TWA related
	private boolean chromeVersionChecked = false;
	private boolean twaWasLaunched = false;
	private Bundle currentSavedInstanceState;
	private static final int SESSION_ID = 96375;
	private static final String TWA_WAS_LAUNCHED_KEY = "android.support.customtabs.trusted.TWA_WAS_LAUNCHED_KEY";
	private static final int REQUEST_OVERLAY_PERMISSION = 1;
	// Because WebSetting allowFileAccessFromFileURLs is Deprecated
	// https://stackoverflow.com/a/63707709
	// and https://developer.android.com/reference/kotlin/androidx/webkit/WebViewAssetLoader
	private final WebViewAssetLoader assetLoader = new WebViewAssetLoader.Builder()
			.addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
			.addPathHandler("/res/", new WebViewAssetLoader.ResourcesPathHandler(this))
			.build();

	@Nullable
	private MainActivity.TwaCustomTabsServiceConnection twaServiceConnection;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		Log.i(className, "override onCreate");
		currentSavedInstanceState = savedInstanceState;
		super.onCreate(savedInstanceState);

		storeActivityCreatedAt(); // Store activity status for possible use in the BootupReceiver
		reportSystemInformation();
		// Setup restarting of the app when it crashes
		Log.i(className, "onCreate: setDefaultUncaughtExceptionHandler");
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
        // On TV devices, use FLAG_KEEP_SCREEN_ON to prevent the device from going into
        // Ambient Mode during active video playback. If the foreground activity does not
        // set FLAG_KEEP_SCREEN_ON, the device automatically enters Ambient Mode after a
        // period of inactivity.
        // Also important for other types of Android versions
		getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
		if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q) {
			getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
		}
//		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
//			getWindow().addFlags(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);
//		}
		requestWindowFeature(Window.FEATURE_NO_TITLE);
		View decorView = getWindow().getDecorView();
		if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q) {
			decorView.setOnSystemUiVisibilityChangeListener(this::onSystemUiVisibilityChange);
		} else {
			// TODO implement hiding status and navigation bars using WindowInsets
			// see https://developer.android.com/reference/android/view/View.OnSystemUiVisibilityChangeListener
			//     https://developer.android.com/reference/android/view/WindowInsets#isVisible(int)
			//     https://developer.android.com/reference/android/view/View.OnApplyWindowInsetsListener
		}
		decorView.setKeepScreenOn(true);

		// request overlay permission needed for 'auto start' ability
		requestManageOverlayPermission(getApplicationContext());

		// create Trusted Web Access or fall back to a WebView
		openBrowserView((savedInstanceState == null),
						(savedInstanceState != null && savedInstanceState.getBoolean(MainActivity.TWA_WAS_LAUNCHED_KEY)),
						retrieveOrGeneratePlayerId());
		// API 33+ way to handle back button
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
			getOnBackInvokedDispatcher().registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, backPressedHandler());
		}

		Log.i(className, "onCreate end");
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
			openBrowserView((currentSavedInstanceState == null),
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
		Log.e(className, ".handleUncaughtException; paramThread: " + paramThread
				+ ", paramThrowable: " + paramThrowable + " => calling recreate()");
		recreate();
	}

	// 'trick' to have a default value of false for force parameter
	public void restartDelayed() { restartDelayed(false); }
	// force=true can be used to make sure the restart is performed on all devices
	// force=false makes restart device dependent; not performed on devices where
	// it may lead to restart-loops
	public void restartDelayed(boolean force) {
		Log.i(className, ".restartDelayed, force: " + force);

		if (getResources().getBoolean(R.bool.restart) || force) {
			// the context of the activityIntent might need to be the running PlayrService
			// keep the Intent in sync with the Manifest and DefaultExceptionHandler
//			PendingIntent localPendingIntent = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_ONE_SHOT);
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
				Log.i(className, ".restartDelayed: setting alarm manager to restart with a delay of " +  DefaultExceptionHandler.restartDelay/1000 + " seconds");
				if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
					// possibly better alternative, see also using Handler for this
					try {
						mgr.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + DefaultExceptionHandler.restartDelay, localPendingIntent);
						Log.i(className, ".restartDelayed: called setAndAllowWhileIdle");
					} catch (SecurityException ex) {
						Log.e(className, ".restartDelayed: setAndAllowWhileIdle caused security exception: " + ex);
					}
				} else {
					try {
						// as of Android 14 (API 34) calling setExact is no longer permitted
						mgr.set(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + DefaultExceptionHandler.restartDelay, localPendingIntent);
						Log.i(className, ".restartDelayed: called set");
					} catch (SecurityException ex) {
						Log.e(className, ".restartDelayed: set caused security exception: " + ex);
						throw ex;
					}
				}
			}
		} else {
			// Pro Display usage; no restart of the MainActivity
			Log.i(className, ".restartDelayed MainActivity NOT restarted");
		}
		Log.i(className, ".restartDelayed: end");
	}

	// implement the ComponentCallbacks2 interface
	/**
	 * Release memory when the UI becomes hidden or when system resources become low.
	 * @param level the memory-related event that was raised.
	 */
	// original onTrimMemory(int level) functionality lead to crashes
	// it turns out that on some players this method is called frequently (less than 0.1 seconds apart)
	// with very severe levels causing the logic below to restart this activity unnecessarily
	// leading to the more conservative functionality calling freeMemoryWhenNeeded(memoryStatus)
	@Override
	public void onTrimMemory(int level) {
		Log.i(className, "override onTrimMemory");
		super.onTrimMemory(level);
		MemoryStatus memoryStatus = analyseMemoryStatus();
		Log.e(className, ".\n***************************************************************************************\n*** onTrimMemory - level: " + level + "\n*** memory status: " + memoryStatus + "\n***************************************************************************************\n.");

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
				// this.restartActivity();
				freeMemoryWhenNeeded(memoryStatus);
				break;
			case ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW:
				// ==>> dump browser view and recreate it
				// this.recreateBrowserView();
				freeMemoryWhenNeeded(memoryStatus);
				break;
			case ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE:
				// ==>> reload browser view
				// this.reloadBrowserView();
				freeMemoryWhenNeeded(memoryStatus);
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
		restartActivityWithDelay(false);
	}
	public void restartActivityWithDelay(boolean force) {
		this.restartActivity(force);
	}

	public String getPlayerId() {
		return getStoredPlayerId();
	}
	// end of implementation IServiceCallbacks

	// 'trick' to have a default value of false for force parameter
	public void restartActivity() { restartActivity(false); }
	public void restartActivity(boolean force) {
		Log.i(className, "restartActivity: setting up delayed restart");
		restartDelayed(force);
		Log.i(className, "restartActivity: killing this process");
		setResult(RESULT_OK);
		Log.i(className, "restartActivity: calling finish()");
		finish();
//		Log.i(className, "restartActivity: calling killProcess()");
//		android.os.Process.killProcess(android.os.Process.myPid());
//		Log.i(className, "restartActivity: calling exit(2)");
//		System.exit(2);
	}

	@Override
	protected void onSaveInstanceState(Bundle outState) {
		Log.i(className, "override onSaveInstanceState");
		super.onSaveInstanceState(outState);
		if (webView != null) { webView.saveState(outState); }
		outState.putBoolean(MainActivity.TWA_WAS_LAUNCHED_KEY, this.twaWasLaunched);
	}

	@Override
	protected void onRestoreInstanceState(Bundle savedInstanceState) {
		Log.i(className, "override onRestoreInstanceState");
		super.onRestoreInstanceState(savedInstanceState);
		if (savedInstanceState != null && !savedInstanceState.isEmpty()) {
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
			bindService(intent, (ServiceConnection) this.twaServiceConnection, Context.BIND_AUTO_CREATE | Context.BIND_ALLOW_ACTIVITY_STARTS);
			// checkRestartService = TODO how do we point this attribute to the service instance
			Log.i(className, "onStart: restart service is bound to twaServiceConnection (TWA is used) [BIND_AUTO_CREATE]");
			bound = true;
		} else if (this.webView != null) {
			if (this.checkRestartService == null) {
				Log.e(className, "onStart: webView is defined but checkRestartService is null.");
			}
			Intent intent = new Intent(this, CheckRestartService.class);
//			intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
			bindService(intent, this.serviceConnection, Context.BIND_AUTO_CREATE | Context.BIND_ALLOW_ACTIVITY_STARTS);
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
		Log.i(className,"onDestroy: possibly delayed restart of the application!");
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
		clearActivityCreatedAt();
		Log.i(className, "onDestroy: end");
	}

	@SuppressLint("InlinedApi")
	protected void hideBars() {
		if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q) {
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
				//				| View.SYSTEM_UI_FLAG_IMMERSIVE;
				decorView.setSystemUiVisibility(uiOptions);
			}
		} else {
			if (getWindow() != null) {
				WindowInsetsController windowInsetsController = getWindow().getInsetsController();
				windowInsetsController.hide(navigationBars());
				windowInsetsController.hide(statusBars());
				windowInsetsController.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
			}
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
		handleBackPressed();
		super.onBackPressed();
	}
	/*
	 * PRIVATE methods
	 */
	private OnBackInvokedCallback backPressedHandler() {
		if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
			return new OnBackInvokedCallback() {
				@Override
				public void onBackInvoked() {
					handleBackPressed();
				}
			};
		} else {
			return null;
		}
	}
	private void handleBackPressed() {
		if (webView != null) {
			if (webView.canGoBack()) {
				webView.goBack();
			}
		}
	}
	private void reportSystemInformation() {
		HashMap<String,String> versionInfo = versionInfo();
		Log.e(className, "***************************************************************");
		Log.e(className, "***                  System information                     ***");
		Log.e(className, "***  -----------------------------------------------------  ***");
		Log.e(className, "***                                                         ***");
		Log.e(className, "***  Android API level: " + paddedOut(String.valueOf(Build.VERSION.SDK_INT), 36) + "***");
		Log.e(className, "*** Android build date: " + paddedOut(String.valueOf(new Date(Build.TIME)), 36) + "***");
		Log.e(className, "***       manufacturer: " + paddedOut(Build.MANUFACTURER, 36) + "***");
		Log.e(className, "***              brand: " + paddedOut(Build.BRAND, 36) + "***");
		Log.e(className, "***            product: " + paddedOut(Build.PRODUCT, 36) + "***");
		Log.e(className, "***              model: " + paddedOut(Build.MODEL, 36) + "***");
		Log.e(className, "***           hardware: " + paddedOut(Build.HARDWARE, 36) + "***");
		Log.e(className, "***             device: " + paddedOut(Build.DEVICE, 36) + "***");
		Log.e(className, "***              board: " + paddedOut(Build.BOARD, 36) + "***");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Log.e(className, "***   soc manufacturer: " + paddedOut(Build.SOC_MANUFACTURER, 36) + "***");
			Log.e(className, "***          soc model: " + paddedOut(Build.SOC_MODEL, 36) + "***");
        }
		Log.e(className, "***                                                         ***");
		Log.e(className, "***         auto start: " + paddedOut(String.valueOf(getResources().getBoolean(R.bool.auto_start)), 36) + "***");
		Log.e(className, "***            restart: " + paddedOut(String.valueOf(getResources().getBoolean(R.bool.restart)), 36) + "***");
		Log.e(className, "***           app name: " + paddedOut(getString(R.string.appName), 36) + "***");
		Log.e(className, "***       version name: " + paddedOut(getString(R.string.versionName), 36) + "***");
		Log.e(className, "***           hostname: " + paddedOut(getString(R.string.hostName), 36) + "***");
		Log.e(className, "***                                                         ***");
		Log.e(className, "***        app version: " + paddedOut(versionInfo.get("appVersion"), 36) + "***");
		Log.e(className, "***    webview version: " + paddedOut(versionInfo.get("webviewVersion"), 36) + "***");
		Log.e(className, "***                                                         ***");
		Log.e(className, "***************************************************************");
	}
	private String paddedOut(String text, int length) {
		return String.format("%-" + length + "s", text);
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
		Log.i(className, "openBrowserView");
//		setContentView(R.layout.activity_main);
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
			// setContentView(?????);
		} else {
			// fall back to WebView
			if (webView != null) {
				Log.e(className, "openBrowserView webView is not null");
			}
			webView = openWebView(initialiseWebContent, playerId);
			if (android.os.Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
				// prevent unintended 'focus' highlighting of the browser view
				webView.setDefaultFocusHighlightEnabled(false);
			}
			setContentView(webView);
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
		memoryCheckHandler = new Handler(Looper.getMainLooper());
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
//		WebView result = (WebView) findViewById(R.id.mainUiView);
		WebView result = new CustomWebView(this);
		Log.i(className, "openWebView; webView is " + (result == null ? "null" : "not null"));
		setupWebView(result);
		result.setWebChromeClient(createWebChromeClient());
		result.setWebViewClient(createWebViewClient());
		result.setKeepScreenOn(true);
		// Make sure necessary cookies are accepted
		CookieManager cookieManager = CookieManager.getInstance();
		cookieManager.setAcceptCookie(true);
		cookieManager.setAcceptThirdPartyCookies(result, true);
		if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q) {
			CookieManager.setAcceptFileSchemeCookies(true);
		}
		if (initialiseWebContent) {
			result.loadDataWithBaseURL("file:///android_asset/",
					initialHtmlPage(playerId, result.getSettings().getUserAgentString()), "text/html", "UTF-8", null);
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
				biz.playr.CheckRestartService.LocalBinder binder = (biz.playr.CheckRestartService.LocalBinder) service;
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
		return result;
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

	private boolean isAndroidTV() {
		UiModeManager uiModeManager = (UiModeManager) getSystemService(UI_MODE_SERVICE);
		return uiModeManager.getCurrentModeType() == Configuration.UI_MODE_TYPE_TELEVISION || getPackageManager().hasSystemFeature(PackageManager.FEATURE_LEANBACK);
	}

	private String retrieveOrGeneratePlayerId() {
		String result = getStoredPlayerId();
		if (result == null || result.isEmpty()) {
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

			// Documentation says: Note: Do not call WebView#loadUrl(String) with the request's
			// URL and then return true. This unnecessarily cancels the current load and starts
			// a new load with the same URL. The correct way to continue loading a given URL is
			// to simply return false, without calling WebView#loadUrl(String).
			// http://stackoverflow.com/questions/4066438/android-webview-how-to-handle-redirects-in-app-instead-of-opening-a-browser
			// Return false from the callback instead of calling view.loadUrl
			// instead. Calling loadUrl introduces a subtle bug where if you
			// have any iframe within the page with a custom scheme URL
			// (say <iframe src="tel:123"/>) it will navigate your app's
			// main frame to that URL most likely breaking the app as a side effect.
			public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
				Log.i(className, "shouldOverrideUrlLoading");
				return false; // then it is not handled by default action
			}
			// This version of this method is deprecated from API level 24
			public boolean shouldOverrideUrlLoading(WebView view, String url) {
				Log.i(className, "shouldOverrideUrlLoading");
				return false; // then it is not handled by default action
			}

			// This version of this method is added in API level 23
			@Override
			public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
				// Log.i(className, "override onReceivedError");
				if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
					// Toast.makeText(getActivity(), "WebView Error" + error.getDescription(), Toast.LENGTH_SHORT).show();
					Log.e(className, "onReceivedError: WebView(Client) error - " + error.getDescription()
							+ " code; " + String.valueOf(error.getErrorCode()) + " URL; " + request.getUrl().toString());
				}
				if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
					if ("net::".equals(error.getDescription().subSequence(0,5))) {
						if (error.getErrorCode() == WebViewClient.ERROR_TOO_MANY_REQUESTS || error.getErrorCode() == WebViewClient.ERROR_REDIRECT_LOOP) {
							// escalate since app will freeze with these errors
							super.onReceivedError(view, request, error);
						} else {
							// ignore other network errors
							// super.onReceivedError(view, request, error);
						}
					} else {
						Log.e(className, "===>>> onReceivedError Reloading WebView !!!");
						// super.onReceivedError(view, request, error);
						view.reload();
					}
				}
			}
			// This version of this method is deprecated from API version 23
			@Override
			public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
				// Log.i(className, "override onReceivedError");
				// Toast.makeText(getActivity(), "WebView Error" + description(), Toast.LENGTH_SHORT).show();
				Log.e(className, "onReceivedError: WebView(Client) error - " + description + " code; " + String.valueOf(errorCode) + " URL; " + failingUrl);
				if ("net::".equals(description.subSequence(0,5))) {
					if (errorCode == WebViewClient.ERROR_TOO_MANY_REQUESTS || errorCode == WebViewClient.ERROR_REDIRECT_LOOP) {
						// escalate since app will freeze with these errors
						super.onReceivedError(view, errorCode, description, failingUrl);
					} else {
						// ignore other network errors
						// super.onReceivedError(view, errorCode, description, failingUrl);
					}
				} else {
					Log.e(className, "===>>> onReceivedError Reloading WebView !!!");
					// super.onReceivedError(view, errorCode, description, failingUrl);
					view.reload();
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

			// see https://stackoverflow.com/a/63707709
			// https://developer.android.com/reference/kotlin/androidx/webkit/WebViewAssetLoader
			@Override
			public WebResourceResponse shouldInterceptRequest(WebView view,
															  WebResourceRequest request) {
				return assetLoader.shouldInterceptRequest(request.getUrl());
			}
		};
	};

	private String initialHtmlPage(String playerId, String webviewUserAgent) {
		return "<html><head><script type=\"text/javascript\" charset=\"utf-8\">window.location = \""
				+ pageUrl(playerId, webviewUserAgent) + "\"</script><head><body/></html>";
	};

	// We use the ActivityManager.MemoryInfo.threshold: The threshold of availMem at which we consider
	// memory to be low and start killing background services and other non-extraneous processes.
	// MemoryStatus.OK       if available memory >  125% of threshold
	// MemoryStatus.MEDIUM   if available memory <= 125% and > 115% of threshold
	// MemoryStatus.LOW      if available memory <= 115% and > 105% of threshold
	// memoryStatus.CRITICAL if available memory <= 105%of threshold
	private MemoryStatus analyseMemoryStatus() {
		MemoryStatus result = MemoryStatus.OK;

		Log.i(className, "analyseMemoryStatus");
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
				"***************************************************************************************\n.");
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

	private HashMap<String, String> versionInfo() {
		HashMap<String, String> result = new HashMap<String, String>();
		result.put("webviewVersion", "Android System WebView not installed");
		result.put("appVersion", "App version not found");
		long versionCode;
		PackageManager pm = getPackageManager();
		PackageInfo pi;
		PackageInfo pi2;
		try {
			pi = pm.getPackageInfo("com.google.android.webview", 0);
			if (pi != null) {
				versionCode = PackageInfoCompat.getLongVersionCode(pi);
				result.put("webviewVersion", "Version-name: " + pi.versionName + " -code: " + versionCode);
			}
			if (android.os.Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
				pi2 = WebViewCompat.getCurrentWebViewPackage(MainActivity.this);
				if (pi2 != null) {
					versionCode = PackageInfoCompat.getLongVersionCode(pi2);
					if (pi != null) {
						result.put("webviewVersion", result.get("webviewVersion") + "Version-name: " + pi2.versionName + " -code: " + versionCode);
					} else {
						result.put("webviewVersion", "Version-name: " + pi2.versionName + " -code: " + versionCode);
					}
				}
			}
		} catch (PackageManager.NameNotFoundException e) {
			Log.e(className, "Android System WebView is not found");
		}
		try {
			pi = pm.getPackageInfo(getPackageName(), 0);
			if (pi != null) {
				result.put("appVersion", pi.versionName);
			}
		} catch (PackageManager.NameNotFoundException e) {
			Log.e(className, getPackageName() + " is not found");
		}
		return result;
	}

	private String pageUrl(String playerId, String webviewUserAgent) {
		HashMap<String, String> versionInfo = versionInfo();
		// ignore preference of the OS for use of https
		return Uri.parse("playr_loader.html").buildUpon()
				.appendQueryParameter("player_id", playerId)
				.appendQueryParameter("webview_user_agent", webviewUserAgent)
				.appendQueryParameter("webview_version", versionInfo.get("webviewVersion"))
				.appendQueryParameter("https_required", httpsRequired())
				.appendQueryParameter("app_version", versionInfo.get("appVersion")).build()
				.toString();
	};

	private void onSystemUiVisibilityChange(int visibility) {
		// Note that system bars will only be "visible" if none of the
		// LOW_PROFILE, HIDE_NAVIGATION, or FULLSCREEN flags are set.
		if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q && (visibility & View.SYSTEM_UI_FLAG_FULLSCREEN) == 0) {
			// bars are visible => user touched the screen, make the bars disappear again in 2 seconds
			Handler handler = new Handler(Looper.getMainLooper());
			handler.postDelayed(() -> hideBars(), 2000);
		} else {
			// The system bars are NOT visible => do nothing
		}
	}

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

	@SuppressLint("SetJavaScriptEnabled")
	/* Configure the Webview for usage as the application's window. */
	private void setupWebView(WebView webView) {
		Log.i(className, "setupWebView");
		if (webView != null) {
			WebSettings webSettings = webView.getSettings();
			webSettings.setJavaScriptEnabled(true);
			// available for android.os.Build.VERSION.SDK_INT > android.os.Build.VERSION_CODES.JELLY_BEAN
			webSettings.setMediaPlaybackRequiresUserGesture(false);
			webSettings.setJavaScriptCanOpenWindowsAutomatically(true);
			webSettings.setLoadWithOverviewMode(true);
			webSettings.setUseWideViewPort(true);
			webSettings.setAllowContentAccess(true);
			webSettings.setAllowFileAccess(true);
			if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q) {
				webSettings.setAllowFileAccessFromFileURLs(true);
				webSettings.setAllowUniversalAccessFromFileURLs(true);
			}
			webSettings.setLoadsImagesAutomatically(true);
			webSettings.setBlockNetworkImage(false);
			webSettings.setSupportMultipleWindows(false);
			webSettings.setMediaPlaybackRequiresUserGesture(false);
			// available for android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.JELLY_BEAN
			webSettings.setBuiltInZoomControls(false);
			webSettings.setDisplayZoomControls(false);
			webSettings.setSupportZoom(false);
			webSettings.setTextZoom(100);
			// TODO check if these font related settings are needed to ensure correct font rendering
			//webSettings.setDefaultFixedFontSize();
			//webSettings.setDefaultFontSize();
			//webSettings.setMinimumFontSize();
			//webSettings.setMinimumLogicalFontSize();
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
			// Currently it is impossible to achieve acceptable playback when there is no
			// internet connection.
			// The aim to minimize data traffic by using aggressive caching strategy will lead to
			// the fact that web pages shown in the browser-app will not refresh when specified
			// because the LOAD_CACHE_ELSE_NETWORK setting will prevent the necessary call to the
			// server
			webSettings.setCacheMode(WebSettings.LOAD_DEFAULT);
			webSettings.setDomStorageEnabled(true);
			// deprecated
			// webSettings.setAppCachePath(getApplicationContext().getFilesDir().getAbsolutePath() + "/cache");
			webSettings.setDatabaseEnabled(true);
			// deprecated
			// webSettings.setDatabasePath(getApplicationContext().getFilesDir().getAbsolutePath() + "/databases");
			webView.resumeTimers();
			webView.setLayerType(View.LAYER_TYPE_HARDWARE, null);
		} else {
			Log.e(className, "setupWebView, webView is null, cannot perform setup!");
		}
	}

	private String getStoredPlayerId() {
		SharedPreferences sharedPreferences = getPreferences(Context.MODE_PRIVATE);
		return sharedPreferences.getString(getString(R.string.player_id_store), "");
	}

	private void storePlayerId(String value) {
		SharedPreferences sharedPreferences = getPreferences(Context.MODE_PRIVATE);
		SharedPreferences.Editor editor = sharedPreferences.edit();
		editor.putString(getString(R.string.player_id_store), value);
		editor.commit();
	}

	private Date getActivityCreatedAt() {
		SharedPreferences defaultSharedPreferences = getDefaultSharedPreferences(getApplicationContext());
		return new Date(defaultSharedPreferences.getLong(getString(R.string.activity_created_at_key), 0));
	}

	private int getActivityState() {
		SharedPreferences defaultSharedPreferences = getDefaultSharedPreferences(getApplicationContext());
		return defaultSharedPreferences.getInt(getString(R.string.activity_state_key), -1);
	}

	private void storeActivityCreatedAt() {
		// Using Instant would be easier but Instant.now() is only available from API level 26
		Date now = new Date();
		long createdAt = now.getTime();

		SharedPreferences defaultSharedPreferences = getDefaultSharedPreferences(getApplicationContext());
		if (defaultSharedPreferences != null) {
			SharedPreferences.Editor editor = defaultSharedPreferences.edit();
			editor.putInt(getString(R.string.activity_state_key), 1);
			editor.putLong(getString(R.string.activity_created_at_key), createdAt);
			editor.apply(); // when this preferences value is used consider synchronous alternative: commit();
			Log.i(className, "storeActivityCreatedAt: activity state (1) and activity created at " + createdAt + " were stored");
		} else {
			Log.e(className, "storeActivityCreatedAt: default shared preferences not found!");
		}
	}

	private void clearActivityCreatedAt() {
		SharedPreferences defaultSharedPreferences = getDefaultSharedPreferences(getApplicationContext());
		if (defaultSharedPreferences != null) {
			SharedPreferences.Editor editor = defaultSharedPreferences.edit();
			editor.putInt(getString(R.string.activity_state_key), 0);
			editor.putLong(getString(R.string.activity_created_at_key), 0);
			editor.apply(); // when this preferences value is used consider synchronous alternative: commit();
			Log.i(className, "clearActivityCreatedAt: cleared activity state (0) and activity created at");
		} else {
			Log.e(className, "clearActivityCreatedAt: default shared preferences not found!");
		}
	}

	private void recreateBrowserView() {
		Log.i(className, "recreateBrowserView start");
		this.unBindServiceConnection();
		this.destroyBrowserView();
		this.openBrowserView(true, false, this.retrieveOrGeneratePlayerId());
		Log.i(className, "recreateBrowserView end");
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
			if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
				// The view drawing cache was largely made obsolete with
				// the introduction of hardware-accelerated rendering in API 11
				// see: https://developer.android.com/reference/android/view/View.html#destroyDrawingCache()
				webView.destroyDrawingCache();
			}

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
		this.finishAndRemoveTask();
	}

	private String httpsRequired() {
		if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.M) {
			return "no";
		} else {
			return NetworkSecurityPolicy.getInstance().isCleartextTrafficPermitted() ? "no" : "yes";
		}
	}
}
