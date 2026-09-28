package io.github.aoguai.sesameag;

import android.app.PendingIntent;
import io.github.aoguai.sesameag.ICallback;
import io.github.aoguai.sesameag.IStatusListener;

interface ICommandService {
    void executeCommand(String command, ICallback callback);
    void registerListener(IStatusListener listener);
    void unregisterListener(IStatusListener listener);
    boolean isExecutionAllowed(String userId);
    PendingIntent getPersistentScheduleAlarmIntent(int lane);
}

