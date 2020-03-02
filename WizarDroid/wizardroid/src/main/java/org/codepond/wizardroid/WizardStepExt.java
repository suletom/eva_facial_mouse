package org.codepond.wizardroid;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import androidx.annotation.NonNull;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;

/**
 * Simplify implementation of wizard steps
 */
public abstract class WizardStepExt extends WizardStep {
    private static final String PARAM_ENTERED = "wizard_step_entered";

    private static final int BUTTON_DELAY_MS = 500;

    private boolean mEntered = false;   // true when the user is in this wizard

    private boolean mInitialized = false;

    @NonNull
    private final Handler mHandler = new Handler();

    public WizardStepExt() { }

    @Override
    final public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        if (null != savedInstanceState) {
            mEntered = savedInstanceState.getBoolean(PARAM_ENTERED, false);
        }
        return doOnCreateView(inflater, container, savedInstanceState);
    }

    public boolean isInitialized() {
        return mInitialized;
    }

    @Override
    public void onEnter() {
        mEntered = true;
        if (!mInitialized) {
            hideButtons();
            initialize();
            mInitialized = true;
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        if (mEntered && !mInitialized) {
            hideButtons();
            initialize();
            mInitialized = true;
        }
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean(PARAM_ENTERED, mEntered);
    }

    @Override
    public void onPause() {
        super.onPause();
        if (mInitialized) {
            mInitialized = false;
            showButtons.run();
            deinitialize();
        }
    }

    @Override
    public void onExitStep() {
        if (mInitialized) {
            mInitialized = false;
            showButtons.run();
            deinitialize();
        }
        mEntered = false;
    }

    private void hideButtons() {
        Activity a = getActivity();
        if (null != a) {
            Button button = a.findViewById(R.id.wizard_next_button);
            button.setVisibility(View.INVISIBLE);

            button = a.findViewById(R.id.wizard_previous_button);
            button.setVisibility(View.INVISIBLE);

            mHandler.postDelayed(showButtons, BUTTON_DELAY_MS);
        }
    }

    private Runnable showButtons = new Runnable() {
        @Override
        public void run() {
            mHandler.removeCallbacks(showButtons);

            Activity a = getActivity();
            if (null != a) {
                Button button = a.findViewById(R.id.wizard_next_button);
                button.setVisibility(View.VISIBLE);

                button = a.findViewById(R.id.wizard_previous_button);
                button.setVisibility(View.VISIBLE);
            }
        }
    };

    protected abstract View doOnCreateView(@NonNull LayoutInflater inflater, ViewGroup container,
                                           Bundle savedInstanceState);

    protected abstract void initialize();

    protected abstract void deinitialize();
}
