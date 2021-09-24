package biz.playr;

import java.util.UUID;

//import android.Manifest;
import android.app.ActivityManager;
import android.app.UiModeManager;
import android.content.ComponentCallbacks2;
import android.content.ComponentName;
import android.content.ServiceConnection;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.provider.Settings;
import android.security.NetworkSecurityPolicy;
import android.util.Log;
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
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebResourceError;
import android.webkit.WebView;
import android.webkit.WebSettings;
import android.webkit.WebViewClient;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.browser.customtabs.CustomTabsCallback;
import androidx.browser.customtabs.CustomTabsClient;
import androidx.browser.customtabs.CustomTabsIntent;
import androidx.browser.customtabs.CustomTabsServiceConnection;
import androidx.browser.customtabs.CustomTabsSession;
import androidx.browser.customtabs.TrustedWebUtils;
//import androidx.core.app.ActivityCompat;
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
	private static final long MB = 1048576L;
	private static final long memoryCheckInterval = 5*60*1000; // 5 minutes
	// TWA related
	private boolean chromeVersionChecked = false;
	private boolean twaWasLaunched = false;
	private static final int SESSION_ID = 96375;
	private static final String TWA_WAS_LAUNCHED_KEY = "android.support.customtabs.trusted.TWA_WAS_LAUNCHED_KEY";
	private static final int REQUEST_OVERLAY_PERMISSION = 1;

	@Nullable
	private MainActivity.TwaCustomTabsServiceConnection twaServiceConnection;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		Log.i(className, "override onCreate");
		super.onCreate(savedInstanceState);

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
		getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
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

		// on Android 10 and later getting the app to start up uses an overlay
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
			requestManageOverlayPermission(getApplicationContext());
//			if (!Settings.canDrawOverlays(getApplicationContext())) {
//				Intent myIntent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION);
//				Uri uri = Uri.fromParts("package", getPackageName(), null);
//
//				myIntent.setData(uri);
//				startActivityForResult(myIntent, REQUEST_OVERLAY_PERMISSIONS);
//			}

//			if (!Settings.canDrawOverlays(getApplicationContext())) {
//				startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION));
//			}
		}

		// create Trusted Web Access or fall back to a WebView
		openBrowserView((savedInstanceState == null),
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
			// code to handle REQUEST_OVERLAY_PERMISSION case
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
		PendingIntent localPendingIntent = PendingIntent.getActivity(MainActivity.this.getBaseContext(), 0, activityIntent, PendingIntent.FLAG_ONE_SHOT);
		// delay start so this activity can be ended before the new one starts
		// Following code will restart application after DefaultExceptionHandler.restartDelay milliseconds
		AlarmManager mgr =  (AlarmManager) getSystemService(Context.ALARM_SERVICE);
		if (mgr != null) {
			Log.i(className, "restartDelayed: setting alarm manager to restart with a delay of " +  DefaultExceptionHandler.restartDelay/1000 + " seconds");
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
				mgr.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + DefaultExceptionHandler.restartDelay, localPendingIntent);
				Log.i(className, "restartDelayed: called setExactAndAllowWhileIdle");
			} else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
				mgr.setExact(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + DefaultExceptionHandler.restartDelay, localPendingIntent);
				Log.i(className, "restartDelayed: called setExact");
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
		Log.e(className, "********************\n***\n***\n*** onTrimMemory - level: " + level + "\n***\n***\n****************************************");

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
//			Intent intent = new Intent(MainActivity.this.getBaseContext(), CheckRestartService.class);
			Intent intent = new Intent(this, CheckRestartService.class);
			intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
			bindService(intent, (ServiceConnection) this.twaServiceConnection, Context.BIND_AUTO_CREATE);
			// checkRestartService = TODO how do we point this attribute to the service instance
			Log.i(className, "onStart: restart service is bound to twaServiceConnection (TWA is used) [BIND_AUTO_CREATE]");
			bound = true;
		} else if (this.webView != null) {
			if (this.checkRestartService == null) {
				Log.e(className, "onStart: webView is defined but checkRestartService is null.");
			}
			Intent intent = new Intent(this, CheckRestartService.class);
			intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
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
		if (checkRestartService != null) {
			checkRestartService.setCallbacks(null);
			Log.i(className, "onStop: callbacks set to null on restart service");
		}
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

	private void unBindServiceConnection() {
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

		// the onStop method should have set callbacks to null already, but just to be sure
		if (checkRestartService != null) {
			checkRestartService.setCallbacks(null);
			Log.i(className, "onDestroy: callbacks set to null on restart service");
		}
		// the onStop method should have unbound the service already, but just to be sure
		if (bound) {
			unBindServiceConnection();
		} else {
			Log.i(className, "onDestroy: connection is unbound");
		}
		this.destroyBrowserView();
		super.onDestroy();
		Log.i(className, "onDestroy: end");
	}

	@SuppressLint("InlinedApi")
	protected void hideBars() {
		if (getWindow() != null) {
			View decorView = getWindow().getDecorView();
			// Hide both the navigation bar and the status bar.
			// SYSTEM_UI_FLAG_FULLSCREEN is only available on Android 4.1 and higher, but as
			// a general rule, you should design your app to hide the status bar whenever you
			// hide the navigation bar.
			int uiOptions = View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
							| View.SYSTEM_UI_FLAG_FULLSCREEN
							| View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
							| View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
							| View.SYSTEM_UI_FLAG_LAYOUT_STABLE;
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

		memoryCheckRunner = () -> {
			freeMemoryWhenNeeded(analyseMemoryStatus());
			// run again
			memoryCheckHandler.postDelayed(memoryCheckRunner, interval);
		 };
		// initial run
		memoryCheckHandler.postDelayed(memoryCheckRunner, interval);
	}

	private WebView openWebView(boolean initialiseWebContent, String playerId) {
		WebView result = (WebView) findViewById(R.id.mainUiView);
		Log.i(className, "openWebView; webView is " + (result == null ? "null" : "not null"));
		setupWebView(result);
		result.setWebChromeClient(createWebChromeClient());
		result.setWebViewClient(createWebViewClient());
		result.setKeepScreenOn(true);
		if (initialiseWebContent) {
			result.loadDataWithBaseURL("file:///android_asset/",
					initialHtmlPage(playerId, result.getSettings().getUserAgentString()), "text/html", "UTF-8", null);
		}

		// Callbacks for service binding, passed to bindService()
		serviceConnection = new ServiceConnection() {
			private static final String className = "ServiceConnection";

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
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !isAndroidTV()) {
			if (!Settings.canDrawOverlays(context)) {
				Log.i(className, "requestManageOverlayPermission: requesting overlay permission");
				Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
						Uri.parse("package:" + getPackageName()));
				startActivityForResult(intent, REQUEST_OVERLAY_PERMISSION);
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
			private String className = "WebChromeClient";
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
				// Return false from the callback instead of calling view.loadUrl
				// instead. Calling loadUrl introduces a subtle bug where if you
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

		// Find out how much memory is available; availMem, totalMem, threshold and lowMemory are available as values
		ActivityManager.MemoryInfo memoryInfo = getAvailableMemory();
		Runtime runtime = Runtime.getRuntime();
		long availableHeapSizeInMB = (runtime.maxMemory()/MB) - ((runtime.totalMemory() - runtime.freeMemory())/MB);
		if (memoryInfo.availMem > 1.25*memoryInfo.threshold) {
			result = MemoryStatus.OK;
		} else if (memoryInfo.availMem <= 1.25*memoryInfo.threshold && memoryInfo.availMem > 1.15*memoryInfo.threshold) {
			result = MemoryStatus.MEDIUM;
		} else if (memoryInfo.availMem <= 1.15*memoryInfo.threshold && memoryInfo.availMem > 1.05*memoryInfo.threshold) {
			result = MemoryStatus.LOW;
		} else { // memoryInfo.availMem <= 1.05*memoryInfo.threshold
			result = MemoryStatus.CRITICAL;
		}
		Log.e(className, ".\n***************************************************************************************\n" +
				"*** total memory: " + memoryInfo.totalMem/MB + " MB\n" +
				"*** available memory: " + memoryInfo.availMem/MB + " (" + this.firstMemoryInfo.availMem/MB + ", " + (memoryInfo.availMem - this.firstMemoryInfo.availMem)/MB + ") [MB]\n" +
				"*** threshold: " + memoryInfo.threshold/MB + " MB\n" +
				"*** low memory?: " + memoryInfo.lowMemory + " (" + this.firstMemoryInfo.lowMemory + ")\n" +
				"*** available heap size: " + availableHeapSizeInMB + " (" + this.firstAvailableHeapSizeInMB + ", " + (availableHeapSizeInMB - this.firstAvailableHeapSizeInMB) + ") [MB]\n" +
				"***************************************************************************************\n" +
				"*** available memory: " + Math.round(100*memoryInfo.availMem/this.firstMemoryInfo.availMem) + "% of initial available and " + Math.round(100*memoryInfo.availMem/memoryInfo.threshold) + "% of threshold => result: " + result  + "\n" +
				"***************************************************************************************");
		return result;
	}
	private void freeMemoryWhenNeeded(MemoryStatus status) {
		switch (status) {
			case CRITICAL:
				// Release as much memory as the process can.
				// ==>> restart the activity
				this.restartActivity();
				break;
			case LOW:
				// Release any UI objects that currently hold memory.
				// ==>> dump browser view and recreate it
				this.recreateBrowserView();
			case MEDIUM:
				// Release any memory that your app doesn't need to run.
				// ==>> reload browser view
				this.reloadBrowserView();
				break;
			case OK:
			default:
				// no action needs to be taken
				break;
		}
	}

	private String pageUrl(String playerId, String webviewUserAgent) {
		String webviewVersion = "Android System WebView not installed";
		String appVersion = "app version not found";
		PackageManager pm = getPackageManager();
		PackageInfo pi;
		PackageInfo pi2;
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
		return Uri.parse("playr_loader.html").buildUpon()
				.appendQueryParameter("player_id", playerId)
				.appendQueryParameter("webview_user_agent", webviewUserAgent)
				.appendQueryParameter("webview_version", webviewVersion)
				.appendQueryParameter("https_required", "no")
				.appendQueryParameter("app_version", appVersion).build()
				.toString();
	};

	private class TwaCustomTabsServiceConnection extends CustomTabsServiceConnection {
		private static final String className = "TwaCusTabsSerConnection";

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
		webSettings.setAllowFileAccess(true);
		// available for android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.JELLY_BEAN
		webSettings.setAllowUniversalAccessFromFileURLs(true);
		webSettings.setBuiltInZoomControls(false);
		webSettings.setSupportZoom(false);
		// available for android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.KITKAT
		// webSettings.setPluginState(PluginState.ON);
		// When navigating back, content is not revalidated, instead the content is just retrieved
		// from the cache. This method allows the client to override this behavior
		webSettings.setCacheMode(WebSettings.LOAD_NO_CACHE);
		webView.resumeTimers();
		webView.setLayerType(View.LAYER_TYPE_HARDWARE, null);
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
				Log.i(className, "destroyWebView: removeView()");
				// viewGroup.removeAllViews();
				viewGroup.removeView(webView);
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

			webView.removeAllViews();

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
