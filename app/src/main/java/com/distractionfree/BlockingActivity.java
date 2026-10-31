package com.distractionfree;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.TextView;

public class BlockingActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        TextView message = new TextView(this);
        message.setText(R.string.blocking_message);
        message.setGravity(Gravity.CENTER);
        message.setTextSize(22);
        message.setPadding(32, 32, 32, 32);
        setContentView(message);
    }
}
