# Notification RemoteViews crash — follow-up fix

## Log-confirmed cause

The supplied Logcat still shows:

- `RemoteViews$ActionException: ... NotificationOptimizedLinearLayout can't use method with RemoteViews: setClickable(boolean)`
- `RemoteServiceException$BadForegroundServiceNotificationException: Couldn't inflate contentViews`

The uploaded project had already removed `android:clickable` / `android:focusable` from the XML notification layouts, but `RecordingService.kt` still contained this runtime call:

`views.setBoolean(R.id.action_secondary, "setClickable", secondaryEnabled)`

That Kotlin call was the remaining source of the same `setClickable(boolean)` RemoteViews action. Removing only the XML attributes did not remove this call, which explains why the crash persisted.

## Changes in this revision

- Removed the `setClickable` and `setEnabled` reflective `RemoteViews.setBoolean` calls.
- The secondary control is now `VISIBLE` only when it has a valid action; otherwise it is `GONE`.
- `setOnClickPendingIntent` is attached only when the secondary action is valid.
- Existing primary action, recording engine, DSP, notification command routing, and XML-defined click targets were otherwise left unchanged.
- Confirmed that neither notification layout contains `android:clickable` or `android:focusable`.
- Confirmed no `RemoteViews.setBoolean(..., "setClickable", ...)` or `RemoteViews.setBoolean(..., "setEnabled", ...)` remains in `RecordingService.kt`.

## Validation and limitations

- ZIP integrity verified.
- Both notification layout XML files parsed successfully.
- Source scan verified the offending reflective setter is absent and all button IDs plus supported `setOnClickPendingIntent` calls remain.
- Full Android Gradle build and physical-device execution were not completed in this environment; these still need to be verified in Android Studio and on the user's devices.

## About startup log lines

`AppHibernationService: Package ... is not installed for any user` is a separate system-server log and is not the fatal exception responsible for this notification crash. The fatal exception present in the supplied trace is the failed RemoteViews inflation above.
