#!/usr/bin/env python3
"""Check repository Markdown targets and local OpenAPI 3.1 references."""
import json
from pathlib import Path
import re
import subprocess
import sys
from urllib.parse import unquote, urlsplit

root = Path(__file__).resolve().parent.parent
files = subprocess.check_output([
    "git", "ls-files", "-co", "--exclude-standard", "-z",
], cwd=root).decode().split("\0")
errors = []
for name in sorted(set(files)):
    path = root / name
    if not name.endswith(".md") or not path.is_file():
        continue
    # Markdown code samples do not establish repository links.
    text = re.sub(r"(?ms)^```.*?^```[^\n]*", "", path.read_text())
    for target in re.findall(r"\]\(([^)]+)\)", text):
        target = target.strip("<>")
        if urlsplit(target).scheme:
            continue
        relative = unquote(target.split("#")[0])
        if relative and not (path.parent / relative).exists():
            errors.append(f"{name}: missing {target}")

# JSON is a YAML subset; keeping this source as JSON allows standard-library
# validation without installing a second parser into the user's environment.
api = json.loads((root / "spec/openapi.yaml").read_text())
if api.get("openapi") != "3.1.0" or not api.get("info", {}).get("version"):
    errors.append("OpenAPI: missing version metadata")
operations = set()
def walk(value):
    if isinstance(value, dict):
        if "$ref" in value:
            ref = value["$ref"]
            if not ref.startswith("#/"):
                errors.append("OpenAPI: external reference requires manual review: " + ref)
            else:
                node = api
                try:
                    for token in ref[2:].split("/"):
                        node = node[token.replace("~1", "/").replace("~0", "~")]
                except (KeyError, TypeError):
                    errors.append("OpenAPI: unresolved " + ref)
        for child in value.values():
            walk(child)
    elif isinstance(value, list):
        for child in value:
            walk(child)
walk(api)
for path, item in api["paths"].items():
    for method, operation in item.items():
        if method not in {"get", "post", "delete", "put", "patch", "head", "options"}:
            continue
        identifier = operation.get("operationId")
        if not identifier or identifier in operations or not operation.get("responses"):
            errors.append(f"OpenAPI: missing/duplicate operation or responses: {method} {path}")
        operations.add(identifier)
if errors:
    print("\n".join(errors), file=sys.stderr)
    sys.exit(1)
print(f"PASS Markdown targets, OpenAPI JSON/metadata/local references/{len(operations)} unique operations")
