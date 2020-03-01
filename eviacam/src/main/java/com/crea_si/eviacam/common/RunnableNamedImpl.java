package com.crea_si.eviacam.common;

import androidx.annotation.Nullable;

public abstract class RunnableNamedImpl implements RunnableNamed {

    //String mName = "";
    /*
    @Override
    public void setName(String name) {
        mName=name;
    }

    @Override
    public String getName() {
        return mName;
    }

     */

    abstract public String getName();

    @Override
    public boolean equals(@Nullable Object obj) {

        if (obj instanceof RunnableNamed) {
            if (((RunnableNamed)obj).getName() == this.getName()) {
                return true;
            }
        }
        return false;
    }
}
