package top.aaa000111.maid.display;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;

/** Draws a known frame in the standalone test package. */
public final class VirtualDisplayProbeActivity extends Activity {
    private float touchStartX;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        View probe = new View(this);
        probe.setBackgroundColor(Color.BLUE);
        probe.setContentDescription("Maid virtual display probe");
        probe.setFocusableInTouchMode(true);
        probe.setOnTouchListener((view, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                touchStartX = event.getX();
            } else if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                if (event.getX() < 60f && touchStartX < 60f) {
                    startActivity(new Intent(this, VirtualDisplayProbeDetailActivity.class));
                } else {
                    view.setBackgroundColor(event.getX() - touchStartX > 80f ? Color.RED : Color.GREEN);
                }
            }
            return true;
        });
        probe.setOnKeyListener((view, keyCode, event) -> {
            if (keyCode != KeyEvent.KEYCODE_ENTER && keyCode != KeyEvent.KEYCODE_X) {
                return false;
            }
            if (event.getAction() == KeyEvent.ACTION_UP) {
                view.setBackgroundColor(keyCode == KeyEvent.KEYCODE_ENTER ? Color.YELLOW : Color.MAGENTA);
            }
            return true;
        });
        probe.postDelayed(this::finishAndRemoveTask, 30_000L);
        setContentView(probe);
        probe.requestFocus();
    }
}
