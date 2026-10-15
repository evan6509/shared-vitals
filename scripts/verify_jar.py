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
        jar.getinfo("com/evanchubbuck/sharedvitals/SharedVitalsState.class")
        if metadata.get("mixins") != ["shared_vitals.mixins.json"]:
            raise ValueError("Missing shared vitals mixin configuration")
        mixins = json.loads(jar.read("shared_vitals.mixins.json"))
        if not mixins.get("required") or mixins.get("injectors", {}).get("defaultRequire") != 1:
            raise ValueError("Gameplay mixins must be required")
        expected_mixins = {
            "FoodDataAccessor", "FoodDataMixin", "LivingEntityMixin", "PlayerAccessor",
            "ServerPlayerGameModeMixin", "ServerPlayerMixin",
        }
        if set(mixins.get("mixins", [])) != expected_mixins:
            raise ValueError("Unexpected gameplay mixin list")
        for mixin in expected_mixins:
            jar.getinfo((mixins["package"] + "." + mixin).replace(".", "/") + ".class")
        if any(name.startswith("com/evanchubbuck/sharedvitals/SharedVitalsGameTests") for name in jar.namelist()):
            raise ValueError("GameTest classes must not be shipped in the mod JAR")
        if metadata["depends"]["minecraft"] != "26.2":
            raise ValueError("Unexpected Minecraft version")
    print(f"Verified {path.name}: {expected_version}")


if __name__ == "__main__":
    if len(sys.argv) != 3:
        sys.exit("Usage: verify_jar.py JAR EXPECTED_VERSION")
    verify(Path(sys.argv[1]), sys.argv[2])
