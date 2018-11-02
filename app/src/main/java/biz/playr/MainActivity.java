package biz.playr;

import java.util.Objects;
import java.util.UUID;

import biz.playr.R;

import android.content.ComponentName;
import android.content.ServiceConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
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
import android.webkit.WebSettings.PluginState;
import android.webkit.WebView;
import android.webkit.WebSettings;
import android.webkit.WebViewClient;
import android.widget.Toast;

//import java.lang.Thread.UncaughtExceptionHandler;
//import android.content.Intent;

public class MainActivity extends Activity implements IServiceCallbacks {
	private WebView webView = null;
	private static final String className = "biz.playr.MainActivity";
	private CheckRestartService checkRestartService;
	private boolean bound = false;

	// Callbacks for service binding, passed to bindService()
	private ServiceConnection serviceConnection = new ServiceConnection() {
		private static final String className = "ServiceConnection";

		@Override
		public void onServiceConnected(ComponentName componentName, IBinder service) {
			Log.i(className, " override ServiceConnection.onServiceConnected");
			// cast the IBinder and get CheckRestartService instance
			biz.playr.CheckRestartService.LocalBinder binder = (biz.playr.CheckRestartService.LocalBinder) service;
			checkRestartService = binder.getService();
			bound = true;
			checkRestartService.setCallbacks(MainActivity.this); // bind IServiceCallbacks
			Log.i(className, " ServiceConnection.onServiceConnected: service bound");
		}

		@Override
		public void onServiceDisconnected(ComponentName componentName) {
			Log.i(className, " override ServiceConnection.onServiceDisconnected");
			bound = false;
		}
	};

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		Log.i(className, "override onCreate");
		super.onCreate(savedInstanceState);
		getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

		requestWindowFeature(Window.FEATURE_NO_TITLE);

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

		// Setup visibility of system bars
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

		String playerId = getStoredPlayerId();
		if (playerId == null || playerId.length() == 0) {
			playerId = UUID.randomUUID().toString();
			storePlayerId(playerId);
			Log.i(className, "generated and stored playerId: " + playerId);
		} else {
			Log.i(className, "retrieved stored playerId: " + playerId);
		}

		// Setup webView
		webView = (WebView) findViewById(R.id.mainUiView);
		Log.i(className, "webView is " + (webView == null ? "null" : "not null"));
		setupWebView(webView);
		webView.setWebChromeClient(new WebChromeClient() {
			private String className = "WebChromeClient";

			// private int count = 0;

			@Override
			public void onShowCustomView(View view, CustomViewCallback callback) {
				Log.i(className, "override setWebChromeClient");
				super.onShowCustomView(view, callback);
			}

			@Override
			public boolean onConsoleMessage(ConsoleMessage consoleMessage) {
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
		});
		webView.setWebViewClient(new WebViewClient() {
			private static final String className = "biz.playr.WebViewClient";

			public boolean shouldOverrideUrlLoading(WebView view, String url) {
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
       public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
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
				 if (android.os.Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
					 // Toast.makeText(getActivity(), "WebView Error" + errorResponse.getReasonPhrase(), Toast.LENGTH_SHORT).show();
					 Log.e(className, "onReceivedHttpError WebView http error: " + errorResponse.getReasonPhrase()
							 + " URL: " + request.getUrl().toString());
				 }
				 super.onReceivedHttpError(view, request, errorResponse);
			 }
		});
		webView.setKeepScreenOn(true);

		String webviewUserAgent = webView.getSettings().getUserAgentString();
		String webviewVersion = "Android System WebView not installed";
		String appVersion = "app version not found";
		PackageManager pm = getPackageManager();
		PackageInfo pi;
		try {
			pi = pm.getPackageInfo("com.google.android.webview", 0);
			if (pi != null) {
				webviewVersion = "Version name: " + pi.versionName
						+ " Version code: " + pi.versionCode;
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

		if (savedInstanceState == null) {
			String pageUrl = Uri
					.parse("playr_loader.html")
					.buildUpon()
					.appendQueryParameter("player_id", playerId)
					.appendQueryParameter("webview_user_agent",
							webviewUserAgent)
					.appendQueryParameter("webview_version", webviewVersion)
					.appendQueryParameter("app_version", appVersion).build()
					.toString();
			String initialHtmlPage = "<html><head><script type=\"text/javascript\" charset=\"utf-8\">window.location = \""
					+ pageUrl + "\"</script><head><body/></html>";
			webView.loadDataWithBaseURL("file:///android_asset/",
					initialHtmlPage, "text/html", "UTF-8", null);
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
	}

	@Override
	protected void onSaveInstanceState(Bundle outState) {
		Log.i(className, "override onSaveInstanceState");
		super.onSaveInstanceState(outState);
		webView.saveState(outState);
	}

	@Override
	protected void onRestoreInstanceState(Bundle savedInstanceState) {
		Log.i(className, "override onRestoreInstanceState");
		super.onRestoreInstanceState(savedInstanceState);
		if (savedInstanceState != null && !savedInstanceState.isEmpty()) {
			webView.restoreState(savedInstanceState);
		}
	}

	@Override
	protected void onResume() {
		Log.i(className, "override onResume");
		super.onResume();

		hideBars();
		webView.onResume();
	}

	@Override
	protected void onStart() {
		Log.i(className, "override onStart");
		super.onStart();
		// bind to CheckRestartService
		Intent intent = new Intent(this, CheckRestartService.class);
		bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE);
//		bindService(intent, serviceConnection, Context.BIND_IMPORTANT);
//		bindService(intent, serviceConnection, Context.BIND_ABOVE_CLIENT);
		Log.i(className, "onStart: service bound (auto create)");
	}

	@Override
	protected void onRestart() {
		Log.i(className, "override onRestart");
		super.onRestart();
	}

	@Override
	protected void onPause() {
		Log.i(className, "override onPause");
		webView.onPause();
		super.onPause();
	}

	protected void onStop() {
		Log.i(className, "override onStop");
		// Unbind from service
		if (bound) {
			checkRestartService.setCallbacks(null); // unregister
			unbindService(serviceConnection);
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
		// Log.e(className,".onDestroy: super.onDestroy() !!! About to restart application !!!");
		restartDelayed();
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
