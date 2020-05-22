package biz.playr;

//import java.util.Objects;
import java.util.UUID;
import biz.playr.R;
import android.content.ComponentName;
import android.content.ServiceConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.security.NetworkSecurityPolicy;
import android.util.Log;
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
//import android.webkit.WebSettings.PluginState;
//import androidx.webkit.WebViewCompat;
import android.webkit.WebView;
import android.webkit.WebSettings;
import android.webkit.WebViewClient;
import androidx.browser.customtabs.CustomTabsCallback;
import androidx.browser.customtabs.CustomTabsClient;
import androidx.browser.customtabs.CustomTabsIntent;
import androidx.browser.customtabs.CustomTabsServiceConnection;
import androidx.browser.customtabs.CustomTabsSession;
import androidx.browser.customtabs.TrustedWebUtils;
// not used
//import androidx.browser.trusted.TrustedWebActivityIntentBuilder;
//import androidx.browser.trusted.TrustedWebActivityIntent;
//import java.lang.Thread.UncaughtExceptionHandler;
//import android.content.Intent;
//import android.widget.Toast;
// packages below were migrated to androidx
//import android.support.customtabs.CustomTabsCallback;
//import android.support.customtabs.CustomTabsClient;
//import android.support.customtabs.CustomTabsIntent;
//import android.support.customtabs.CustomTabsServiceConnection;
//import android.support.customtabs.CustomTabsSession;
//import android.support.customtabs.TrustedWebUtils;


public class MainActivity extends Activity implements IServiceCallbacks {
	private WebView webView = null;
	private static final String className = "biz.playr.MainActivity";
	private CheckRestartService checkRestartService;
	private boolean bound = false;
	private boolean callbackSet = false;
	// TWA related
	private boolean chromeVersionChecked = false;
	private boolean webViewWasLaunched = false;
	private boolean twaWasLaunched = false;
	private static final int SESSION_ID = 96375;
	private static final String TWA_WAS_LAUNCHED_KEY = "android.support.customtabs.trusted.TWA_WAS_LAUNCHED_KEY";

//	private WebViewServiceConnection webViewServiceConnection;
//	private TwaCustomTabsServiceConnection twaServiceConnection;

	// Callbacks for service binding, passed to bindService()
	// set checkRestartService and set callback on it
	// bindService happens in onStart()
	// callback will be set to null in onStop
	private ServiceConnection webViewServiceConnection = new ServiceConnection() {
		private static final String className = "wv ServiceConnection";

		@Override
		public void onServiceConnected(ComponentName componentName, IBinder service) {
			Log.i(className, "override onServiceConnected");
			// cast the IBinder and get CheckRestartService instance
			biz.playr.CheckRestartService.LocalBinder binder = (biz.playr.CheckRestartService.LocalBinder) service;
			checkRestartService = binder.getService();
			checkRestartService.setCallbacks(MainActivity.this); // bind IServiceCallbacks
			callbackSet = true;
			Log.i(className, "onServiceConnected: callbacks set on CheckRestartService");
		}

		@Override
		public void onServiceDisconnected(ComponentName componentName) {
			Log.i(className, "override onServiceDisconnected");
		}
	};

    private CustomTabsServiceConnection twaServiceConnection = new CustomTabsServiceConnection() {
//	private WebViewServiceConnection twaServiceConnection = new CustomTabsServiceConnection() {
		private static final String className = "twa CustTabsServiceCon.";

		@Override
		public void onCustomTabsServiceConnected(ComponentName componentName, CustomTabsClient client) {
			Log.i(className, "override onCustomTabsServiceConnected");
			client.warmup(0L);
			CustomTabsSession session = getSession(client);
//			CustomTabsIntent intent = getCustomTabsIntent(session);
			Uri uri = Uri.parse("http://play.playr.biz"); // <<===### use the url for the player_loader page or the
			Log.d(className, "Launching Trusted Web Activity.");
			// deprecated
			// TrustedWebUtils.launchAsTrustedWebActivity(this@LauncherActivity, session!!, intent, url);
			// new way is not possible because of class incompatibilities
//			TrustedWebActivityIntentBuilder trustedWebActivityIntentBuilder = new TrustedWebActivityIntentBuilder(uri);
//			TrustedWebActivityIntent trustedWebActivityIntent = trustedWebActivityIntentBuilder.build(session);
//			TrustedWebUtils.launchAsTrustedWebActivity(MainActivity.this, trustedWebActivityIntent, uri);
			twaWasLaunched = true;
		}

		// onServiceConnected is final on CustomTabsServiceConnection alas
//		@Override
//		public void onServiceConnected(ComponentName componentName, IBinder service) {
//			Log.i(className, "override onServiceConnected");
//			// cast the IBinder and get CheckRestartService instance
//			biz.playr.CheckRestartService.LocalBinder binder = (biz.playr.CheckRestartService.LocalBinder) service;
//			checkRestartService = binder.getService();
//			checkRestartService.setCallbacks(MainActivity.this); // bind IServiceCallbacks
//			callbackSet = true;
//			Log.i(className, "onServiceConnected: callbacks set on CheckRestartService");
//		}

		@Override
		public void onServiceDisconnected(ComponentName componentName) {
			Log.i(className, "override onServiceDisconnected");
		}
	};


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

		// Set up looks of the view
		getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
		requestWindowFeature(Window.FEATURE_NO_TITLE);
		View decorView = getWindow().getDecorView();
		decorView
			.setOnSystemUiVisibilityChangeListener(new View.OnSystemUiVisibilityChangeListener() {
				@Override
				public void onSystemUiVisibilityChange(int visibility) {
					// Note that system bars will only be "visible" if none of the
					// LOW_PROFILE, HIDE_NAVIGATION, or FULLSCREEN flags are set.
					if ((visibility & View.SYSTEM_UI_FLAG_FULLSCREEN) == 0) {
						// bars are visible => user touched the screen, make
						// the bars disappear again in 2 seconds
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
		setContentView(R.layout.activity_main);

		String playerId = retrieveOrGeneratePlayerId();

		// create Trusted Web Access or fall back to a WebView
		String chromePackage = CustomTabsClient.getPackageName(this, TrustedWebUtils.SUPPORTED_CHROME_PACKAGES, true);
		if (chromePackage != null) {
			if (!chromeVersionChecked) {
				TrustedWebUtils.promptForChromeUpdateIfNeeded(this, chromePackage);
				chromeVersionChecked = true;
			}

			if (savedInstanceState != null && savedInstanceState.getBoolean(MainActivity.TWA_WAS_LAUNCHED_KEY)) {
				this.finish();
			} else {
//				TwaCustomTabsServiceConnection twaServiceConnection = new TwaCustomTabsServiceConnection();
				CustomTabsClient.bindCustomTabsService(this, chromePackage, twaServiceConnection);
			}
		} else {
			// fall back to WebView
			// Setup webView
			webView = (WebView) findViewById(R.id.mainUiView);
			Log.i(className, "webView is " + (webView == null ? "null" : "not null"));
			setupWebView(webView);
			webView.setWebChromeClient(createWebChromeClient());
			webView.setWebViewClient(createWebViewClient());
			webView.setKeepScreenOn(true);
			if (savedInstanceState == null) {
				webView.loadDataWithBaseURL("file:///android_asset/",
						initialHtmlPage(playerId, webView.getSettings().getUserAgentString()), "text/html", "UTF-8", null);
			}
		}

		// Setup webView
//		webView = (WebView) findViewById(R.id.mainUiView);
//		Log.i(className, "webView is " + (webView == null ? "null" : "not null"));
//		setupWebView(webView);
//		webView.setWebChromeClient(createWebChromeClient());
//		webView.setWebViewClient(createWebViewClient());
//		webView.setKeepScreenOn(true);
//		if (savedInstanceState == null) {
//			webView.loadDataWithBaseURL("file:///android_asset/",
//					initialHtmlPage(playerId, webView.getSettings().getUserAgentString()), "text/html", "UTF-8", null);
//		}
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
				Log.i(className, "override onConsoleMessage");
				// Log.i(className,"override onConsoleMessage: " +
				// consoleMessage.message());
				// count++;
				// if (count == 10) {
				// Log.i(className,">>>>>> override onConsoleMessage, throw Exception");
				// // throw new IllegalArgumentException("Test exception");
				// } else {
				// Log.i(className,">>>>>> override onConsoleMessage, count = " + count);
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
				// main frame to that URL most likely breaking the app as a side
				// effect.
				// http://stackoverflow.com/questions/4066438/android-webview-how-to-handle-redirects-in-app-instead-of-opening-a-browser
				return false; // then it is not handled by default action
			}

			/*
			 * this version of this method si deprecated from API version 23
			 */
			@Override
			public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
				Log.i(className, "override onReceivedError");
				// Toast.makeText(getActivity(), "WebView Error" +
				// description(), Toast.LENGTH_SHORT).show();
				Log.e(className, "WebView(Client) error: " + description
						+ " code: " + String.valueOf(errorCode) + " URL: "
						+ failingUrl);
				Log.e(className, "===>>> !!! WebViewClient.onReceivedError Reloading Webview !!! <<<===");
				// super.onReceivedError(view, errorCode, description, failingUrl);
				view.reload();
			}

			/*
			 * Added in API level 23 (use these when we set
			 * android:targetSdkVersion to 23)
			 */
			@Override
			public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
				Log.i(className, "override onReceivedError");
				if (android.os.Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
					// Toast.makeText(getActivity(), "WebView Error" + error.getDescription(), Toast.LENGTH_SHORT).show();
					Log.e(className, "onReceivedError WebView error: " + error.getDescription()
							+ " code: " + String.valueOf(error.getErrorCode()) + " URL: " + request.getUrl().toString());
				}
				Log.e(className, "===>>> !!! WebViewClient.onReceivedError Reloading Webview !!! <<<===");
				// super.onReceivedError(view, request, error);
				view.reload();
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

	private String pageUrl(String playerId, String webviewUserAgent) {
//		String webviewUserAgent = webView.getSettings().getUserAgentString();
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
//			pi2 = WebViewCompat.getCurrentWebViewPackage(appContext);
			if (android.os.Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
				pi2 = WebView.getCurrentWebViewPackage();
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
		return Uri.parse("playr_loader.html").buildUpon()
				.appendQueryParameter("player_id", playerId)
				.appendQueryParameter("webview_user_agent", webviewUserAgent)
				.appendQueryParameter("webview_version", webviewVersion)
				.appendQueryParameter("https_required", httpsRequired())
				.appendQueryParameter("app_version", appVersion).build()
				.toString();
	};

	// Callbacks for service binding, passed to bindService()
//	private class WebViewServiceConnection extends ServiceConnection {
//		private static final String className = "WebViewServiceConnection";
//
//		@Override
//		public void onServiceConnected(ComponentName componentName, IBinder service) {
//			Log.i(className, " override onServiceConnected");
//			// cast the IBinder and get CheckRestartService instance
//			biz.playr.CheckRestartService.LocalBinder binder = (biz.playr.CheckRestartService.LocalBinder) service;
//			checkRestartService = binder.getService();
//			bound = true;
//			checkRestartService.setCallbacks(MainActivity.this); // bind IServiceCallbacks
//			Log.i(className, "onServiceConnected: service bound");
//		}
//
//		@Override
//		public void onServiceDisconnected(ComponentName componentName) {
//			Log.i(className, "override onServiceDisconnected");
//			bound = false;
//		}
//	};

//	private class TwaCustomTabsServiceConnection extends CustomTabsServiceConnection {
//		private static final String className = "TwaCusTabsSerConnection";
//
//		@Override
//		public void onCustomTabsServiceConnected(ComponentName componentName, CustomTabsClient client) {
//			Log.i(className, " override onCustomTabsServiceConnected");
//			client.warmup(0L);
//			CustomTabsSession session = getSession(client);
//			// CustomTabsIntent intent = getCustomTabsIntent(session);
//			Uri uri = Uri.parse("http://play.playr.biz"); // <<===### use the url for the player_loader page or the
//			Log.d(className, "Launching Trusted Web Activity.");
//			// TrustedWebUtils.launchAsTrustedWebActivity(this@LauncherActivity, session!!, intent, url);
//			TrustedWebActivityIntentBuilder trustedWebActivityIntentBuilder = new TrustedWebActivityIntentBuilder(uri);
//			TrustedWebActivityIntent trustedWebActivityIntent = trustedWebActivityIntentBuilder.build(session);
//			twaWasLaunched = true;
//		}
//
//		@Override
//		public void onServiceDisconnected(ComponentName componentName) {
//			Log.i(className, "override onServiceDisconnected");
//		}
//	}

	protected CustomTabsSession getSession(CustomTabsClient client) {
		return client.newSession((CustomTabsCallback)null, SESSION_ID);
	}

    protected CustomTabsIntent getCustomTabsIntent(CustomTabsSession session) {
        CustomTabsIntent.Builder builder = new CustomTabsIntent.Builder(session);
        return builder.build();
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

	public void handleUncaughtException(Thread paramThread,
			Throwable paramThrowable) {
		Log.e(className, "handleUncaughtException; paramThread: " + paramThread
				+ ", paramThrowable: " + paramThrowable);
		// restartActivity();
		recreate();
	}

	public void restartDelayed() {
		Log.i(className, "restartDelayed");

//		PendingIntent localPendingIntent = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_ONE_SHOT);
		Intent activityIntent = new Intent(this.getBaseContext(),
				biz.playr.MainActivity.class);
		activityIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP
				| Intent.FLAG_ACTIVITY_CLEAR_TASK
				| Intent.FLAG_ACTIVITY_NEW_TASK);
		activityIntent.setAction(Intent.ACTION_MAIN);
		activityIntent.addCategory(Intent.CATEGORY_LAUNCHER);
		PendingIntent localPendingIntent = PendingIntent.getActivity(this.getBaseContext(), 0, activityIntent, PendingIntent.FLAG_ONE_SHOT);
		AlarmManager mgr =  (AlarmManager) getSystemService(Context.ALARM_SERVICE);
		if (mgr != null) {
			mgr.set(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + DefaultExceptionHandler.restartDelay, localPendingIntent);
		}
		Log.i(className, "restartDelayed: end");
	}

	// implement the IServiceCallbacks interface
	public void restartActivityWithDelay() {
		this.restartActivity();
	}
	public String getPlayerId() {
		return getStoredPlayerId();
	}
	// end of implementation IServiceCallbacks

	public void restartActivity() {
		Log.i(className, "restartActivity");
		// the context of the activityIntent might need to be the running PlayrService
		// keep the Intent in sync with the Manifest and DefaultExceptionHandler
		Intent activityIntent = new Intent(this.getBaseContext(),
				biz.playr.MainActivity.class);
		activityIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP
				| Intent.FLAG_ACTIVITY_CLEAR_TASK
				| Intent.FLAG_ACTIVITY_NEW_TASK);
		activityIntent.setAction(Intent.ACTION_MAIN);
		activityIntent.addCategory(Intent.CATEGORY_LAUNCHER);
		// startActivity(activityIntent);

		// delay start so this activity can be ended before the new one starts
		PendingIntent pendingIntent = PendingIntent.getActivity(
				this.getBaseContext(), 0, activityIntent,
				PendingIntent.FLAG_ONE_SHOT);
		// Following code will restart application after <delay> seconds
		AlarmManager mgr = (AlarmManager) biz.playr.MainApplication
				.getInstance().getBaseContext()
				.getSystemService(Context.ALARM_SERVICE);
		if (mgr != null) {
			mgr.set(AlarmManager.RTC_WAKEUP, System.currentTimeMillis()
					+ DefaultExceptionHandler.restartDelay, pendingIntent);
		}

		Log.i(className, "restartActivity: killing this process");
		setResult(RESULT_OK);
		finish();
		android.os.Process.killProcess(android.os.Process.myPid());
		// System.exit(2);
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
		webSettings.setCacheMode(WebSettings.LOAD_NO_CACHE);
		webView.setLayerType(View.LAYER_TYPE_HARDWARE, null);
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
	protected void onStart() {
		Log.i(className, "override onStart");
		super.onStart();
		// bind to CheckRestartService
		Intent intent = new Intent(this, CheckRestartService.class);
		if (webViewServiceConnection != null) {
			bindService(intent, webViewServiceConnection, Context.BIND_AUTO_CREATE);
			bound = true;
		} else if (twaServiceConnection != null) {
			bindService(intent, twaServiceConnection, Context.BIND_AUTO_CREATE);
			bound = true;
		}
//		bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE);
//		bindService(intent, serviceConnection, Context.BIND_IMPORTANT);
//		bindService(intent, serviceConnection, Context.BIND_ABOVE_CLIENT);
		Log.i(className, "onStart: service bound (auto create)");
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
		if (callbackSet) {
			checkRestartService.setCallbacks(null);
			callbackSet = false;
		}
		if (bound) {
			if (webViewServiceConnection != null) {
				unbindService(webViewServiceConnection);
			}
			if (twaServiceConnection != null) {
				unbindService(twaServiceConnection);
			}
			bound = false;
		}
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
	}

	@Override
	protected void onDestroy() {
		Log.i(className, "override onDestroy");

		// since onDestroy is called when the device changes aspect ratio
		// (which is possible on tablets) this method cannot be used to force
		// a restart of the application when this method is called.
		// Having this logic here causes a restart loop when the device changes
		// aspect the ratio.
		// Log.e(className,".onDestroy: Prepare to restart the app.");
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
		// AlarmManager mgr = (AlarmManager)
		// biz.playr.MainApplication.getInstance().getBaseContext().getSystemService(Context.ALARM_SERVICE);
		// mgr.set(AlarmManager.RTC, System.currentTimeMillis() +
		// DefaultExceptionHandler.restartDelay, pendingIntent);
		//

		// TODO: properly handling the delayed restart can only be done by letting the system handle less onDestroy events:
		// see: https://developer.android.com/guide/topics/resources/runtime-changes#java
		// this would mean:
		// 1) configure onDestroy not to be called when the screen is rotated
		// 2) calling restartDelayed() here
		// 3) handling the screen rotation in an overridden onConfigurationChanged implementation
		Log.e(className,".onDestroy: Delayed restart of the application !!!");
		restartDelayed();
		if (this.bound) {
			if (this.webViewServiceConnection != null) {
				unbindService(this.webViewServiceConnection);
				this.bound = false;
			}
			if (this.twaServiceConnection != null) {
				unbindService(this.twaServiceConnection);
				this.bound = false;
			}
		}
		super.onDestroy();
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
					| View.SYSTEM_UI_FLAG_LAYOUT_STABLE;
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
}
