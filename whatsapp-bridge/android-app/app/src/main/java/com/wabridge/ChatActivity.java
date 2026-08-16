package com.wabridge;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * One contact's conversation (#5), backed by the local DB that WaSyncService
 * keeps synced in the background. This activity no longer polls the server
 * itself - it just reads the DB and listens for the service's "updated"
 * broadcast, so switching between chats is instant and doesn't restart polling.
 *
 * Media (#15): the server only holds downloaded media briefly in memory as a
 * handoff (it may be on ephemeral free hosting). This activity downloads any
 * media it doesn't have yet straight into the app's own storage, so the
 * phone - not the server - is the permanent copy.
 */
public class ChatActivity extends Activity {

    private String server;
    private String token;
    private String chatJid;
    private String chatName;

    private ListView messageList;
    private MessageAdapter adapter;
    private List<MessageItem> messages = new ArrayList<>();
    private DBHelper db;
    private EditText replyField;
    private TextView emptyText;

    // Tracks message ids we've already kicked off a download for, so we
    // don't start duplicate downloads every time loadMessages() runs.
    private Set<String> downloadsInFlight = new HashSet<>();

    private BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (WaSyncService.ACTION_UPDATE.equals(intent.getAction())) {
                loadMessages();
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_chat);

        SharedPreferences prefs = getSharedPreferences("wa_bridge", MODE_PRIVATE);
        server = prefs.getString("server", "");
        token = prefs.getString("token", "");

        chatJid = getIntent().getStringExtra("jid");
        chatName = getIntent().getStringExtra("name");
        if (chatName == null || chatName.isEmpty()) {
            chatName = chatJid != null ? chatJid.split("@")[0] : "Chat";
        }

        db = DBHelper.get(this);

        messageList = (ListView) findViewById(R.id.messageList);
        replyField = (EditText) findViewById(R.id.replyField);
        emptyText = (TextView) findViewById(R.id.emptyText);
        Button sendButton = (Button) findViewById(R.id.sendButton);
        Button backButton = (Button) findViewById(R.id.backButton);
        TextView titleText = (TextView) findViewById(R.id.chatTitleText);
        titleText.setText(chatName);

        adapter = new MessageAdapter();
        messageList.setAdapter(adapter);

        sendButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String text = replyField.getText().toString().trim();
                if (text.isEmpty()) return;
                replyField.setText("");
                new SendTask().execute(text);
            }
        });

        backButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Tell the service this chat is on screen so it won't notify for it (#8).
        WaSyncService.activeChatJid = chatJid;
        db.markChatRead(chatJid);

        IntentFilter filter = new IntentFilter(WaSyncService.ACTION_UPDATE);
        // Android 13+ requires explicitly declaring whether a context-registered
        // receiver is exported to other apps, or it throws a SecurityException.
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(receiver, filter);
        }
        loadMessages();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (chatJid.equals(WaSyncService.activeChatJid)) {
            WaSyncService.activeChatJid = null;
        }
        unregisterReceiver(receiver);
    }

    private void loadMessages() {
        messages.clear();
        Cursor c = db.getMessagesForChat(chatJid);
        while (c.moveToNext()) {
            MessageItem item = new MessageItem();
            item.id = c.getString(0);
            item.fromMe = c.getInt(1) == 1;
            item.text = c.getString(2);
            item.ts = c.getLong(3);
            item.pushName = c.getString(4);
            item.mediaType = c.getString(5);
            item.mediaUrl = c.getString(6);
            item.mediaLocalPath = c.getString(7);
            messages.add(item);

            // Kick off a local download for any media we don't have yet.
            if (item.mediaUrl != null && !item.mediaUrl.isEmpty()
                    && (item.mediaLocalPath == null || item.mediaLocalPath.isEmpty())
                    && !downloadsInFlight.contains(item.id)) {
                downloadsInFlight.add(item.id);
                new DownloadMediaTask().execute(item.id, item.mediaUrl, item.mediaType);
            }
        }
        c.close();
        adapter.notifyDataSetChanged();
        if (!messages.isEmpty()) {
            messageList.setSelection(messageList.getCount() - 1);
        }
        emptyText.setVisibility(messages.isEmpty() ? View.VISIBLE : View.GONE);
        // Any messages that arrived while this chat is open should stay marked read.
        db.markChatRead(chatJid);
    }

    /** Downloads a media file from the server once and saves it into the app's own storage. */
    private class DownloadMediaTask extends AsyncTask<String, Void, Boolean> {
        String messageId;
        String localFileName;

        @Override
        protected Boolean doInBackground(String... params) {
            messageId = params[0];
            String mediaUrl = params[1];
            String mediaType = params[2];

            try {
                URL url = new URL(server + mediaUrl);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(20000);
                conn.setRequestProperty("X-Device-Token", token);
                if (conn.getResponseCode() != 200) return false;

                String ext = "image".equals(mediaType) ? "jpg"
                        : "video".equals(mediaType) ? "mp4"
                          : "audio".equals(mediaType) ? "ogg"
                            : "bin";
                localFileName = messageId + "." + ext;

                File mediaDir = new File(getFilesDir(), "media");
                if (!mediaDir.exists()) mediaDir.mkdirs();
                File outFile = new File(mediaDir, localFileName);

                InputStream in = conn.getInputStream();
                OutputStream out = new FileOutputStream(outFile);
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                }
                out.close();
                in.close();

                db.setMediaLocalPath(messageId, outFile.getAbsolutePath());
                return true;
            } catch (Exception e) {
                return false;
            }
        }

        @Override
        protected void onPostExecute(Boolean success) {
            downloadsInFlight.remove(messageId);
            if (success != null && success) {
                loadMessages();
            }
        }
    }

    private class SendTask extends AsyncTask<String, Void, String> {
        String sentText;

        @Override
        protected String doInBackground(String... params) {
            sentText = params[0];
            try {
                URL url = new URL(server + "/send");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(10000);
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("X-Device-Token", token);
                conn.setDoOutput(true);
                String body = new JSONObject().put("to", chatJid).put("text", sentText).toString();
                OutputStream os = conn.getOutputStream();
                os.write(body.getBytes("UTF-8"));
                os.close();
                if (conn.getResponseCode() != 200) return "Send failed: " + conn.getResponseCode();

                BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) sb.append(line);
                reader.close();
                JSONObject json = new JSONObject(sb.toString());
                if (json.optBoolean("success")) {
                    String id = json.optString("id", UUID.randomUUID().toString());
                    // fromMe messages never bump unread count for ourselves, and have no media.
                    db.saveIncomingOrOutgoing(id, chatJid, true, sentText,
                            System.currentTimeMillis(), "", false, null, null);
                    return null;
                }
                return "Send failed";
            } catch (Exception e) {
                return "Error: " + e.getMessage();
            }
        }

        @Override
        protected void onPostExecute(String result) {
            if (result != null) {
                Toast.makeText(ChatActivity.this, result, Toast.LENGTH_SHORT).show();
            } else {
                loadMessages();
            }
        }
    }

    static class MessageItem {
        String id;
        boolean fromMe;
        String text;
        long ts;
        String pushName;
        String mediaType;
        String mediaUrl;
        String mediaLocalPath;
    }

    private class MessageAdapter extends BaseAdapter {
        @Override
        public int getCount() { return messages.size(); }
        @Override
        public Object getItem(int position) { return messages.get(position); }
        @Override
        public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            MessageItem m = messages.get(position);
            if (convertView == null) {
                convertView = getLayoutInflater().inflate(R.layout.message_item, parent, false);
            }
            TextView senderText = (TextView) convertView.findViewById(R.id.senderText);
            TextView bodyText = (TextView) convertView.findViewById(R.id.bodyText);
            TextView timeText = (TextView) convertView.findViewById(R.id.timeText);
            ImageView mediaImage = (ImageView) convertView.findViewById(R.id.mediaImage);
            LinearLayout container = (LinearLayout) convertView.findViewById(R.id.messageContainer);

            senderText.setText(m.fromMe ? "You" : chatName);

            boolean hasLocalImage = "image".equals(m.mediaType) && m.mediaLocalPath != null && !m.mediaLocalPath.isEmpty();
            if (hasLocalImage) {
                Bitmap bmp = BitmapFactory.decodeFile(m.mediaLocalPath);
                if (bmp != null) {
                    mediaImage.setImageBitmap(bmp);
                    mediaImage.setVisibility(View.VISIBLE);
                } else {
                    mediaImage.setVisibility(View.GONE);
                }
            } else {
                mediaImage.setVisibility(View.GONE);
            }

            if (m.mediaType != null && !"image".equals(m.mediaType)) {
                boolean downloaded = m.mediaLocalPath != null && !m.mediaLocalPath.isEmpty();
                bodyText.setText("[" + m.mediaType + "]" + (downloaded ? " saved to device" : " downloading..."));
            } else if ("image".equals(m.mediaType) && !hasLocalImage) {
                bodyText.setText("[image] downloading...");
            } else {
                bodyText.setText(m.text);
            }

            SimpleDateFormat sdf = new SimpleDateFormat("HH:mm", Locale.getDefault());
            timeText.setText(sdf.format(new Date(m.ts)));
            container.setBackgroundColor(m.fromMe ? 0xFFDCF8C6 : 0xFFFFFFFF);

            return convertView;
        }
    }
}