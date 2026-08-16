package com.wabridge;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.os.Build;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Chat list / dashboard (#4 partial, #6, #7). Reads from the local DB that
 * WaSyncService keeps up to date, so this screen never talks to the server
 * directly - it just reflects whatever the background service has synced.
 */
public class ChatListActivity extends Activity {

    private ListView chatListView;
    private ChatAdapter adapter;
    private List<ChatRow> chats = new ArrayList<>();
    private TextView emptyText;
    private TextView connStatusText;
    private DBHelper db;

    private BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (WaSyncService.ACTION_UPDATE.equals(intent.getAction())) {
                loadChats();
            } else if (WaSyncService.ACTION_STATUS.equals(intent.getAction())) {
                String serverStatus = intent.getStringExtra(WaSyncService.EXTRA_STATUS);
                String waState = intent.getStringExtra(WaSyncService.EXTRA_STATE);
                connStatusText.setText(describeState(serverStatus, waState));
            }
        }
    };

    /** Maps the real WhatsApp/server state to what the header shows (#1). */
    private String describeState(String serverStatus, String waState) {
        if (!"ok".equals(serverStatus) || "offline".equals(waState)) {
            return "Server unreachable";
        }
        if (waState == null) {
            return "Connecting...";
        }
        switch (waState) {
            case "ready": return "Connected";
            case "qr": return "Waiting for QR scan";
            case "connecting": return "Connecting...";
            case "disconnected": return "WhatsApp disconnected";
            default: return "Connecting...";
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_chat_list);

        db = DBHelper.get(this);
        chatListView = (ListView) findViewById(R.id.chatList);
        emptyText = (TextView) findViewById(R.id.emptyText);
        connStatusText = (TextView) findViewById(R.id.connStatusText);

        adapter = new ChatAdapter();
        chatListView.setAdapter(adapter);

        chatListView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                ChatRow row = chats.get(position);
                Intent intent = new Intent(ChatListActivity.this, ChatActivity.class);
                intent.putExtra("jid", row.jid);
                intent.putExtra("name", row.name);
                startActivity(intent);
            }
        });

        chatListView.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
                final ChatRow row = chats.get(position);
                new AlertDialog.Builder(ChatListActivity.this)
                        .setTitle("Delete chat")
                        .setMessage("Delete chat with " + row.name + "? This removes it from this device only.")
                        .setPositiveButton("Delete", new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                db.deleteChat(row.jid);
                                Toast.makeText(ChatListActivity.this, "Chat deleted", Toast.LENGTH_SHORT).show();
                                loadChats();
                            }
                        })
                        .setNegativeButton("Cancel", null)
                        .show();
                return true;
            }
        });

        Button settingsButton = (Button) findViewById(R.id.settingsButton);
        settingsButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(ChatListActivity.this, MainActivity.class));
            }
        });

        // Make sure the background sync service is running (covers app being
        // reopened after being killed, not just the first connect - #2).
        startService(new Intent(this, WaSyncService.class));
    }

    @Override
    protected void onResume() {
        super.onResume();
        WaSyncService.activeChatJid = null; // no specific chat open while list is showing
        IntentFilter filter = new IntentFilter();
        filter.addAction(WaSyncService.ACTION_UPDATE);
        filter.addAction(WaSyncService.ACTION_STATUS);
        // Android 13+ requires explicitly declaring whether a context-registered
        // receiver is exported to other apps, or it throws a SecurityException.
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(receiver, filter);
        }
        loadChats();
    }

    @Override
    protected void onPause() {
        super.onPause();
        unregisterReceiver(receiver);
    }

    private void loadChats() {
        chats.clear();
        Cursor c = db.getChatList();
        while (c.moveToNext()) {
            ChatRow row = new ChatRow();
            row.jid = c.getString(0);
            row.name = c.getString(1);
            row.lastMessage = c.getString(2);
            row.lastTs = c.getLong(3);
            row.unreadCount = c.getInt(4);
            chats.add(row);
        }
        c.close();
        adapter.notifyDataSetChanged();
        emptyText.setVisibility(chats.isEmpty() ? View.VISIBLE : View.GONE);
    }

    static class ChatRow {
        String jid;
        String name;
        String lastMessage;
        long lastTs;
        int unreadCount;
    }

    private class ChatAdapter extends BaseAdapter {
        @Override
        public int getCount() { return chats.size(); }
        @Override
        public Object getItem(int position) { return chats.get(position); }
        @Override
        public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            if (convertView == null) {
                convertView = LayoutInflater.from(ChatListActivity.this)
                        .inflate(R.layout.chat_list_item, parent, false);
            }
            ChatRow row = chats.get(position);

            TextView avatar = (TextView) convertView.findViewById(R.id.avatarText);
            TextView name = (TextView) convertView.findViewById(R.id.nameText);
            TextView lastMessage = (TextView) convertView.findViewById(R.id.lastMessageText);
            TextView time = (TextView) convertView.findViewById(R.id.timeText);
            TextView unreadBadge = (TextView) convertView.findViewById(R.id.unreadBadge);

            name.setText(row.name);
            lastMessage.setText(row.lastMessage);
            avatar.setText(row.name.isEmpty() ? "?" : row.name.substring(0, 1).toUpperCase());
            time.setText(formatTime(row.lastTs));

            if (row.unreadCount > 0) {
                unreadBadge.setVisibility(View.VISIBLE);
                unreadBadge.setText(String.valueOf(row.unreadCount));
                name.setTextColor(0xFF000000);
            } else {
                unreadBadge.setVisibility(View.GONE);
            }

            return convertView;
        }
    }

    /** Today shows a time, older shows a date - matches #6 "Show latest message time". */
    private String formatTime(long ts) {
        Calendar msgCal = Calendar.getInstance();
        msgCal.setTimeInMillis(ts);
        Calendar today = Calendar.getInstance();

        if (msgCal.get(Calendar.YEAR) == today.get(Calendar.YEAR)
                && msgCal.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR)) {
            return new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date(ts));
        }
        return new SimpleDateFormat("dd/MM", Locale.getDefault()).format(new Date(ts));
    }
}