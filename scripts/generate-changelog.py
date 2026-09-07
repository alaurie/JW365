#!/usr/bin/env python3
import sys, subprocess, os

tag = sys.argv[1] if len(sys.argv) > 1 else ""
prev_tag = sys.argv[2] if len(sys.argv) > 2 else ""

if not prev_tag:
    tags = subprocess.run(["git", "tag", "--sort=-creatordate"], capture_output=True, text=True).stdout.strip().splitlines()
    filtered = [t for t in tags if t != tag]
    if filtered:
        prev_tag = filtered[0]

range_spec = f"{prev_tag}..HEAD" if prev_tag else "HEAD"

cmd = ["git", "log", range_spec, "--pretty=format:%h|%s|%an"]
res = subprocess.run(cmd, capture_output=True, text=True)
lines = [l.split("|") for l in res.stdout.strip().splitlines() if l]

features = []
fixes = []
docs = []
other = []

for item in lines:
    if len(item) < 3:
        continue
    hash_id, msg, author = item[0], item[1], item[2]
    entry = f"- [`{hash_id}`](https://github.com/alaurie/JW365/commit/{hash_id}) {msg} ({author})"
    lower = msg.lower()
    if lower.startswith("feat"):
        features.append(entry)
    elif lower.startswith("fix"):
        fixes.append(entry)
    elif lower.startswith("docs"):
        docs.append(entry)
    else:
        other.append(entry)

output = []
output.append(f"## Release {tag}\n")

if features:
    output.append("### 🚀 Features & Improvements")
    output.extend(features)
    output.append("")

if fixes:
    output.append("### 🐛 Bug Fixes & Stability")
    output.extend(fixes)
    output.append("")

if docs:
    output.append("### 📚 Documentation")
    output.extend(docs)
    output.append("")

if other:
    output.append("### 🔧 Other Changes")
    output.extend(other)
    output.append("")

checksums_path = "build/distributions/checksums-sha256.txt"
if os.path.exists(checksums_path):
    output.append("### 📦 Packages & SHA-256 Checksums")
    output.append("```text")
    with open(checksums_path) as f:
        output.append(f.read().strip())
    output.append("```\n")

if prev_tag:
    output.append(f"**Full Changelog**: https://github.com/alaurie/JW365/compare/{prev_tag}...{tag}")

result = "\n".join(output)
os.makedirs("build", exist_ok=True)
with open("build/release-notes.md", "w") as f:
    f.write(result)
print(result)
