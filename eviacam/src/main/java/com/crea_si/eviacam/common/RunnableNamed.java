package com.crea_si.eviacam.common;

public interface RunnableNamed extends Runnable{

    @Override
    void run();

    String getName();

    boolean equals(Object o);

}
