package biz.playr;

import android.content.Context;
import android.os.Build;
import android.util.AttributeSet;
import android.util.Log;
import android.view.DragEvent;
import android.view.MotionEvent;
import android.view.View;
import android.webkit.WebView;

import androidx.annotation.RequiresApi;

public class CustomWebView extends WebView {
    private static final String className = "biz.playr.CustomWebView";
    Context myContext;
    View.OnTouchListener onTouchListener = new View.OnTouchListener() {
        public final static int FINGER_RELEASED = 0;
        public final static int FINGER_TOUCHED = 1;
        public final static int FINGER_DRAGGING = 2;
        public final static int FINGER_UNDEFINED = 3;
        private int fingerState = FINGER_RELEASED;

        @Override
        public boolean onTouch(View view, MotionEvent motionEvent) {
            Log.i(className, "override onTouch (webView onTouchListener)");
            if (motionEvent.getPointerCount() > 1) {
                fingerState = FINGER_UNDEFINED;
                Log.i(className, "onTouch: multiple fingers detected, reset fingerState");
            } else {
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
                        if (fingerState != FINGER_DRAGGING) {
                            fingerState = FINGER_RELEASED;
                            // handle click/touch here
                            boolean result = performClick();
                            Log.i(className, "onTouch: fingerState != FINGER_DRAGGING, result performClick: " + result + ", return true");
                            return true;
                        } else if (fingerState == FINGER_DRAGGING) {
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
            }
            Log.i(className, "onTouch: return false");
            return false;
        }
    };

    public CustomWebView(Context context) {
        super(context);

        this.myContext = context;
        this.setLongClickable(false);
    }
    public CustomWebView(Context context, AttributeSet attributeSet) {
        super(context, attributeSet);
        myContext=context;
        this.setLongClickable(false);
    }
    public CustomWebView(Context context, AttributeSet attributeSet, int defStyleAttr) {
        super(context, attributeSet, defStyleAttr);
        myContext=context;
        this.setLongClickable(false);
    }
    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    public CustomWebView(Context context, AttributeSet attributeSet, int defStyleAttr, int defStyleRes)
    {
        super(context, attributeSet, defStyleAttr, defStyleRes);
        myContext=context;
        this.setLongClickable(false);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        Log.i(className, "override onTouchEvent, delegate to onTouch or super");
        return (onTouchListener.onTouch(this, event) || super.onTouchEvent(event));
    }

    @Override
    public boolean performLongClick() {
        Log.i(className, "override performLongClick, return false");
        return false;
    };

    @Override
    public boolean performClick() {
        Log.i(className, "override performClick, delegate to super");
        return super.performClick();
    }
    @Override
    public boolean onDragEvent(DragEvent dragEvent) {
        Log.i(className, "override onDragEvent, return false");
        return false;
    }
}
