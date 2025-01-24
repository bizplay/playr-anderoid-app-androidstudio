package biz.playr;

public interface IServiceCallbacks {
    // same as using restartActivityWithDelay(false)
    void restartActivityWithDelay();
    // force=true can be used to make sure the restart is performed on all devices
    // force=false makes restart device dependent; not performed on devices where
    // it may lead to restart-loops
    void restartActivityWithDelay(boolean force);
    String getPlayerId();
}