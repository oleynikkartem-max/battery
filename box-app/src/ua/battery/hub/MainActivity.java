package ua.battery.hub;

import android.app.Activity;
import android.os.Bundle;

/** Invisible: only starts the service (used once after install, so boot broadcasts reach the app). */
public class MainActivity extends Activity {
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        HubService.start(this);
        finish();
    }
}
