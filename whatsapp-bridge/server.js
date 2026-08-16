const {
  default: makeWASocket,
  useMultiFileAuthState,
  fetchLatestBaileysVersion,
  downloadMediaMessage,
  Browsers,
  DisconnectReason
} = require('@whiskeysockets/baileys');

const { Boom } = require('@hapi/boom');

const express = require('express');
const cors = require('cors');
const qrcodeTerminal = require('qrcode-terminal');
const QRCode = require('qrcode');
const P = require('pino');
const path = require('path');
const fs = require('fs');
const initSqlJs = require('sql.js');

// =====================================================
// CONFIG
// =====================================================

const PORT = process.env.PORT || 3000;
const DEVICE_TOKEN = process.env.DEVICE_TOKEN || 'gaurav03';
const AUTH_FOLDER = path.join(__dirname, 'auth_state');
const DB_FILE = path.join(__dirname, 'bridge.db');

const logger = P({ level: 'silent' });

if (!fs.existsSync(AUTH_FOLDER)) {
  fs.mkdirSync(AUTH_FOLDER, { recursive: true });
}

// =====================================================
// MEDIA CACHE (in-memory, not disk)
// =====================================================
// The app downloads media once and keeps its own permanent local copy, so
// the server only needs to hold each file long enough to hand it off. This
// avoids relying on persistent disk, which many free hosting tiers don't
// give you (or wipe on every redeploy/restart). Oldest entries are evicted
// once the cache hits MAX_MEDIA_ITEMS so memory doesn't grow unbounded.

const MAX_MEDIA_ITEMS = 100;
const mediaCache = new Map(); // id -> { buffer, ext, mimeType }

function cacheMedia(id, buffer, ext) {
  const mimeType = ext === 'jpg' ? 'image/jpeg'
    : ext === 'mp4' ? 'video/mp4'
    : ext === 'ogg' ? 'audio/ogg'
    : 'application/octet-stream';

  mediaCache.set(id, { buffer, ext, mimeType });

  if (mediaCache.size > MAX_MEDIA_ITEMS) {
    const oldestKey = mediaCache.keys().next().value;
    mediaCache.delete(oldestKey);
  }
}

// =====================================================
// DATABASE (sql.js - WASM SQLite, no native build required)
// =====================================================
// Replaces the old in-memory 500-message ring buffer. Messages now persist
// across server restarts and there's no hard cap on history. (#21)
//
// sql.js keeps the database in memory and we explicitly export + write it
// to disk. Writes are debounced (saved ~500ms after the last change) so a
// burst of incoming messages doesn't write to disk on every single row.

let db = null;
let saveTimer = null;

function scheduleSave() {
  if (saveTimer) return;
  saveTimer = setTimeout(() => {
    saveTimer = null;
    try {
      const data = db.export();
      fs.writeFileSync(DB_FILE, Buffer.from(data));
    } catch (err) {
      console.error('DB save failed:', err.message);
    }
  }, 500);
}

function run(sql, params = []) {
  db.run(sql, params);
  scheduleSave();
}

function all(sql, params = []) {
  const stmt = db.prepare(sql);
  stmt.bind(params);
  const rows = [];
  while (stmt.step()) {
    rows.push(stmt.getAsObject());
  }
  stmt.free();
  return rows;
}

function get(sql, params = []) {
  const rows = all(sql, params);
  return rows.length ? rows[0] : null;
}

async function initDb() {
  const SQL = await initSqlJs({
    locateFile: (file) => path.join(__dirname, 'node_modules', 'sql.js', 'dist', file)
  });

  // Auto-recover: if bridge.db exists but is corrupted or incomplete (e.g. the
  // process was killed mid-write on a previous run), don't crash - back up the
  // bad file and start a fresh database instead.
  if (fs.existsSync(DB_FILE)) {
    try {
      const fileBuffer = fs.readFileSync(DB_FILE);
      db = new SQL.Database(fileBuffer);
      // Confirm the file is actually a usable database, not just bytes that loaded.
      db.run(`CREATE TABLE IF NOT EXISTS __sanity_check (x INTEGER)`);
      db.run(`DROP TABLE __sanity_check`);
    } catch (err) {
      console.error('bridge.db appears corrupted, starting fresh:', err.message);
      const backupPath = DB_FILE + '.corrupt-' + Date.now();
      try {
        fs.renameSync(DB_FILE, backupPath);
        //console.log('Backed up corrupted DB to:', backupPath);
      } catch (renameErr) {
        console.error('Could not back up corrupted DB:', renameErr.message);
      }
      db = new SQL.Database();
    }
  } else {
    db = new SQL.Database();
  }

  db.run(`
    CREATE TABLE IF NOT EXISTS messages (
      id TEXT,
      chat_jid TEXT NOT NULL,
      from_me INTEGER NOT NULL,
      text TEXT,
      ts INTEGER NOT NULL,
      push_name TEXT,
      participant TEXT,
      is_group INTEGER NOT NULL DEFAULT 0,
      media_type TEXT,
      media_path TEXT,
      status TEXT DEFAULT 'sent',
      PRIMARY KEY (id, chat_jid)
    );
  `);
  db.run(`CREATE INDEX IF NOT EXISTS idx_messages_ts ON messages(ts);`);
  db.run(`CREATE INDEX IF NOT EXISTS idx_messages_chat ON messages(chat_jid, ts);`);

  db.run(`
    CREATE TABLE IF NOT EXISTS chat_meta (
      jid TEXT PRIMARY KEY,
      name TEXT,
      is_group INTEGER NOT NULL DEFAULT 0
    );
  `);

  // One-time cleanup: remove any WhatsApp Status entries stored before
  // Status was explicitly excluded (#3). Runs after both tables exist.
  run(`DELETE FROM messages WHERE chat_jid = 'status@broadcast'`);
  run(`DELETE FROM chat_meta WHERE jid = 'status@broadcast'`);

  scheduleSave();
}

function pushMessage(entry) {
  const isGroup = entry.from.endsWith('@g.us') ? 1 : 0;

  run(
    `INSERT OR IGNORE INTO messages
      (id, chat_jid, from_me, text, ts, push_name, participant, is_group, media_type, media_path, status)
     VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
    [
      entry.id,
      entry.from,
      entry.fromMe ? 1 : 0,
      entry.text,
      entry.ts,
      entry.pushName || '',
      entry.participant || null,
      isGroup,
      entry.mediaType || null,
      entry.mediaPath || null,
      entry.status || 'sent'
    ]
  );

  if (entry.pushName) {
    run(
      `INSERT INTO chat_meta (jid, name, is_group) VALUES (?, ?, ?)
       ON CONFLICT(jid) DO UPDATE SET
         name = CASE WHEN excluded.name IS NOT NULL AND excluded.name != '' THEN excluded.name ELSE chat_meta.name END`,
      [entry.from, entry.pushName, isGroup]
    );
  }
}

function markStatus(id, status) {
  run(`UPDATE messages SET status = ? WHERE id = ?`, [status, id]);
}

// =====================================================
// GLOBAL STATE
// =====================================================

let sock = null;
let connectionState = 'connecting'; // connecting | qr | ready | disconnected
let lastQR = null;
let reconnectTimer = null;
let isStarting = false;

let connectionStatus = {
  state: 'connecting',
  qr: null,
  user: null,
  ts: Date.now()
};

function updateStatus() {
  connectionStatus = {
    state: connectionState,
    qr: lastQR,
    user: sock?.user?.id || null,
    ts: Date.now()
  };
}

// Baileys ack codes: 0=pending 1=sent 2=delivered 3=read 4=played
const STATUS_MAP = {
  0: 'pending',
  1: 'sent',
  2: 'delivered',
  3: 'read',
  4: 'read'
};

// =====================================================
// START WHATSAPP
// =====================================================

async function startSock() {
  if (isStarting) {
    console.log('Socket start already in progress.');
    return;
  }
  isStarting = true;

  try {
    // console.log('       STARTING WHATSAPP SOCKET');
    // console.log('========================================');
    // console.log('\n========================================');
    // console.log('Auth folder:', AUTH_FOLDER);

    const { state, saveCreds } = await useMultiFileAuthState(AUTH_FOLDER);
    //console.log('Existing credentials:', state.creds.registered);

    const { version } = await fetchLatestBaileysVersion();
    //console.log('WhatsApp Web version:', version.join('.'));

    sock = makeWASocket({
      version,
      auth: state,
      // Ubuntu Chrome advertises WEB_BROWSER - avoids 428 before QR that
      // some Browsers.macOS('Desktop') combos hit.
      browser: Browsers.ubuntu('Chrome'),
      logger,
      printQRInTerminal: false,
      markOnlineOnConnect: false,
      connectTimeoutMs: 60000,
      defaultQueryTimeoutMs: 60000,
      syncFullHistory: false
    });

    isStarting = false;

    sock.ev.on('creds.update', saveCreds);

    // =================================================
    // CONNECTION UPDATE
    // =================================================

    sock.ev.on('connection.update', async (update) => {
      const { connection, lastDisconnect, qr } = update;

      if (qr) {
        //console.log('\n========================================');
        //console.log('          QR CODE RECEIVED');
        //console.log('========================================');

        lastQR = qr;
        connectionState = 'qr';
        updateStatus();

        qrcodeTerminal.generate(qr, { small: true }, (code) => {
          // console.log('\nScan this QR with WhatsApp:\n');
          // console.log(code);
          // console.log(`\nBrowser QR: http://localhost:${PORT}/qr/page\n`);
        });
      }

      if (connection === 'open') {
        connectionState = 'ready';
        lastQR = null;
        updateStatus();

        // console.log('\n========================================');
           console.log('       WHATSAPP CONNECTED');
        // console.log('========================================');
        // console.log('User:', sock?.user?.id || 'Unknown');
        // console.log('');
      }

      if (connection === 'close') {
        let statusCode = null;

        if (lastDisconnect?.error instanceof Boom) {
          statusCode = lastDisconnect.error.output?.statusCode;
        }
        if (!statusCode) {
          statusCode = lastDisconnect?.error?.output?.statusCode || null;
        }

        const shouldReconnect = statusCode !== DisconnectReason.loggedOut;

        // console.log('\n========================================');
        // console.log('     WHATSAPP CONNECTION CLOSED');
        // console.log('========================================');
        // console.log('Status code:', statusCode);
        // console.log('Reconnect:', shouldReconnect);
        // console.log('========================================');

        connectionState = 'disconnected';
        lastQR = null;
        updateStatus();

        if (!shouldReconnect) {
          // console.log('\nWhatsApp logged out.');
          // console.log('Delete auth_state and restart if you want a new QR.\n');
          return;
        }

        if (reconnectTimer) clearTimeout(reconnectTimer);

        //console.log('\nReconnecting in 5 seconds...');
        reconnectTimer = setTimeout(() => {
          reconnectTimer = null;
          startSock();
        }, 5000);
      }

      if (connection === 'connecting') {
        connectionState = 'connecting';
        updateStatus();
        //console.log('Connecting to WhatsApp...');
      }
    });

    // =================================================
    // MESSAGES
    // =================================================

    sock.ev.on('messages.upsert', async (upsert) => {
      try {
        if (upsert.type !== 'notify') return;

        for (const m of upsert.messages) {
          if (!m.message) continue;

          const from = m.key.remoteJid;

          // WhatsApp Status broadcasts come through as messages from this
          // JID. Skip entirely - never stored, never downloaded, never
          // shown. This app is chats and chat media only (#3).
          if (from === 'status@broadcast') continue;

          const isFromMe = m.key.fromMe;
          const isGroup = from.endsWith('@g.us');
          // In a group, m.key.participant is who actually sent it.
          // In a 1:1 chat there's no separate participant. (#17 sender identity)
          const participant = isGroup ? (m.key.participant || null) : null;

          const text =
            m.message.conversation ||
            m.message.extendedTextMessage?.text ||
            m.message.imageMessage?.caption ||
            m.message.videoMessage?.caption ||
            m.message.documentMessage?.caption ||
            '';

          const mediaType = m.message.imageMessage ? 'image'
            : m.message.videoMessage ? 'video'
            : m.message.audioMessage ? 'audio'
            : m.message.documentMessage ? 'document'
            : null;

          if (!text && !mediaType) continue;

          const id = m.key.id;
          let hasMediaCached = false;

          // Download media and hold it in memory just long enough for the
          // app to fetch it via /media/:id (#15) - see MEDIA CACHE above.
          if (mediaType) {
            try {
              const buffer = await downloadMediaMessage(
                m,
                'buffer',
                {},
                { logger, reuploadRequest: sock.updateMediaMessage }
              );
              const ext = mediaType === 'image' ? 'jpg'
                : mediaType === 'video' ? 'mp4'
                : mediaType === 'audio' ? 'ogg'
                : (m.message.documentMessage?.fileName?.split('.').pop() || 'bin');
              cacheMedia(id, buffer, ext);
              hasMediaCached = true;
            } catch (mediaErr) {
              console.error('Media download failed:', mediaErr.message);
            }
          }

          const entry = {
            id,
            from,
            fromMe: isFromMe,
            text: text || `[${mediaType || 'media'}]`,
            ts: m.messageTimestamp ? Number(m.messageTimestamp) * 1000 : Date.now(),
            pushName: m.pushName || '',
            participant,
            mediaType,
            mediaPath: hasMediaCached ? id : null,
            status: isFromMe ? 'sent' : 'received'
          };

          pushMessage(entry);

          //console.log(`[${isFromMe ? 'OUT' : 'IN'}] ${from}${participant ? ' (' + participant + ')' : ''}: ${entry.text}`);
        }
      } catch (error) {
        //console.error('Message processing error:', error);
      }
    });

    // =================================================
    // MESSAGE STATUS UPDATES (sent/delivered/read) (#20)
    // =================================================

    sock.ev.on('messages.update', (updates) => {
      for (const u of updates) {
        const id = u.key?.id;
        const ack = u.update?.status;
        if (id != null && ack != null) {
          const mapped = STATUS_MAP[ack] || null;
          if (mapped) markStatus(id, mapped);
        }
      }
    });

  } catch (error) {
    isStarting = false;

    console.error('\n========================================');
    console.error('FAILED TO START WHATSAPP');
    console.error('========================================');
    console.error(error);
    console.error('========================================');

    connectionState = 'disconnected';
    lastQR = null;
    updateStatus();

    setTimeout(() => {
      startSock();
    }, 5000);
  }
}

// =====================================================
// EXPRESS
// =====================================================

const app = express();
app.use(cors());
app.use(express.json());

// =====================================================
// TOKEN MIDDLEWARE
// =====================================================

app.use((req, res, next) => {
  const publicRoutes = ['/', '/qr', '/qr/page', '/status'];
  if (publicRoutes.includes(req.path) || req.method === 'OPTIONS') {
    return next();
  }

  const token = req.headers['x-device-token'] || req.query.token;
  if (token !== DEVICE_TOKEN) {
    return res.status(401).json({ error: 'unauthorized' });
  }
  next();
});

// =====================================================
// HOME
// =====================================================

app.get('/', (req, res) => {
  res.send(`
<!DOCTYPE html>
<html>
<head>
<meta name="viewport" content="width=device-width, initial-scale=1" />
<title>WhatsApp Bridge</title>
<style>
body { margin:0; min-height:100vh; display:flex; justify-content:center; align-items:center; font-family:Arial; background:#f0f2f5; }
.box { background:white; padding:35px; border-radius:16px; text-align:center; box-shadow:0 5px 30px rgba(0,0,0,.15); width:90%; max-width:400px; }
h1 { color:#075e54; }
a { display:block; padding:12px; margin-top:15px; background:#25d366; color:white; text-decoration:none; border-radius:8px; }
</style>
</head>
<body>
<div class="box">
<h1>WhatsApp Bridge</h1>
<p>Connection: <strong>${connectionState}</strong></p>
<a href="/qr/page">Open QR</a>
<a href="/status">Status</a>
</div>
</body>
</html>
`);
});

// =====================================================
// STATUS
// =====================================================

app.get('/status', (req, res) => {
  res.json({
    state: connectionState,
    user: sock?.user?.id || null,
    hasQR: !!lastQR,
    timestamp: Date.now()
  });
});

// =====================================================
// QR JSON / QR PAGE (unchanged)
// =====================================================

app.get('/qr', (req, res) => {
  res.json(connectionStatus);
});

app.get('/qr/page', async (req, res) => {
  res.setHeader('Content-Type', 'text/html');
  res.setHeader('Cache-Control', 'no-store, no-cache, must-revalidate');

  if (connectionState === 'ready') {
    return res.send(`
<!DOCTYPE html>
<html>
<head>
<meta name="viewport" content="width=device-width, initial-scale=1" />
<title>WhatsApp Connected</title>
<style>
body { margin:0; min-height:100vh; display:flex; justify-content:center; align-items:center; font-family:Arial; background:#f0f2f5; }
.box { background:white; padding:40px; border-radius:18px; text-align:center; box-shadow:0 5px 30px rgba(0,0,0,.15); }
.success { font-size:60px; color:#25d366; }
h2 { color:#075e54; }
</style>
</head>
<body>
<div class="box">
<div class="success">&#10003;</div>
<h2>WhatsApp Connected</h2>
<p>${sock?.user?.id || ''}</p>
</div>
</body>
</html>
`);
  }

  if (!lastQR) {
    return res.send(`
<!DOCTYPE html>
<html>
<head>
<meta http-equiv="refresh" content="3" />
<meta name="viewport" content="width=device-width, initial-scale=1" />
<title>Waiting</title>
<style>
body { margin:0; min-height:100vh; display:flex; justify-content:center; align-items:center; font-family:Arial; background:#f0f2f5; }
.box { background:white; padding:40px; border-radius:18px; text-align:center; box-shadow:0 5px 30px rgba(0,0,0,.15); }
.loader { font-size:45px; }
h2 { color:#075e54; }
p { color:#666; }
</style>
</head>
<body>
<div class="box">
<div class="loader">&#8987;</div>
<h2>Waiting for QR code...</h2>
<p>State: <strong>${connectionState}</strong></p>
<p>Refreshing automatically...</p>
</div>
</body>
</html>
`);
  }

  try {
    const qrDataUrl = await QRCode.toDataURL(lastQR, { width: 300, margin: 2, errorCorrectionLevel: 'M' });

    return res.send(`
<!DOCTYPE html>
<html>
<head>
<meta name="viewport" content="width=device-width, initial-scale=1" />
<meta http-equiv="refresh" content="10" />
<title>WhatsApp QR</title>
<style>
* { box-sizing:border-box; }
body { margin:0; min-height:100vh; display:flex; justify-content:center; align-items:center; font-family:Arial; background:#f0f2f5; }
.container { background:white; width:92%; max-width:420px; padding:30px; border-radius:18px; text-align:center; box-shadow:0 5px 30px rgba(0,0,0,.15); }
h2 { color:#075e54; margin-top:0; }
.status { color:#25d366; font-weight:bold; }
.qr { display:block; width:300px; max-width:100%; height:auto; margin:20px auto; padding:10px; background:white; }
.instructions { color:#555; line-height:1.6; }
button { margin-top:20px; padding:12px 25px; border:none; border-radius:8px; background:#25d366; color:white; font-size:16px; cursor:pointer; }
.small { margin-top:15px; font-size:13px; color:#999; }
</style>
</head>
<body>
<div class="container">
<h2>WhatsApp Login</h2>
<div class="status">Scan this QR code</div>
<img class="qr" src="${qrDataUrl}" alt="WhatsApp QR Code" />
<div class="instructions">
Open WhatsApp<br><br>
<strong>Settings &rarr; Linked Devices</strong><br><br>
Tap:<br>
<strong>Link a Device</strong>
</div>
<button onclick="location.reload()">Refresh QR</button>
<div class="small">QR page refreshes automatically.</div>
</div>
</body>
</html>
`);
  } catch (error) {
    console.error('QR generation error:', error);
    return res.status(500).send(`<h2>QR generation failed</h2><pre>${error.message}</pre>`);
  }
});

// =====================================================
// MESSAGES
// Reads from sql.js instead of the old in-memory array, and includes
// mediaType/mediaPath/status/participant fields.
// =====================================================

app.get('/messages', (req, res) => {
  const since = parseInt(req.query.since) || 0;
  const chatJid = req.query.chat || null;

  const rows = chatJid
    ? all(`SELECT * FROM messages WHERE ts > ? AND chat_jid = ? AND chat_jid != 'status@broadcast' ORDER BY ts ASC`, [since, chatJid])
    : all(`SELECT * FROM messages WHERE ts > ? AND chat_jid != 'status@broadcast' ORDER BY ts ASC`, [since]);

  const result = rows.map(r => ({
    id: r.id,
    from: r.chat_jid,
    fromMe: !!r.from_me,
    text: r.text,
    ts: r.ts,
    pushName: r.push_name,
    participant: r.participant,
    isGroup: !!r.is_group,
    mediaType: r.media_type,
    mediaUrl: r.media_path ? `/media/${r.id}` : null,
    status: r.status
  }));

  res.json({
    messages: result,
    count: result.length,
    serverTs: Date.now()
  });
});

// =====================================================
// MEDIA
// Serves a downloaded media file by message id. (#15)
// =====================================================

app.get('/media/:id', (req, res) => {
  const cached = mediaCache.get(req.params.id);
  if (!cached) {
    // Either never cached, evicted (cache is capped), or the server has
    // restarted since - the app should already have its own copy for
    // anything it downloaded before that point.
    return res.status(404).json({ error: 'media no longer available on server - not cached or already expired' });
  }
  res.setHeader('Content-Type', cached.mimeType);
  res.send(cached.buffer);
});

// =====================================================
// CHATS
// Returns both individual chats (derived from stored message history) and
// WhatsApp groups (fetched live from Baileys), each tagged isGroup. (#12, #17)
// =====================================================

app.get('/chats', async (req, res) => {
  try {
    const individualRows = all(`
      SELECT chat_jid, MAX(ts) as last_ts,
             (SELECT push_name FROM messages m2 WHERE m2.chat_jid = m1.chat_jid AND m2.push_name != '' ORDER BY ts DESC LIMIT 1) as name
      FROM messages m1
      WHERE is_group = 0 AND chat_jid != 'status@broadcast'
      GROUP BY chat_jid
      ORDER BY last_ts DESC
    `);

    const individuals = individualRows.map(r => ({
      id: r.chat_jid,
      name: r.name || r.chat_jid.split('@')[0],
      isGroup: false,
      lastTs: r.last_ts
    }));

    let groups = [];
    if (sock && connectionState === 'ready') {
      try {
        const groupChats = await sock.groupFetchAllParticipating();
        groups = Object.values(groupChats).map(c => ({
          id: c.id,
          name: c.subject || c.id,
          isGroup: true,
          participantCount: c.participants ? c.participants.length : null
        }));
      } catch (groupErr) {
        console.error('Group fetch error:', groupErr.message);
      }
    }

    res.json({ chats: [...individuals, ...groups] });
  } catch (error) {
    console.error('Chats error:', error);
    res.status(500).json({ error: error.message });
  }
});

// =====================================================
// SEND MESSAGE
// =====================================================

app.post('/send', async (req, res) => {
  try {
    if (!sock) {
      return res.status(503).json({ error: 'WhatsApp socket not initialized' });
    }
    if (connectionState !== 'ready') {
      return res.status(503).json({ error: 'WhatsApp is not connected', state: connectionState });
    }

    const { to, text } = req.body;
    if (!to || !text) {
      return res.status(400).json({ error: 'to and text required' });
    }

    const jid = to.includes('@') ? to : to.replace(/\D/g, '') + '@s.whatsapp.net';
    const sent = await sock.sendMessage(jid, { text });

    pushMessage({
      id: sent.key.id,
      from: jid,
      fromMe: true,
      text,
      ts: Date.now(),
      pushName: '',
      status: 'sent'
    });

    res.json({ success: true, id: sent.key.id });
  } catch (error) {
    console.error('Send message error:', error);
    res.status(500).json({ error: error.message });
  }
});

// =====================================================
// STARTUP
// DB init is async (loading the WASM module), so the server only starts
// listening / connecting to WhatsApp once it's ready.
// =====================================================

async function main() {
  await initDb();

  app.listen(PORT, () => {
    console.log(`WhatsApp Server Started on: http://localhost:${PORT}`);
  });

  startSock();
}

main();