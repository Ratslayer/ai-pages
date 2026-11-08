import base64
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
from unittest.mock import patch
from urllib.error import HTTPError

from scripts import publish_catalog


class CatalogPublicationTest(unittest.TestCase):
    def test_signs_all_tools_and_skips_unchanged_or_incomplete_builds(self):
        with tempfile.TemporaryDirectory() as temp:
            directory = Path(temp)
            key = directory / "private.pem"
            subprocess.run(
                ["openssl", "genpkey", "-algorithm", "RSA", "-pkeyopt", "rsa_keygen_bits:2048", "-out", str(key)],
                check=True, capture_output=True,
            )
            public_key = directory / "public.pem"
            subprocess.run(["openssl", "pkey", "-in", str(key), "-pubout", "-out", str(public_key)], check=True)
            public_file = directory / "public.txt"
            public_der = subprocess.run(
                ["openssl", "pkey", "-pubin", "-in", str(public_key), "-outform", "DER"],
                check=True, capture_output=True,
            ).stdout
            public_file.write_bytes(base64.b64encode(public_der))
            shell = Path(shutil.copytree(publish_catalog.SHELL, directory / "shell"))
            entries = [
                {"id": "calorie-watcher", "title": "Calorie Watcher"},
                {"id": "second-tool", "title": "Second Tool"},
            ]
            for entry in entries:
                output = directory / f"page-{entry['id']}"
                output.mkdir()
                for name in publish_catalog.ASSETS:
                    (output / name).write_text(entry["id"] + "/" + name, encoding="utf-8")

            existing = None
            writes = []

            def github(method, body=None):
                nonlocal existing
                if method == "GET":
                    if existing is None:
                        raise HTTPError("test://catalog", 404, "Missing", {}, None)
                    return existing
                self.assertEqual(method, "PUT")
                writes.append(body)
                existing = {"content": body["content"], "sha": "expected-file-sha"}

            with (
                patch.object(publish_catalog, "github", side_effect=github),
                patch.object(publish_catalog, "PUBLIC_KEY_FILE", public_file),
                patch.object(publish_catalog, "SHELL", shell),
                patch.dict(os.environ, {"AI_PAGES_SIGNING_KEY": key.read_text(encoding="utf-8")}),
            ):
                publish_catalog.publish(entries, directory)
                publish_catalog.publish(entries, directory)
                self.assertEqual(len(writes), 1)

                envelope = json.loads(base64.b64decode(writes[0]["content"]))
                payload = base64.b64decode(envelope["payload"])
                catalog = json.loads(payload)
                self.assertEqual(catalog["bridgeVersion"], 1)
                self.assertEqual([page["id"] for page in catalog["pages"]], [entry["id"] for entry in entries])
                self.assertEqual(catalog["pages"][1]["files"]["app.js"], "second-tool/app.js")
                self.assertEqual(set(catalog["shell"]), {"home", "settings"})
                for screen in catalog["shell"].values():
                    self.assertEqual(set(screen), set(publish_catalog.ASSETS))
                    self.assertIn("window.AiPages", screen["app.js"])
                signature = directory / "signature"
                signature.write_bytes(base64.b64decode(envelope["signature"]))
                verify = ["openssl", "dgst", "-sha256", "-verify", str(public_key), "-signature", str(signature)]
                subprocess.run(verify, input=payload, check=True, capture_output=True)
                self.assertNotEqual(subprocess.run(verify, input=payload + b" ", capture_output=True).returncode, 0)

                with (shell / "home" / "index.html").open("a", encoding="utf-8") as home:
                    home.write("<!-- new layout -->")
                publish_catalog.publish(entries, directory)
                self.assertEqual(len(writes), 2)
                updated = json.loads(base64.b64decode(writes[1]["content"]))
                updated = json.loads(base64.b64decode(updated["payload"]))
                self.assertNotEqual(updated["revision"], catalog["revision"])
                self.assertEqual(updated["pages"], catalog["pages"])
                self.assertIn("<!-- new layout -->", updated["shell"]["home"]["index.html"])

                (directory / "page-second-tool" / "app.js").unlink()
                with self.assertRaisesRegex(ValueError, "Missing build output"):
                    publish_catalog.publish(entries, directory)
                self.assertEqual(len(writes), 2)

                publish_catalog.publish(entries[:1], directory)
                self.assertEqual(writes[2]["sha"], "expected-file-sha")

                public_file.write_bytes(base64.b64encode(public_der[:-1] + b"\x00"))
                with self.assertRaisesRegex(ValueError, "does not match the public key"):
                    publish_catalog.publish(entries[:1], directory)
                self.assertEqual(len(writes), 3)
