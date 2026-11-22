package com.wangzan101.mika.shell;

import android.view.Surface;

// Narrow protocol-compatible subset of Mika's IMikaShell. Transaction IDs are preserved.
interface IMikaShell {
    int createDisplay(in Surface surface, int width, int height, int dpi) = 1;
    void releaseDisplay(int displayId) = 2;
    int startApp(int displayId, String packageName) = 3;
    int topTaskId(int displayId) = 4;
    boolean moveTask(int taskId, int displayId) = 5;
    boolean injectKey(int displayId, int keyCode) = 6;
    boolean injectTap(int displayId, int x, int y) = 7;
    int displayOfTask(int taskId) = 12;
    String packageOfTask(int taskId) = 13;
    String packageOfTask(int taskId) = 13;
    int startView(int displayId, String action, String uri, String packageName) = 17;
    boolean injectStroke(int displayId, in int[] xs, in int[] ys, in long[] times) = 34;
    void finishDisplay(int displayId, in String[] keep, in String[] close) = 48;
    int injectAsciiText(int displayId, int taskId, String packageName, String text) = 50;
    boolean resizeDisplay(int displayId, in Surface surface, int width, int height, int dpi) = 62;
    int[] identity() = 21;
    void releaseAll() = 22;
}
