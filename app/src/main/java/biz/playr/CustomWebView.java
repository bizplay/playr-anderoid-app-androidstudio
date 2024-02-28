package biz.playr;

import android.content.Context;
import android.util.AttributeSet;
import android.util.Log;
import android.view.DragEvent;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.webkit.WebView;

public class CustomWebView extends WebView {
    private static final String className = "biz.playr.CustomWebView";
    Context myContext;
//    GestureDetector gestureDetector;
//    GestureDetector.SimpleOnGestureListener simpleOnGestureListener = new GestureDetector.SimpleOnGestureListener() {
//        public boolean onDown(MotionEvent motionEvent) {
//            return true;
//        }
//        public boolean onFling(MotionEvent motionEvent1, MotionEvent motionEvent2, float velocityX, float velocityY) {
//            if (motionEvent1.getRawX() > motionEvent2.getRawX() && StrictMath.abs(motionEvent1.getRawY()-motionEvent2.getRawY())<100) {
//                Log.i(className, "SimpleOnGestureListener.onFling: swipe left");
//            } else if(motionEvent1.getRawX() < motionEvent2.getRawX() && StrictMath.abs(motionEvent1.getRawY()-motionEvent2.getRawY())<100){
//                Log.i(className, "SimpleOnGestureListener.onFling: swipe right");
//            } else {
//                //CustomWebView.pageUp(true); /*can't work, as explained above*/
//            }
//            return true;
//        }
//    };
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
                            Log.i(className, "onTouch: fingerState != FINGER_DRAGGING, return true");
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
//        gestureDetector = new GestureDetector(context, simpleOnGestureListener);
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
    public CustomWebView(Context context, AttributeSet attributeSet, int defStyleAttr, int defStyleRes)
    {
        super(context, attributeSet, defStyleAttr, defStyleRes);
        myContext=context;
        this.setLongClickable(false);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
//        return (gestureDetector.onTouchEvent(event) || super.onTouchEvent(event));
        return (onTouchListener.onTouch(this, event) || super.onTouchEvent(event));
    }

    @Override
    public boolean performLongClick() {
        Log.i(className, "override performLongClick");
        return false;
    };

    @Override
    public boolean performClick() {
        Log.i(className, "override performClick");
        return super.performClick();
    }
    @Override
    public boolean onDragEvent(DragEvent dragEvent) {
        Log.i(className, "override onDragEvent");
        return false;
    }
}
