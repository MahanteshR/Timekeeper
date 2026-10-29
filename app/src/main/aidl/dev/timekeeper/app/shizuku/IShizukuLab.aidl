package dev.timekeeper.app.shizuku;

/** Narrow manual-test interface. No arbitrary command, file, or Binder proxy endpoint. */
interface IShizukuLab {
    void destroy() = 16777114;
    String configure(in String[] packages) = 1;
    String inspect(String packageName) = 2;
    String suspendTrial(String packageName, String token) = 3;
    String release(String packageName, String token) = 4;
    String stopTrial(String packageName, boolean force) = 5;
    String observe() = 6;
    String report() = 7;
}
