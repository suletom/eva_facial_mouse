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

 // http://stackoverflow.com/questions/20758986/android-preferenceactivity-dialog-with-number-mPicker
// http://stackoverflow.com/questions/2695646/declaring-a-custom-android-ui-element-using-xml
// https://github.com/TonicArtos/Otago-Linguistics-Experiments/blob/master/SPRE/src/com/michaelnovakjr/numberpicker/NumberPickerPreference.java
// https://gist.github.com/thom-nic/959884

package com.crea_si.eviacam.util;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.preference.DialogPreference;
import android.preference.Preference;
import android.util.AttributeSet;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.crea_si.eviacam.R;

/**
 * A {@link Preference} that displays a number picker as a dialog.
 */
public class KeyPickerPreference extends DialogPreference {

    private static final String TAG = "KeyPickerPreference";
    private int mValue;
    private TextView mTxtMsg;
    private TextView mTxt;
    private TextView mBtn;

    private CharSequence mTitle;

    @SuppressWarnings("WeakerAccess")
    public KeyPickerPreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);

        LocalBroadcastManager.getInstance(getContext()).registerReceiver(
                mMessageReceiver, new IntentFilter("KeyEvent"));

        if (attrs== null) return;

        mTitle= this.getTitle();
        mValue=0;

        mTxt = new TextView(getContext());

    }

    @SuppressWarnings("unused")
    public KeyPickerPreference(Context context, AttributeSet attrs) {
        this(context, attrs, android.R.attr.dialogPreferenceStyle);
    }

    @Override
    protected void onBindDialogView(View view) {

        super.onBindDialogView(view);
        setCode(String.valueOf(getValue()));

        setTmpKeyBlock(true);
    }

    @Override
    protected View onCreateDialogView() {

        LinearLayout layout = new LinearLayout(getContext());
        layout.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        layout.setOrientation(LinearLayout.VERTICAL);

        mTxtMsg = new TextView(getContext());

        mTxtMsg.setText(R.string.settings_key_click_dialog_text);
        mTxtMsg.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);

        mTxt.setText(String.valueOf(mValue).equals("0")?"":String.valueOf(mValue));
        mTxt.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);

        layout.addView(mTxtMsg);

        if (mTxt.getParent()!=null){
            ((ViewGroup)mTxt.getParent()).removeView(mTxt);
        }
        layout.addView(mTxt);

        mBtn = new Button(getContext());
        mBtn.setText(R.string.settings_key_click_dialog_btntext);
        mBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                mValue=0;
                setCode("0");
            }
        });
        layout.addView(mBtn);

        setTmpKeyBlock(true);


        return layout;
    }

    private void setTmpKeyBlock(Boolean enable){
        Intent intent = new Intent("BlockAllKey");
        // You can also include some extra data.
        intent.putExtra("enable", enable);
        LocalBroadcastManager.getInstance(getContext()).sendBroadcast(intent);
        Log.d("evia","allkeybloxk:"+String.valueOf(enable));
    }


    private void setCode(String s){
        String str = getContext().getResources().getString(R.string.settings_key_click_dialog_got)+ (s.equals("0")?"-":s);
        mTxt.setText(str);
    }

    private BroadcastReceiver mMessageReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            // Get extra data included in the Intent
            int kc = intent.getIntExtra("keycode",0);
            int ka = intent.getIntExtra("keyaction",0);

            //Log.d("key"," val:"+String.valueOf(kc));
            if (ka==KeyEvent.ACTION_UP) {
                mValue = kc;
                setCode(String.valueOf(kc));

            }
        }
    };

    @Override
    protected void onDialogClosed(boolean positiveResult) {

        if (positiveResult) {
            setValue(mValue);
        }

        setTmpKeyBlock(false);

    }

    @Override
    protected void onSetInitialValue(boolean restorePersistedValue, Object defaultValue) {

        if (restorePersistedValue)
            setValue(getPersistedInt(0));
        else
            setValue(0);
    }

    public void setValue(int value) {
        OnPreferenceChangeListener listener= getOnPreferenceChangeListener();
        boolean update= true;
        if (null != listener) {
            update= listener.onPreferenceChange(this, value);
        }

        if (update) {
            mValue = value;
            setTitle(mTitle + ": " + Integer.toString(value));
            persistInt(mValue);
        }

    }

    public int getValue() {
        return mValue;
    }



}