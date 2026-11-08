"""Build the source matrix and publish a signed catalog from completed builds."""
import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
from urllib.error import HTTPError
from urllib.request import HTTPRedirectHandler, Request, build_opener

SOURCES = Path("catalog-sources.json")
PUBLIC_KEY_FILE = Path("app/src/main/assets/ai-pages-public.txt")
ASSETS = ("index.html", "app.js", "style.css")
MAX_SIZE = 900_000


def sources():
    entries = json.loads(SOURCES.read_text(encoding="utf-8"))
    if not isinstance(entries, list) or not entries:
        raise ValueError("List at least one tool in catalog-sources.json.")
    ids = set()
    for entry in entries:
        if not isinstance(entry, dict) or set(entry) != {"id", "title", "repository", "ref", "private"}:
            raise ValueError("Each source must provide id, title, repository, ref and private.")
        tool_id = entry["id"]
        if not isinstance(tool_id, str) or not re.fullmatch(r"[a-z][a-z0-9-]{0,39}", tool_id) or tool_id in ids:
            raise ValueError("Tool IDs must be unique and match [a-z][a-z0-9-]{0,39}.")
        ids.add(tool_id)
        title = entry["title"]
        if not isinstance(title, str) or not title.strip() or len(title.encode("utf-16-le")) > 160:
            raise ValueError(f"Invalid title for {tool_id}.")
        repo = entry["repository"]
        ref = entry["ref"]
        if not isinstance(repo, str) or not re.fullmatch(r"[A-Za-z0-9-]+/[A-Za-z0-9_.-]+", repo):
            raise ValueError(f"Invalid source repository for {tool_id}.")
        if not isinstance(ref, str) or not ref.strip() or not isinstance(entry["private"], bool):
            raise ValueError(f"Invalid source ref or privacy setting for {tool_id}.")
    return entries


def json_bytes(value):
    return json.dumps(value, ensure_ascii=False, separators=(",", ":")).encode()


def signed_catalog(entries, artifacts, key):
    pages = []
    for entry in entries:
        files = {}
        directory = artifacts / f"page-{entry['id']}"
        for name in ASSETS:
            file = directory / name
            if not file.is_file() or file.is_symlink():
                raise ValueError(f"Missing build output {file}.")
            if file.stat().st_size > MAX_SIZE:
                raise ValueError(f"Build output {file} exceeds the catalog size limit.")
            files[name] = file.read_text(encoding="utf-8")
        pages.append({"id": entry["id"], "title": entry["title"], "files": files})

    revision = hashlib.sha256(json_bytes(pages)).hexdigest()
    payload = json_bytes({"bridgeVersion": 1, "revision": revision, "pages": pages})
    signature = subprocess.run(
        ["openssl", "dgst", "-sha256", "-sign", str(key)],
        input=payload, capture_output=True, check=True,
    ).stdout
    envelope = json_bytes({
        "payload": base64.b64encode(payload).decode(),
        "signature": base64.b64encode(signature).decode(),
    })
    if len(envelope) > MAX_SIZE:
        raise ValueError("The catalog exceeds the Android shell's size limit.")
    return revision, envelope


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, msg, headers, newurl):
        return None


def github(method, body=None):
    repository = os.environ["GITHUB_REPOSITORY"]
    request = Request(
        f"https://api.github.com/repos/{repository}/contents/feed/catalog.json",
        data=None if body is None else json_bytes(body),
        headers={
            "Accept": "application/vnd.github+json",
            "Authorization": f"Bearer {os.environ['GITHUB_TOKEN']}",
            "Content-Type": "application/json",
            "X-GitHub-Api-Version": "2022-11-28",
        },
        method=method,
    )
    with build_opener(NoRedirect()).open(request, timeout=30) as response:
        return json.load(response)


def publish(entries, artifacts):
    private_key = os.environ.get("AI_PAGES_SIGNING_KEY", "").strip()
    if not private_key:
        raise ValueError("Set the AI_PAGES_SIGNING_KEY Actions secret in AI Pages.")
    with tempfile.TemporaryDirectory() as directory:
        key = Path(directory) / "private.pem"
        key.touch(mode=0o600)
        key.write_text(private_key + "\n", encoding="utf-8")
        expected = base64.b64decode("".join(PUBLIC_KEY_FILE.read_text(encoding="utf-8").split()), validate=True)
        actual = subprocess.run(
            ["openssl", "pkey", "-in", str(key), "-pubout", "-outform", "DER"],
            capture_output=True, check=True,
        ).stdout
        if actual != expected:
            raise ValueError("AI_PAGES_SIGNING_KEY does not match the public key committed to AI Pages.")
        revision, envelope = signed_catalog(entries, artifacts, key)

    try:
        existing = github("GET")
    except HTTPError as error:
        if error.code != 404:
            raise
        existing = None
    if existing and base64.b64decode(existing["content"]) == envelope:
        print("The catalog is already up to date.")
        return
    body = {
        "branch": "main",
        "message": f"Publish tool catalog ({revision[:12]})",
        "content": base64.b64encode(envelope).decode(),
    }
    if existing:
        body["sha"] = existing["sha"]
    github("PUT", body)
    print(f"Published {len(entries)} tool(s) to feed/catalog.json.")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--artifacts", type=Path)
    args = parser.parse_args()
    entries = sources()
    if args.artifacts is None:
        print(json_bytes(entries).decode())
    else:
        publish(entries, args.artifacts)


if __name__ == "__main__":
    main()
