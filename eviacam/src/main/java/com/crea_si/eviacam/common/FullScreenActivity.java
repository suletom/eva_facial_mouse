package com.crea_si.eviacam.common;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.crea_si.eviacam.R;

public class FullScreenActivity extends Activity {

    public static final String TAG = "FullScreenActivity";

    private CountDownTimer mCdt = null;
    private TextView mTxt = null;
    private Button mBtn = null;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.fullscreen_activity);

            mTxt=findViewById(R.id.cdtv);
            mBtn=findViewById(R.id.cbtn);
            mBtn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    mCdt.cancel();
                    finish();
                }
            });

            mCdt = new CountDownTimer(10000, 1000) {

                public void onTick(long millisUntilFinished) {
                    if (mTxt!=null) {
                        mTxt.setText(String.valueOf(millisUntilFinished / 1000));
                    }

                }

                public void onFinish() {
                    Log.d(EVIACAM.TAG+"->"+TAG, "Counter finished!");
                    if (mTxt!=null) {
                        mTxt.setText("");
                    }
                    onTimerEnd();
                }
            };




    }

    @Override
    protected void onStart() {
        super.onStart();
        Log.d(EVIACAM.TAG+"->"+TAG, "OnResume:  starting counter!");
        mCdt.start();
    }

    private void onTimerEnd(){
        Intent intent = new Intent("restartcamera");
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
    }


    @Override
    protected void onResume() {


        super.onResume();
    }


}
