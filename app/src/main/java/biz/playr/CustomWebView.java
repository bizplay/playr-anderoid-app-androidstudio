package biz.playr;

import android.annotation.SuppressLint;
import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.webkit.WebView;

/**
 * WebView that allows normal single-finger interaction with page content, but
 * suppresses long-press and multi-touch (pinch/zoom and two-finger gestures).
 */
public class CustomWebView extends WebView {
	public CustomWebView(Context context) {
		super(context);
		configureInteraction();
	}

	public CustomWebView(Context context, AttributeSet attributeSet) {
		super(context, attributeSet);
		configureInteraction();
	}

	public CustomWebView(Context context, AttributeSet attributeSet, int defStyleAttr) {
		super(context, attributeSet, defStyleAttr);
		configureInteraction();
	}

	public CustomWebView(Context context, AttributeSet attributeSet, int defStyleAttr, int defStyleRes) {
		super(context, attributeSet, defStyleAttr, defStyleRes);
		configureInteraction();
	}

	@SuppressLint("ClickableViewAccessibility")
	private void configureInteraction() {
		setLongClickable(false);
		setOnLongClickListener(v -> true); // consume long-press; do not show context menu
		// Built-in WebView zoom via pinch is also blocked by multi-touch filtering below.
		getSettings().setSupportZoom(false);
		getSettings().setBuiltInZoomControls(false);
		getSettings().setDisplayZoomControls(false);
	}

	@Override
	public boolean onTouchEvent(MotionEvent event) {
		if (event.getPointerCount() > 1) {
			// End any in-progress single-finger gesture so Chromium does not get stuck.
			MotionEvent cancel = MotionEvent.obtain(event);
			cancel.setAction(MotionEvent.ACTION_CANCEL);
			super.onTouchEvent(cancel);
			cancel.recycle();
			return true; // swallow multi-touch
		}
		return super.onTouchEvent(event);
	}

	@Override
	public boolean performLongClick() {
		return false;
	}
}
