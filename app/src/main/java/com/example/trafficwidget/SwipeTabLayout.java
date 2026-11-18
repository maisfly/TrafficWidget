package com.example.trafficwidget;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.widget.LinearLayout;

/**
 * The white card of the traffic page. It turns a horizontal swipe into a tab change while
 * leaving taps (bars, the app-details row) and vertical scrolling untouched.
 *
 * <p>Tracking happens in both {@link #onInterceptTouchEvent} and {@link #onTouchEvent}: the
 * day chart consumes touches, so its swipe has to be intercepted from the parent, while the
 * week / month chart does not consume them and the card itself receives the whole gesture.
 * A swipe is recognised once the horizontal movement exceeds twice the touch slop and is
 * clearly more horizontal than vertical; a pressed child then gets ACTION_CANCEL, so a swipe
 * that starts on a bar never selects it.</p>
 */
public class SwipeTabLayout extends LinearLayout {

    public interface OnSwipeListener {
        /** {@code -1} = previous tab, {@code +1} = next tab. */
        void onSwipe(int direction);
    }

    private final int touchSlop;
    private float downX;
    private float downY;
    private boolean tracking;
    private boolean swiping;
    private OnSwipeListener listener;

    public SwipeTabLayout(Context context) {
        this(context, null);
    }

    public SwipeTabLayout(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public SwipeTabLayout(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
    }

    public void setOnSwipeListener(OnSwipeListener listener) {
        this.listener = listener;
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                startTracking(event);
                return false;
            case MotionEvent.ACTION_MOVE:
                if (tracking && !swiping && isHorizontalSwipe(event)) {
                    startSwipe();
                    return true;
                }
                return false;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                tracking = false;
                return false;
            default:
                return false;
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                startTracking(event);
                return true;
            case MotionEvent.ACTION_MOVE:
                if (tracking && !swiping && isHorizontalSwipe(event)) {
                    startSwipe();
                }
                return true;
            case MotionEvent.ACTION_UP:
                if (swiping && listener != null) {
                    float dx = event.getX() - downX;
                    if (Math.abs(dx) > touchSlop * 3) {
                        listener.onSwipe(dx < 0 ? 1 : -1);
                    }
                }
                swiping = false;
                tracking = false;
                return true;
            case MotionEvent.ACTION_CANCEL:
                swiping = false;
                tracking = false;
                return true;
            default:
                return true;
        }
    }

    private void startTracking(MotionEvent event) {
        downX = event.getX();
        downY = event.getY();
        tracking = true;
        swiping = false;
    }

    private void startSwipe() {
        swiping = true;
        // Keep the parent ScrollView from stealing the horizontal gesture mid-way.
        if (getParent() != null) {
            getParent().requestDisallowInterceptTouchEvent(true);
        }
    }

    private boolean isHorizontalSwipe(MotionEvent event) {
        float dx = event.getX() - downX;
        float dy = event.getY() - downY;
        return Math.abs(dx) > touchSlop * 2 && Math.abs(dx) > Math.abs(dy) * 1.5f;
    }
}
