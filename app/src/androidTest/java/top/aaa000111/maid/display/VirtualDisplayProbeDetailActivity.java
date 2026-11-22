package top.aaa000111.maid.display;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;

/** Checks app-initiated navigation to an Activity that does not opt in to embedding. */
public final class VirtualDisplayProbeDetailActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        View probe = new View(this);
        probe.setBackgroundColor(Color.CYAN);
        setContentView(probe);
    }
}
