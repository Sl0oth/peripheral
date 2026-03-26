require('dotenv').config();
const express = require('express');
const path    = require('path');
const fs      = require('fs');
const crypto  = require('crypto');

const app         = express();
const PORT        = process.env.PORT || 3000;
const ADMIN_TOKEN = process.env.ADMIN_TOKEN || 'change_me';
const DB_FILE     = path.join(__dirname, 'scripts.json');

// ── JSON file store ───────────────────────────────────────────────────────────

function loadDB() {
  try {
    return JSON.parse(fs.readFileSync(DB_FILE, 'utf8'));
  } catch {
    return { scripts: [] };
  }
}

function saveDB(data) {
  fs.writeFileSync(DB_FILE, JSON.stringify(data, null, 2));
}

// Initialise file if it doesn't exist
if (!fs.existsSync(DB_FILE)) saveDB({ scripts: [] });

// ── Middleware ────────────────────────────────────────────────────────────────

app.use(express.json({ limit: '64kb' }));
app.use(express.static(path.join(__dirname, 'public')));

function requireAdmin(req, res, next) {
  const auth  = req.headers['authorization'] || '';
  const token = auth.startsWith('Bearer ') ? auth.slice(7) : '';
  if (!token || token !== ADMIN_TOKEN) {
    return res.status(401).json({ error: 'Unauthorized' });
  }
  next();
}

function sanitize(str, max) {
  return String(str || '').trim().slice(0, max);
}

// ── Public API ────────────────────────────────────────────────────────────────

// GET /api/scripts — list all approved scripts (no code)
app.get('/api/scripts', (req, res) => {
  const db   = loadDB();
  const rows = db.scripts
    .filter(s => s.status === 'approved')
    .sort((a, b) => b.submitted_at - a.submitted_at)
    .map(({ id, name, description, author, submitted_at }) =>
      ({ id, name, description, author, submitted_at }));
  res.json(rows);
});

// GET /api/scripts/:id — single approved script with code
app.get('/api/scripts/:id', (req, res) => {
  const db  = loadDB();
  const row = db.scripts.find(s => s.id === req.params.id && s.status === 'approved');
  if (!row) return res.status(404).json({ error: 'Not found' });
  const { id, name, description, code, author, submitted_at } = row;
  res.json({ id, name, description, code, author, submitted_at });
});

// POST /api/submit — submit a new script
app.post('/api/submit', (req, res) => {
  const name        = sanitize(req.body.name,        64);
  const description = sanitize(req.body.description, 1000);
  const code        = sanitize(req.body.code,        32000);
  const authorRaw   = sanitize(req.body.author,      64);
  const anonymous   = req.body.anonymous === true || req.body.anonymous === 'true';
  const author      = anonymous ? 'Anonymous' : (authorRaw || 'Anonymous');

  if (!name)        return res.status(400).json({ error: 'Name is required' });
  if (!description) return res.status(400).json({ error: 'Description is required' });
  if (!code)        return res.status(400).json({ error: 'Code is required' });
  if (!code.trim().startsWith('from mc import') && !code.trim().startsWith('import'))
    return res.status(400).json({ error: 'Script must start with a Python import statement' });

  const id = crypto.randomBytes(8).toString('hex');
  const db = loadDB();
  db.scripts.push({ id, name, description, code, author, status: 'pending',
    reject_note: null, submitted_at: Date.now(), reviewed_at: null });
  saveDB(db);

  res.json({ ok: true, id });
});

// ── Admin API ─────────────────────────────────────────────────────────────────

// GET /api/admin/scripts?status=pending|approved|rejected
app.get('/api/admin/scripts', requireAdmin, (req, res) => {
  const status = req.query.status || 'pending';
  const db     = loadDB();
  const rows   = db.scripts
    .filter(s => s.status === status)
    .sort((a, b) => b.submitted_at - a.submitted_at)
    .map(({ id, name, description, author, status, reject_note, submitted_at, reviewed_at }) =>
      ({ id, name, description, author, status, reject_note, submitted_at, reviewed_at }));
  res.json(rows);
});

// GET /api/admin/scripts/:id — full script for review
app.get('/api/admin/scripts/:id', requireAdmin, (req, res) => {
  const db  = loadDB();
  const row = db.scripts.find(s => s.id === req.params.id);
  if (!row) return res.status(404).json({ error: 'Not found' });
  res.json(row);
});

// POST /api/admin/approve/:id
app.post('/api/admin/approve/:id', requireAdmin, (req, res) => {
  const db  = loadDB();
  const row = db.scripts.find(s => s.id === req.params.id);
  if (!row) return res.status(404).json({ error: 'Not found' });
  row.status      = 'approved';
  row.reviewed_at = Date.now();
  saveDB(db);
  res.json({ ok: true });
});

// POST /api/admin/reject/:id
app.post('/api/admin/reject/:id', requireAdmin, (req, res) => {
  const note = sanitize(req.body.note, 500);
  const db   = loadDB();
  const row  = db.scripts.find(s => s.id === req.params.id);
  if (!row) return res.status(404).json({ error: 'Not found' });
  row.status      = 'rejected';
  row.reject_note = note || null;
  row.reviewed_at = Date.now();
  saveDB(db);
  res.json({ ok: true });
});

// ── Page routes ───────────────────────────────────────────────────────────────

app.get('/',       (_, res) => res.sendFile(path.join(__dirname, 'public', 'index.html')));
app.get('/submit', (_, res) => res.sendFile(path.join(__dirname, 'public', 'submit.html')));
app.get('/admin',  (_, res) => res.sendFile(path.join(__dirname, 'public', 'admin.html')));

// ── Start ─────────────────────────────────────────────────────────────────────

app.listen(PORT, () => {
  console.log(`Peripheral Store running on http://localhost:${PORT}`);
  console.log(`Admin panel: http://localhost:${PORT}/admin`);
  if (ADMIN_TOKEN === 'change_me') {
    console.warn('WARNING: Set ADMIN_TOKEN in .env before going live!');
  }
});
