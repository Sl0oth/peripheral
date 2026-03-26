# Peripheral Script Store — Server

Backend for the Peripheral mod's community script store.

## Setup

```bash
cd store-server
npm install
cp .env.example .env
# Edit .env — set a strong ADMIN_TOKEN
node server.js
```

## .env

```
ADMIN_TOKEN=some_long_random_secret_here
PORT=3000
```

## Endpoints

| Method | Path | Description |
|--------|------|-------------|
| GET | `/` | Store browse page |
| GET | `/submit` | Script submission form |
| GET | `/admin` | Admin review panel (password protected) |
| GET | `/api/scripts` | List approved scripts (no code) |
| GET | `/api/scripts/:id` | Single script with code |
| POST | `/api/submit` | Submit a script for review |
| GET | `/api/admin/scripts?status=pending` | Admin: list by status |
| GET | `/api/admin/scripts/:id` | Admin: full script |
| POST | `/api/admin/approve/:id` | Admin: approve |
| POST | `/api/admin/reject/:id` | Admin: reject (body: `{"note":"reason"}`) |

## Deploying

Any Node.js host works (Railway, Render, DigitalOcean, VPS, etc.).
Point a domain at it and set that domain as the Store URL in Peripheral's Settings tab.

The database is a single SQLite file (`scripts.db`) created automatically on first run.
Back it up occasionally.

## Admin panel

Go to `/admin` in your browser and enter the `ADMIN_TOKEN` from `.env`.
Your token is saved in localStorage so you stay logged in across sessions.

When someone submits a script:
1. Open `/admin` → Pending tab
2. Click the script to expand it and read the full code
3. Approve or Reject (with optional reason)
4. Approved scripts appear in the store immediately

## Liability

The store is provided as a convenience only. The `PERIPHERAL_STORE_DISCLAIMER` in the
submit and browse pages makes clear that the mod author accepts zero liability for
community scripts. Keep this language intact.
