package me.paranoid.wifikill;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Notification;
import android.app.Service;
import android.content.Context;
import android.content.Intent;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.shadows.ShadowNotificationManager;

/**
 * Guards the foreground capture service: the session must be startable through
 * the binder, its state readable from the UI, and a stop must tear it down
 * without leaving a notification behind.
 *
 * <p>Robolectric has no root shell, so the capture process fails immediately
 * and only the state machine is exercised.</p>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class MonitorCaptureServiceTest {

    private ServiceController<MonitorCaptureService> controller;
    private MonitorCaptureService.Session session;
    private ShadowNotificationManager notifications;

    private Context app() {
        return RuntimeEnvironment.getApplication();
    }

    @Before
    public void bind() {
        controller = Robolectric.buildService(MonitorCaptureService.class,
                MonitorCaptureService.intent(app())).create();
        session = (MonitorCaptureService.Session) controller.get().onBind(
                MonitorCaptureService.intent(app()));
        notifications = Shadows.shadowOf((android.app.NotificationManager)
                app().getSystemService(Context.NOTIFICATION_SERVICE));
        SettingsStore.clearCaptureSession(app());
    }

    @After
    public void unbind() {
        session.setListener(null);
        controller.get().onDestroy();
    }

    @Test public void theBinderStartsASessionWithValidatedValues() {
        assertTrue(session.start("wfkmon0", "Ch 6", 100, 512, "/tmp/capture.pcap", "", 2000, false));
        MonitorCaptureService.State state = session.state();
        assertTrue(state.running);
        assertEquals("wfkmon0", state.interfaceName);
        assertEquals("Ch 6", state.channel);
        assertEquals("/tmp/capture.pcap", state.targetPath);
        assertTrue(MonitorCaptureService.isRunning(app()));
    }

    @Test public void anUnsafeInterfaceNameIsRefused() {
        assertFalse(session.start("wlan0;reboot", "Ch 6", 100, 512, "/tmp/x.pcap", "", 2000, false));
        assertFalse(session.state().running);
        assertFalse(session.start("wfkmon0", "Ch 6", 0, 512, "/tmp/x.pcap", "", 2000, false));
        assertFalse(session.state().running);
        assertFalse(session.start("wfkmon0", "Ch 6", 100, 0, "/tmp/x.pcap", "", 2000, false));
    }

    @Test public void aRunningSessionPublishesAPersistentStopNotification() {
        assertTrue(session.start("wfkmon0", "Ch 6", 100, 512, "/tmp/capture.pcap", "", 2000, false));
        Notification notification =
                Shadows.shadowOf((Service) controller.get()).getLastForegroundNotification();
        assertNotNull("startForeground must be called with a notification", notification);
        assertTrue((notification.flags & Notification.FLAG_ONGOING_EVENT) != 0);
        assertNotNull("the notification must offer a stop action", notification.actions);
        assertEquals(1, notification.actions.length);
        assertEquals(app().getString(R.string.capture_notification_stop),
                notification.actions[0].title.toString());
    }

    @Test public void theListenerIsNotifiedWhenTheSessionStartsAndStops() {
        final int[] calls = {0};
        session.setListener(() -> calls[0]++);
        session.start("wfkmon0", "Ch 6", 100, 512, "/tmp/capture.pcap", "", 2000, false);
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        assertTrue("the UI must learn about the new session", calls[0] > 0);
        session.setListener(null);
    }

    @Test public void stoppingEndsTheSession() {
        assertTrue(session.start("wfkmon0", "Ch 6", 100, 512, "/tmp/capture.pcap", "", 2000, false));
        session.stop();
        controller.get().onDestroy();
        assertFalse(MonitorCaptureService.isRunning(app()));
        assertFalse(session.state().running);
    }

    @Test public void channelHoppingIsOffByDefault() {
        assertTrue(session.start("wfkmon0", "Ch 6", 100, 512, "/tmp/capture.pcap", "", 2000, false));
        assertFalse(session.state().hopping);
        assertFalse(session.isChannelHopping());
    }

    @Test public void channelHoppingIsRefusedWithoutTwoChannels() {
        assertTrue(session.start("wfkmon0", "Ch 6", 100, 512, "/tmp/capture.pcap", "", 2000, false));
        assertFalse(session.setChannelHopping(true));
        assertFalse(session.state().hopping);
    }

    @Test public void channelHoppingTogglesWhenChannelsAreSupplied() {
        assertTrue(session.start("wfkmon0", "Ch 6", 100, 512, "/tmp/capture.pcap",
                "6,2437;11,2462", 2000, false));
        assertTrue(session.setChannelHopping(true));
        assertTrue(session.state().hopping);
        assertTrue(session.isChannelHopping());
        assertTrue(session.setChannelHopping(false));
        assertFalse(session.state().hopping);
    }

    @Test public void invalidHopChannelEntriesAreIgnored() {
        assertTrue(session.start("wfkmon0", "Ch 6", 100, 512, "/tmp/capture.pcap",
                "6,2437;bogus;0,0;999,99999", 2000, false));
        // Only the valid entry survives, so hopping cannot be enabled.
        assertFalse(session.setChannelHopping(true));
        assertFalse(session.state().hopping);
    }

    @Test public void hoppingCannotBeEnabledAfterTheSessionStops() {
        assertTrue(session.start("wfkmon0", "Ch 6", 100, 512, "/tmp/capture.pcap",
                "6,2437;11,2462", 2000, false));
        session.stop();
        controller.get().onDestroy();
        assertFalse(session.setChannelHopping(true));
    }

    @Test public void sessionStateSurvivesInTheSettingsStore() {
        assertFalse(SettingsStore.captureSessionActive(app()));
        SettingsStore.setCaptureSession(app(), "wlan0", "wfkmon0", "up", true, "/tmp/a.pcap");
        assertTrue(SettingsStore.captureSessionActive(app()));
        assertEquals("wlan0", SettingsStore.captureSessionInterface(app()));
        assertEquals("wfkmon0", SettingsStore.captureSessionMonitor(app()));
        assertEquals("up", SettingsStore.captureSessionOriginalState(app()));
        assertTrue(SettingsStore.captureSessionCreated(app()));
        assertEquals("/tmp/a.pcap", SettingsStore.captureSessionPath(app()));
        SettingsStore.clearCaptureSession(app());
        assertFalse(SettingsStore.captureSessionActive(app()));
        assertEquals("", SettingsStore.captureSessionInterface(app()));
    }
}
