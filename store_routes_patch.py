# ============================================================
# PERIPHERAL SCRIPT STORE — paste this into app.py
# ============================================================
#
# 1. Add the import at the top of app.py (if not already present):
#    import datetime
#
# 2. In init_db(), add the store_scripts table creation inside the
#    existing `with app.app_context():` / `db = get_db()` block,
#    alongside the existing CREATE TABLE statements:

STORE_TABLE_SQL = """
CREATE TABLE IF NOT EXISTS store_scripts (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    name         TEXT    NOT NULL,
    description  TEXT    NOT NULL,
    author       TEXT    NOT NULL,              -- public display name (may be "Anonymous")
    submitted_by TEXT    NOT NULL DEFAULT '',   -- real Minecraft username, admins only
    category     TEXT    NOT NULL DEFAULT 'general',
    content      TEXT    NOT NULL,
    status       TEXT    NOT NULL DEFAULT 'pending',
    created_at   TEXT    NOT NULL,
    updated_at   TEXT    NOT NULL
);
"""

# 3. In require_auth(), add the store public paths to the whitelist.
#    Find the existing whitelist check (probably something like
#    `if request.path in ['/health', ...]`) and add these paths:
#
#    PUBLIC_PATHS = [
#        '/health',                    # whatever already exists
#        '/api/store/scripts',
#    ]
#    Also allow prefix /api/store/scripts/<id> — easiest with:
#
#    if request.path.startswith('/api/store/scripts') and request.method == 'GET':
#        return  # public read access
#    if request.path == '/api/store/submit' and request.method == 'POST':
#        return  # public submit


# ============================================================
# 4. Register these routes in app.py (just paste the block below)
# ============================================================

import datetime as _dt


def _now():
    return _dt.datetime.utcnow().isoformat() + "Z"


# --- Public: list approved scripts -----------------------------------------

@app.route("/api/store/scripts", methods=["GET"])
def store_list():
    db = get_db()
    category = request.args.get("category")
    if category:
        rows = db.execute(
            "SELECT id, name, description, author, category, created_at, updated_at "
            "FROM store_scripts WHERE status = 'approved' AND category = ? ORDER BY id DESC",
            (category,),
        ).fetchall()
    else:
        rows = db.execute(
            "SELECT id, name, description, author, category, created_at, updated_at "
            "FROM store_scripts WHERE status = 'approved' ORDER BY id DESC"
        ).fetchall()
    return jsonify([dict(r) for r in rows])


# --- Public: get single approved script (includes content) -----------------

@app.route("/api/store/scripts/<int:script_id>", methods=["GET"])
def store_get(script_id):
    db = get_db()
    row = db.execute(
        "SELECT id, name, description, author, category, content, created_at, updated_at "
        "FROM store_scripts WHERE id = ? AND status = 'approved'",
        (script_id,),
    ).fetchone()
    if row is None:
        return jsonify({"error": "not found"}), 404
    return jsonify(dict(row))


# --- Public: submit a script for review ------------------------------------

@app.route("/api/store/submit", methods=["POST"])
def store_submit():
    data = request.get_json(force=True, silent=True) or {}
    required = ("name", "description", "author", "content")
    missing = [f for f in required if not data.get(f)]
    if missing:
        return jsonify({"error": f"missing fields: {', '.join(missing)}"}), 400

    now = _now()
    db = get_db()
    cur = db.execute(
        "INSERT INTO store_scripts (name, description, author, submitted_by, category, content, status, created_at, updated_at) "
        "VALUES (?, ?, ?, ?, ?, ?, 'pending', ?, ?)",
        (
            data["name"],
            data["description"],
            data["author"],
            data.get("submitted_by", ""),
            data.get("category", "general"),
            data["content"],
            now,
            now,
        ),
    )
    db.commit()
    return jsonify({"id": cur.lastrowid, "status": "pending"}), 201


# --- Admin: list all scripts (any status) ----------------------------------

@app.route("/api/store/admin/scripts", methods=["GET"])
def store_admin_list():
    db = get_db()
    status_filter = request.args.get("status")
    if status_filter:
        rows = db.execute(
            "SELECT * FROM store_scripts WHERE status = ? ORDER BY id DESC",
            (status_filter,),
        ).fetchall()
    else:
        rows = db.execute(
            "SELECT * FROM store_scripts ORDER BY id DESC"
        ).fetchall()
    return jsonify([dict(r) for r in rows])


# --- Admin: update a script (edit content/metadata) -----------------------

@app.route("/api/store/admin/scripts/<int:script_id>", methods=["POST"])
def store_admin_update(script_id):
    data = request.get_json(force=True, silent=True) or {}
    allowed = ("name", "description", "author", "category", "content", "status")
    updates = {k: v for k, v in data.items() if k in allowed}
    if not updates:
        return jsonify({"error": "no updatable fields provided"}), 400

    updates["updated_at"] = _now()
    set_clause = ", ".join(f"{k} = ?" for k in updates)
    values = list(updates.values()) + [script_id]

    db = get_db()
    db.execute(f"UPDATE store_scripts SET {set_clause} WHERE id = ?", values)
    db.commit()
    return jsonify({"ok": True})


# --- Admin: approve a script -----------------------------------------------

@app.route("/api/store/admin/scripts/<int:script_id>/approve", methods=["POST"])
def store_admin_approve(script_id):
    db = get_db()
    db.execute(
        "UPDATE store_scripts SET status = 'approved', updated_at = ? WHERE id = ?",
        (_now(), script_id),
    )
    db.commit()
    return jsonify({"ok": True, "status": "approved"})


# --- Admin: reject a script ------------------------------------------------

@app.route("/api/store/admin/scripts/<int:script_id>/reject", methods=["POST"])
def store_admin_reject(script_id):
    db = get_db()
    db.execute(
        "UPDATE store_scripts SET status = 'rejected', updated_at = ? WHERE id = ?",
        (_now(), script_id),
    )
    db.commit()
    return jsonify({"ok": True, "status": "rejected"})
