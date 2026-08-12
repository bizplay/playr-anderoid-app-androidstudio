package biz.playr;

public interface IServiceCallbacks {
    // Immediate in-process restart while MainActivity is still in the foreground.
    void restartActivity();
    // force=true can be used to make sure the restart is performed on all devices
    // force=false makes restart device dependent; not performed on devices where
    // it may lead to restart-loops
    void restartActivity(boolean force);
    String getPlayerId();
}
