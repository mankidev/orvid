package com.orvid.play;

public interface Frames {
    long duration();

    float aspect();

    void frameAt(long ms, Storyboard.Callback callback);

    default void stop() {
    }
}
