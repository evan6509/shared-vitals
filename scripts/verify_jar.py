"""Reject a release JAR with missing entrypoint or incorrect mod metadata."""

import json
from pathlib import Path
import sys
from zipfile import ZipFile


def verify(path: Path, expected_version: str) -> None:
    with ZipFile(path) as jar:
        metadata = json.loads(jar.read("fabric.mod.json"))
        expected = {
            "id": "shared_vitals",
            "name": "Shared Vitals",
            "version": expected_version,
        }
        for key, value in expected.items():
            if metadata.get(key) != value:
                raise ValueError(f"Unexpected {key}: {metadata.get(key)!r}; expected {value!r}")
        entrypoint = "com.evanchubbuck.sharedvitals.SharedVitalsMod"
        if metadata.get("entrypoints", {}).get("main") != [entrypoint]:
            raise ValueError("Unexpected main entrypoint")
        jar.getinfo(entrypoint.replace(".", "/") + ".class")
        if metadata["depends"]["minecraft"] != "26.2":
            raise ValueError("Unexpected Minecraft version")
    print(f"Verified {path.name}: {expected_version}")


if __name__ == "__main__":
    if len(sys.argv) != 3:
        sys.exit("Usage: verify_jar.py JAR EXPECTED_VERSION")
    verify(Path(sys.argv[1]), sys.argv[2])
