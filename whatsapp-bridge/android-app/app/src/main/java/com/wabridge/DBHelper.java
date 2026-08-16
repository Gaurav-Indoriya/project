package com.wabridge;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

/**
 * Local persistence layer. Everything the app knows about chats/messages
 * lives here so history survives app restarts (#21) and the chat list
 * can be built without re-fetching everything from the server (#6, #7).
 *
 * Media files themselves are downloaded once from the server and kept in
 * the app's own storage (media_local_path) - the server only holds them
 * briefly in memory as a handoff, since it may be on ephemeral free hosting.
 *
 * deleted_chats is a tombstone table: once a chat is deleted, any re-sync
 * of messages older than the deletion point is silently dropped instead of
 * resurrecting the chat. A genuinely new message after the deletion point
 * clears the tombstone and lets the chat reappear naturally, same as
 * starting a fresh conversation.
 */
public class DBHelper extends SQLiteOpenHelper {

    private static final String DB_NAME = "wabridge.db";
    private static final int DB_VERSION = 3;

    public static final String STATUS_BROADCAST_JID = "status@broadcast";

    private static DBHelper instance;

    public static synchronized DBHelper get(Context ctx) {
        if (instance == null) {
            instance = new DBHelper(ctx.getApplicationContext());
        }
        return instance;
    }

    private DBHelper(Context ctx) {
        super(ctx, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE messages (" +
                "id TEXT PRIMARY KEY, " +
                "chat_jid TEXT, " +
                "from_me INTEGER, " +
                "text TEXT, " +
                "ts INTEGER, " +
                "push_name TEXT, " +
                "media_type TEXT, " +
                "media_url TEXT, " +
                "media_local_path TEXT)");

        db.execSQL("CREATE INDEX idx_messages_chat ON messages(chat_jid, ts)");

        db.execSQL("CREATE TABLE chats (" +
                "jid TEXT PRIMARY KEY, " +
                "name TEXT, " +
                "last_message TEXT, " +
                "last_ts INTEGER, " +
                "unread_count INTEGER DEFAULT 0)");

        db.execSQL("CREATE TABLE deleted_chats (" +
                "jid TEXT PRIMARY KEY, " +
                "deleted_at INTEGER)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // Migrate in place - do NOT drop tables, that would wipe the user's
        // chat history on every app update (#21 depends on this surviving).
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE messages ADD COLUMN media_type TEXT");
            db.execSQL("ALTER TABLE messages ADD COLUMN media_url TEXT");
            db.execSQL("ALTER TABLE messages ADD COLUMN media_local_path TEXT");
        }
        if (oldVersion < 3) {
            db.execSQL("CREATE TABLE IF NOT EXISTS deleted_chats (" +
                    "jid TEXT PRIMARY KEY, " +
                    "deleted_at INTEGER)");
            // Clean up any WhatsApp Status entries that may have been synced
            // before Status was explicitly excluded.
            db.execSQL("DELETE FROM messages WHERE chat_jid='" + STATUS_BROADCAST_JID + "'");
            db.execSQL("DELETE FROM chats WHERE jid='" + STATUS_BROADCAST_JID + "'");
        }
    }

    /**
     * Insert a message if it doesn't already exist, and update the chat summary row.
     * Returns false (and inserts nothing) if this message belongs to a chat that was
     * deleted locally and is not newer than the deletion point.
     */
    public boolean saveIncomingOrOutgoing(String id, String chatJid, boolean fromMe, String text,
                                          long ts, String pushName, boolean incrementUnread,
                                          String mediaType, String mediaUrl) {
        if (STATUS_BROADCAST_JID.equals(chatJid)) {
            return false; // WhatsApp Status is never stored (#3).
        }

        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            Cursor tombstone = db.rawQuery("SELECT deleted_at FROM deleted_chats WHERE jid=?", new String[]{chatJid});
            boolean isDeleted = tombstone.moveToFirst();
            long deletedAt = isDeleted ? tombstone.getLong(0) : 0;
            tombstone.close();

            if (isDeleted) {
                if (ts <= deletedAt) {
                    // This message predates (or is exactly at) the deletion - don't resurrect the chat.
                    db.setTransactionSuccessful();
                    return false;
                }
                // A genuinely new message arrived after the chat was deleted - let it
                // reappear naturally and clear the tombstone.
                db.delete("deleted_chats", "jid=?", new String[]{chatJid});
            }

            Cursor c = db.rawQuery("SELECT id FROM messages WHERE id=?", new String[]{id});
            boolean exists = c.moveToFirst();
            c.close();
            if (!exists) {
                ContentValues cv = new ContentValues();
                cv.put("id", id);
                cv.put("chat_jid", chatJid);
                cv.put("from_me", fromMe ? 1 : 0);
                cv.put("text", text);
                cv.put("ts", ts);
                cv.put("push_name", pushName);
                cv.put("media_type", mediaType);
                cv.put("media_url", mediaUrl);
                db.insert("messages", null, cv);

                ContentValues chatCv = new ContentValues();
                chatCv.put("jid", chatJid);
                chatCv.put("name", (pushName != null && !pushName.isEmpty()) ? pushName : chatJid.split("@")[0]);
                chatCv.put("last_message", text);
                chatCv.put("last_ts", ts);

                Cursor existingChat = db.rawQuery("SELECT unread_count, name FROM chats WHERE jid=?", new String[]{chatJid});
                int unread = 0;
                String existingName = null;
                if (existingChat.moveToFirst()) {
                    unread = existingChat.getInt(0);
                    existingName = existingChat.getString(1);
                }
                existingChat.close();

                if (incrementUnread) unread += 1;
                chatCv.put("unread_count", unread);
                // Don't overwrite a known contact name with a bare JID fallback
                if (existingName != null && !existingName.isEmpty() && (pushName == null || pushName.isEmpty())) {
                    chatCv.put("name", existingName);
                }

                db.insertWithOnConflict("chats", null, chatCv, SQLiteDatabase.CONFLICT_REPLACE);
            }
            db.setTransactionSuccessful();
            return !exists;
        } finally {
            db.endTransaction();
        }
    }

    public void setMediaLocalPath(String messageId, String localPath) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put("media_local_path", localPath);
        db.update("messages", cv, "id=?", new String[]{messageId});
    }

    public void markChatRead(String chatJid) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put("unread_count", 0);
        db.update("chats", cv, "jid=?", new String[]{chatJid});
    }

    /**
     * Removes a chat and all its messages from this device only (#13), and records a
     * tombstone so a background re-sync can't bring old messages back and resurrect it.
     */
    public void deleteChat(String chatJid) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            db.delete("messages", "chat_jid=?", new String[]{chatJid});
            db.delete("chats", "jid=?", new String[]{chatJid});

            ContentValues cv = new ContentValues();
            cv.put("jid", chatJid);
            cv.put("deleted_at", System.currentTimeMillis());
            db.insertWithOnConflict("deleted_chats", null, cv, SQLiteDatabase.CONFLICT_REPLACE);

            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    public Cursor getChatList() {
        SQLiteDatabase db = getReadableDatabase();
        return db.rawQuery("SELECT jid, name, last_message, last_ts, unread_count " +
                "FROM chats WHERE jid != ? ORDER BY last_ts DESC", new String[]{STATUS_BROADCAST_JID});
    }

    public Cursor getMessagesForChat(String chatJid) {
        SQLiteDatabase db = getReadableDatabase();
        return db.rawQuery("SELECT id, from_me, text, ts, push_name, media_type, media_url, media_local_path " +
                "FROM messages WHERE chat_jid=? ORDER BY ts ASC", new String[]{chatJid});
    }

    public int getTotalUnread() {
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = db.rawQuery("SELECT SUM(unread_count) FROM chats WHERE jid != ?", new String[]{STATUS_BROADCAST_JID});
        int total = 0;
        if (c.moveToFirst()) total = c.getInt(0);
        c.close();
        return total;
    }
}