package org.codepond.wizardroid.infrastructure;

import android.util.Pair;

import java.util.LinkedList;
import java.util.ListIterator;

public class Bus {
    private static final Bus sInstance = new Bus();
    private static LinkedList<Pair<Subscriber, Class<?>>> sSubscribers = new LinkedList<>();

    private Bus() {
    }

    public static Bus getInstance() {
        return sInstance;
    }

    public void post(Object event) {
        Class messageType = event.getClass();
        for (Pair<Subscriber, Class<?>> entry : sSubscribers) {
            if (entry.second == messageType) {
                entry.first.receive(event);
            }
        }
    }

    public void register(Subscriber subscriber, Class<?> eventType) {
        for (Pair<Subscriber, Class<?>> entry : sSubscribers) {
            if (entry.first == subscriber && entry.second== eventType) return;
        }
        sSubscribers.add(new Pair<Subscriber, Class<?>>(subscriber, eventType));
    }

    public void unregister(Subscriber subscriber) {
        ListIterator<Pair<Subscriber, Class<?>>> iter= sSubscribers.listIterator();

        while (iter.hasNext()) {
            Pair<Subscriber, Class<?>> elem= iter.next();
            if (elem.first== subscriber) {
                iter.remove();
            }
        }
    }
}
