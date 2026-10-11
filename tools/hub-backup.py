#!/usr/bin/env python3
"""Create a verified, private backup of a running robotOS workspace (database plus immutable media).

Restore only into a new private directory while the service is stopped. Host credentials are
deliberately not included; retain protected hub.json separately. Never copy a live SQLite file.
"""
import argparse
from contextlib import closing
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import sqlite3
import tempfile


def backup(source, destination):
    source, destination = Path(source).resolve(), Path(destination).resolve()
    if destination.exists():
        raise ValueError("Backup destination already exists; choose a new directory")
    if source == destination or source in destination.parents:
        raise ValueError("Backup destination must be outside the live data directory")
    database = source / "workspace.sqlite3"
    if not database.is_file():
        raise ValueError("No workspace database at source")
    destination.parent.mkdir(parents=True, exist_ok=True)
    stage = Path(tempfile.mkdtemp(prefix=".robotos-backup-", dir=destination.parent))
    os.chmod(stage, 0o700)
    try:
        with closing(sqlite3.connect(database.as_uri() + "?mode=ro", uri=True)) as live:
            with closing(sqlite3.connect(stage / "workspace.sqlite3")) as saved:
                live.backup(saved)
                if saved.execute("PRAGMA integrity_check").fetchone()[0] != "ok":
                    raise ValueError("Database integrity check failed")
                media = saved.execute("SELECT id,sha256,size FROM media").fetchall()
                # Produce one standalone database file, not a snapshot needing a transient WAL.
                saved.execute("PRAGMA journal_mode=DELETE")
                saved.commit()
        os.chmod(stage / "workspace.sqlite3", 0o600)
        (stage / "media").mkdir(mode=0o700)
        entries = []
        for ident, expected, size in media:
            if not re.fullmatch(r"[A-Za-z0-9_-]{1,200}", ident):
                raise ValueError("Unsafe media identity in database")
            original = source / "media" / ident
            if original.is_symlink():
                raise ValueError("Media must not be a symlink")
            target = stage / "media" / ident
            shutil.copyfile(original, target)
            os.chmod(target, 0o600)
            actual = hashlib.sha256(target.read_bytes()).hexdigest()
            if actual != expected or target.stat().st_size != size:
                raise ValueError("Media verification failed for " + ident)
            with target.open("rb") as copied:
                os.fsync(copied.fileno())
            entries.append({"id": ident, "sha256": actual, "size": size})
        manifest = {"schema": 1, "database_sha256": hashlib.sha256((stage / "workspace.sqlite3").read_bytes()).hexdigest(),
                    "media": entries, "credentials_included": False}
        manifest_path = stage / "backup.json"
        fd = os.open(manifest_path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(fd, "w") as file:
            json.dump(manifest, file, indent=2)
            file.write("\n")
            file.flush()
            os.fsync(file.fileno())
        with (stage / "workspace.sqlite3").open("rb") as database_file:
            os.fsync(database_file.fileno())
        for directory in (stage / "media", stage):
            descriptor = os.open(directory, os.O_RDONLY)
            try:
                os.fsync(descriptor)
            finally:
                os.close(descriptor)
        if destination.exists():
            raise ValueError("Backup destination appeared during backup; choose a new directory")
        stage.rename(destination)
        descriptor = os.open(destination.parent, os.O_RDONLY)
        try:
            os.fsync(descriptor)
        finally:
            os.close(descriptor)
        return manifest
    finally:
        if stage.exists():
            shutil.rmtree(stage)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--data", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    result = backup(args.data, args.output)
    print("Verified backup: " + str(args.output.resolve()))
    print("Media files: " + str(len(result["media"])))


if __name__ == "__main__":
    main()
