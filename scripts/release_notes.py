"""Generate readable release notes from the exact Git commit range being released."""

import re
import subprocess
import sys


def git(*args: str) -> str:
    return subprocess.check_output(["git", *args], text=True).strip()


def notes(version: str, repository: str, target: str, previous: str = "") -> str:
    if not re.fullmatch(r"\d+\.\d+\.\d+", version):
        raise ValueError("Expected a numeric release version")
    if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repository):
        raise ValueError("Expected an owner/repository name")
    target = git("rev-parse", "--verify", f"{target}^{{commit}}")
    if previous:
        base = git("rev-parse", "--verify", f"{previous}^{{commit}}")
        subprocess.run(["git", "merge-base", "--is-ancestor", base, target], check=True)
        revision_range = f"{base}..{target}"
    else:
        revision_range = target

    log = git("log", "--no-merges", "--reverse", "--format=%H%x09%s", revision_range, "--")
    url = f"https://github.com/{repository}"
    lines = [f"# What's new in {version}", "", "Minecraft 26.2 · Fabric · Java 25", ""]
    for entry in log.splitlines():
        commit, subject = entry.split("\t", 1)
        subject = re.sub(r"([\\`*_{}\[\]<>])", r"\\\1", subject).replace("@", "&#64;")
        lines.append(f"- {subject} ([{commit[:7]}]({url}/commit/{commit}))")
    if not log:
        lines.append("- No new non-merge commits in this release.")
    lines += ["", f"**Built from:** [{target[:7]}]({url}/commit/{target})"]
    if previous:
        lines += ["", f"[Full changelog]({url}/compare/{previous}...v{version})"]
    else:
        lines += ["", "Initial release."]
    return "\n".join(lines) + "\n"


if __name__ == "__main__":
    if len(sys.argv) not in (4, 5):
        sys.exit("Usage: release_notes.py VERSION OWNER/REPO TARGET [PREVIOUS_TAG]")
    print(notes(*sys.argv[1:]), end="")
