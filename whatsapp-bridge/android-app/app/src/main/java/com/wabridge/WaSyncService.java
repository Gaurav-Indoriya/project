package com.wabridge;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.IBinder;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Keeps polling the bridge server for new messages independent of which
 * Activity (if any) is on screen. Persists everything to DBHelper,
 * broadcasts updates so open Activities can refresh live, and posts a
 * notification for messages that arrive in a chat that isn't currently open.
 *
 * lastTs is persisted to SharedPreferences (not just kept in memory) so that
 * if Android kills this Service in the background and it gets restarted
 * later, it resumes from where it left off instead of re-fetching the
 * server's entire history - that full resync was what caused both the
 * "status stuck on Connecting" bug and deleted chats reappearing.
 *
 * Every cycle (#6, every 5s) this also polls /status for the real WhatsApp
 * connection state (ready/qr/connecting/disconnected) - not just "did the
 * last request succeed" - and broadcasts it so the UI reflects reality (#1).
 */
public class WaSyncService extends Service {

    public static final String ACTION_UPDATE = "com.wabridge.MESSAGES_UPDATED";
    public static final String ACTION_STATUS = "com.wabridge.SYNC_STATUS";
    public static final String EXTRA_STATUS = "status"; // "ok" | "error" (server reachability)
    public static final String EXTRA_STATE = "state";   // "ready" | "qr" | "connecting" | "disconnected" | "offline"

    /** Set by whichever chat Activity is currently visible, so we skip its notification (#8). */
    public static volatile String activeChatJid = null;

    private static final long POLL_INTERVAL_MS = 5000; // #6 - refresh every 5 seconds
    private static final String PREFS = "wa_bridge";
    private static final String PREF_LAST_TS = "sync_last_ts";

    private Handler handler = new Handler();
    private boolean running = false;
    private long lastTs = 0;
    private String server;
    private String token;
    private SharedPreferences prefs;

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        server = prefs.getString("server", "");
        token = prefs.getString("token", "");
        // Resume from the last known sync point instead of re-fetching everything.
        lastTs = prefs.getLong(PREF_LAST_TS, 0);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!running) {
            running = true;
            handler.post(pollRunnable);
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        handler.removeCallbacks(pollRunnable);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private Runnable pollRunnable = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            new Thread(new Runnable() {
                @Override
                public void run() {
                    String state = fetchStatus();
                    boolean ok = poll();
                    broadcastStatus(ok, state);
                }
            }).start();
            handler.postDelayed(this, POLL_INTERVAL_MS);
        }
    };

    /** Hits /status for the real WhatsApp connection state (#1). */
    private String fetchStatus() {
        if (server == null || server.isEmpty()) return "offline";
        try {
            URL url = new URL(server + "/status");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            conn.setRequestProperty("X-Device-Token", token);
            if (conn.getResponseCode() != 200) return "offline";

            BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
            reader.close();

            JSONObject json = new JSONObject(sb.toString());
            return json.optString("state", "disconnected");
        } catch (Exception e) {
            return "offline";
        }
    }

    private boolean poll() {
        if (server == null || server.isEmpty()) return false;
        try {
            URL url = new URL(server + "/messages?since=" + lastTs);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            conn.setRequestProperty("X-Device-Token", token);
            if (conn.getResponseCode() != 200) return false;

            BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
            reader.close();

            JSONObject json = new JSONObject(sb.toString());
            long serverTs = json.getLong("serverTs");
            JSONArray arr = json.getJSONArray("messages");

            DBHelper db = DBHelper.get(this);
            boolean anyChange = false;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject m = arr.getJSONObject(i);
                String id = m.getString("id");
                String from = m.getString("from");

                // WhatsApp Status is never shown, downloaded, or stored (#3).
                if (DBHelper.STATUS_BROADCAST_JID.equals(from)) continue;

                boolean fromMe = m.getBoolean("fromMe");
                String text = m.getString("text");
                long ts = m.getLong("ts");
                String pushName = m.optString("pushName", "");
                String mediaType = m.isNull("mediaType") ? null : m.optString("mediaType", null);
                String mediaUrl = m.isNull("mediaUrl") ? null : m.optString("mediaUrl", null);

                boolean isChatOpen = from.equals(activeChatJid);
                boolean shouldIncrementUnread = !fromMe && !isChatOpen;

                // saveIncomingOrOutgoing returns false if the message was skipped
                // (duplicate id, or belongs to a chat deleted after this message's ts).
                boolean inserted = db.saveIncomingOrOutgoing(id, from, fromMe, text, ts, pushName,
                        shouldIncrementUnread, mediaType, mediaUrl);

                if (inserted) {
                    anyChange = true;
                    if (shouldIncrementUnread) {
                        String sender = (pushName != null && !pushName.isEmpty()) ? pushName : from.split("@")[0];
                        showNotification(from, sender, text);
                    }
                }
            }
            if (serverTs > lastTs) {
                lastTs = serverTs;
                prefs.edit().putLong(PREF_LAST_TS, lastTs).commit();
            }

            if (anyChange) {
                Intent update = new Intent(ACTION_UPDATE);
                sendBroadcast(update);
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void broadcastStatus(boolean serverOk, String waState) {
        Intent i = new Intent(ACTION_STATUS);
        i.putExtra(EXTRA_STATUS, serverOk ? "ok" : "error");
        i.putExtra(EXTRA_STATE, waState);
        sendBroadcast(i);
    }

    private void showNotification(String chatJid, String sender, String text) {
        Intent intent = new Intent(this, ChatActivity.class);
        intent.putExtra("jid", chatJid);
        intent.putExtra("name", sender);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, chatJid.hashCode(), intent,
                PendingIntent.FLAG_UPDATE_CURRENT);

        Notification notification = new Notification.Builder(this)
                .setSmallIcon(android.R.drawable.sym_action_chat)
                .setContentTitle(sender)
                .setContentText(text)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setDefaults(Notification.DEFAULT_SOUND | Notification.DEFAULT_VIBRATE)
                .build();

        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        // Same notifId per chat so multiple messages from one contact stack/update
        // instead of spamming separate notifications (#8 - avoid duplicates, group by contact).
        nm.notify(chatJid.hashCode(), notification);
    }
}