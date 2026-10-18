package com.esfle.glassesmode;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.util.TypedValue;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Setup screen: shows whether the accessibility service is on and links to its settings. */
public class MainActivity extends Activity {
    private TextView status;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        int pad = dp(24);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad * 2, pad, pad);

        TextView title = new TextView(this);
        title.setText("Glasses Mode");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
        root.addView(title);

        TextView help = new TextView(this);
        help.setText("With your glasses plugged in, double-tap either volume button. "
                + "The phone locks to landscape, its own screen goes black and ignores touches, while the glasses keep working.\n\n"
                + "Double-tap again, or unplug the glasses, to undo. Single taps and holding still change the volume, "
                + "and without glasses the buttons work as normal.");
        help.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        help.setPadding(0, dp(12), 0, dp(20));
        root.addView(help);

        status = new TextView(this);
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        status.setPadding(0, 0, 0, dp(20));
        root.addView(status);

        Button a11y = new Button(this);
        a11y.setText("Open accessibility settings");
        a11y.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        root.addView(a11y);

        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        GlassesService svc = GlassesService.instance;
        StringBuilder sb = new StringBuilder();
        sb.append(svc != null ? "✓  " : "✗  ").append("Accessibility service on\n");
        if (svc != null) {
            sb.append("\nGlasses: ").append(svc.isGlassesConnected() ? "connected" : "not connected");
            sb.append("\nGlasses mode: ").append(svc.isActive() ? "ON" : "off");
        } else {
            sb.append("\nTurn on \"Glasses Mode\" in accessibility settings.");
        }
        status.setText(sb.toString());
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
