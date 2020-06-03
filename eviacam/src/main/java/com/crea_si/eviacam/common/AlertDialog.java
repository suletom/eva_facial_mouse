/*
 * Enable Viacam for Android, a camera based mouse emulator
 *
 * Copyright (C) 2015 Cesar Mauri Loba (CREA Software Systems)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

 package com.crea_si.eviacam.common;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.os.Build;
import android.util.Log;
import android.view.Display;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.crea_si.eviacam.R;
import com.crea_si.eviacam.util.PhysicalOrientation;

/***
 * Root relative layout which is overlaid over the entire screen of the device an to which
 * other views can be added.
 */
public class AlertDialog   {

    public static final int ALERTDIALOG_POSITIVERESULT = 1;
    public static final int ALERTDIALOG_NEGATIVERESULT = 0;

    public static final int ALERTDIALOG_CHECKBOX = 1;

    private View rl=null;
    private Context ct;

    private BroadcastReceiver mAlertReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            // Get extra data included in the Intent
            String pb = intent.getStringExtra("positivebuttontext");
            String nb = intent.getStringExtra("negativebuttontext");
            String text = intent.getStringExtra("text");
            String checkboxtext = intent.getStringExtra("checkboxtext");
            int checkbox = intent.getIntExtra("checkbox",0);
            String title = intent.getStringExtra("title");
            int uid = intent.getIntExtra("uid",0);

            Log.d(EVIACAM.TAG, "AlertDialog: mAlertReceiver!");

            showAlert(uid,title,text,pb,nb,checkboxtext,checkbox);
        }
    };

    AlertDialog(Context c) {
        //super(c);
        ct=c;

        Log.d(EVIACAM.TAG, "AlertDialog: registerreciever!");
        LocalBroadcastManager.getInstance(c).registerReceiver(
                mAlertReceiver, new IntentFilter("alertdialog"));


        rl = LayoutInflater.from(c).inflate(R.layout.alertdialog, null);

        WindowManager.LayoutParams feedbackParams = new WindowManager.LayoutParams();

        feedbackParams.type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY;
        //feedbackParams.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
        //                       WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        //
        feedbackParams.gravity = Gravity.TOP;
        feedbackParams.format = PixelFormat.TRANSLUCENT;

        WindowManager wm = (WindowManager) c.getSystemService(Context.WINDOW_SERVICE);

        rl.setVisibility(View.INVISIBLE);
        rl.setFocusable(false);
        rl.setClickable(false);

        wm.addView(rl, feedbackParams);

    }



    public void setInvisible(Context c){

        rl.setVisibility(View.INVISIBLE);
        rl.setFocusable(false);
        rl.setClickable(false);

    }

    public void setVisible(Context c){

        rl.getRootView().setVisibility(View.VISIBLE);
        rl.setFocusable(true);
        rl.setClickable(true);

    }

    public void cleanup(){
        WindowManager wm= (WindowManager) this.ct.getSystemService(Context.WINDOW_SERVICE);
        wm.removeViewImmediate(rl);

        LocalBroadcastManager.getInstance(ct).unregisterReceiver(mAlertReceiver);

        Log.d(EVIACAM.TAG, "AlertDialog: finish AlertDialog");
    }

    public void showAlert(int uid,String title,String text, String pb,String nb,String chtext,int checkbox) {


        Log.d(EVIACAM.TAG, "AlertDialog: showalert func!!!");

        TextView tv = rl.findViewById(R.id.dialog_tv);
        TextView ti = rl.findViewById(R.id.dialog_ti);
        CheckBox ch = rl.findViewById(R.id.checkbox);
        Button pob = rl.findViewById(R.id.dialog_positive_btn);
        Button nob = rl.findViewById(R.id.dialog_neutral_btn);



        ti.setText(title);
        tv.setText(text);
        if (chtext=="" || chtext==null) {
            ch.setVisibility(View.INVISIBLE);
        } else {
            ch.setText(chtext);
            ch.setVisibility(View.VISIBLE);
            if (checkbox==1) {
                ch.setChecked(true);
            }
        }

        if (pb == "" || pb==null) {
            pob.setVisibility(View.INVISIBLE);
        } else {
            pob.setText(pb);
            pob.setVisibility(View.VISIBLE);
        }

        if (nb == "" || nb==null) {
            nob.setVisibility(View.INVISIBLE);
        } else {
            nob.setText(nb);
            nob.setVisibility(View.VISIBLE);
        }

        pob.setOnClickListener(new Button.OnClickListener() {
            public void onClick(View v) {

                Log.d(EVIACAM.TAG, "AlertDialog: Pob onclick!");
                //Do stuff here
                setInvisible(ct);

                Intent inte = new Intent("alertdialogreply");
                inte.putExtra("uid",uid);
                inte.putExtra("result",ALERTDIALOG_POSITIVERESULT);
                inte.putExtra("checkbox",ch.isChecked()?ALERTDIALOG_CHECKBOX:0);
                LocalBroadcastManager.getInstance(ct).sendBroadcast(inte);
            }
        });

        nob.setOnClickListener(new Button.OnClickListener() {
            public void onClick(View v) {

                Log.d(EVIACAM.TAG, "AlertDialog: Nb onclick!");

                //Do stuff here
                setInvisible(ct);

                Intent inte = new Intent("alertdialogreply");
                inte.putExtra("uid",uid);
                inte.putExtra("result",ALERTDIALOG_NEGATIVERESULT);
                inte.putExtra("checkbox",ch.isChecked()?ALERTDIALOG_CHECKBOX:0);
                LocalBroadcastManager.getInstance(ct).sendBroadcast(inte);
            }
        });

        setVisible(ct);

    }

}





