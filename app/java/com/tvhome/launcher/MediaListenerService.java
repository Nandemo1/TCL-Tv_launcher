package com.tvhome.launcher;

import android.service.notification.NotificationListenerService;

/**
 * Grants this launcher access to active media sessions (Spotify et al). The system
 * requires the user (or adb) to allow notification access for this listener; without
 * it MediaSessionManager returns no sessions and the card falls back to static text.
 */
public class MediaListenerService extends NotificationListenerService {
}
