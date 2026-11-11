import sqlite3, sys, shutil, pathlib, tempfile
root = pathlib.Path(sys.argv[1])
print(f"# sqlite library used to open the copies: {sqlite3.sqlite_version}")
for label in sorted(p.name for p in root.iterdir() if p.name.startswith("after-")):
    parts = []
    for kind in ("only-db", "db-and-wal"):
        # work on a throwaway copy so opening it cannot change the snapshot
        with tempfile.TemporaryDirectory() as tmp:
            for f in (root / label / kind).iterdir():
                shutil.copy(f, tmp)
            con = sqlite3.connect(str(pathlib.Path(tmp) / "diditake.db"))
            tables = con.execute("select count(*) from sqlite_master where type='table'").fetchone()[0]
            try:
                rows = con.execute("select count(*) from dose_record").fetchone()[0]
            except sqlite3.OperationalError:
                rows = "no table"
            con.close()
        parts.append(f"{kind}: tables={tables} dose_record rows={rows}")
    print(f"{label} | " + " | ".join(parts))
