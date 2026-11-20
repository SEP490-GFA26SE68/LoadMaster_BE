"""Read-only packaging checks; never start Keycloak or load application Settings."""
import json
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[4]
THEMES = ROOT / "LoadMasterService/keycloak-themes"
COMPOSE = ROOT / "LoadMasterService/docker-compose.yml"


class BuiltinLoginThemeTest(unittest.TestCase):
    def test_selected_login_theme_is_packaged(self):
        realm = json.loads((ROOT / "keycloak/import/loadmaster-realm.json").read_text(encoding="utf-8"))
        registry = json.loads((THEMES / "META-INF/keycloak-themes.json").read_text(encoding="utf-8"))
        packaged = {theme["name"] for theme in registry["themes"] if "login" in theme["types"]}
        self.assertIn(realm["loginTheme"], packaged)
        selected = THEMES / "theme" / realm["loginTheme"] / "login"
        self.assertTrue((selected / "theme.properties").is_file())
        chain = [selected]
        seen = {realm["loginTheme"]}
        while True:
            props = dict(re.findall(r"^([A-Za-z]+)=(.*)$", (chain[-1] / "theme.properties").read_text(encoding="utf-8"), re.M))
            parent = props.get("parent")
            if not parent:
                break
            self.assertNotIn(parent, seen)
            seen.add(parent)
            chain.append(THEMES / "theme" / parent / "login")
        for resource in ["login.ftl", "login-update-password.ftl", "template.ftl"]:
            self.assertTrue(any((entry / resource).is_file() for entry in chain), resource)
        self.assertTrue((selected / "resources/css/styles.css").is_file())

    def test_image_version_matches_packaged_theme_metadata(self):
        image = re.search(r"image:\s*quay.io/keycloak/keycloak:([0-9.]+)", COMPOSE.read_text(encoding="utf-8"))
        self.assertIsNotNone(image)
        metadata = THEMES / "META-INF/maven/org.keycloak/keycloak-themes/pom.properties"
        version = re.search(r"^version=(.*)$", metadata.read_text(encoding="utf-8"), re.M)
        self.assertIsNotNone(version)
        self.assertEqual(image.group(1), version.group(1).strip())
        self.assertEqual(image.group(1), "26.7.4")

    def test_builtin_setup_does_not_mount_removed_custom_theme(self):
        self.assertNotIn("../keycloak/themes:/opt/keycloak/themes", COMPOSE.read_text(encoding="utf-8"))
        self.assertFalse((ROOT / "keycloak/themes/loadmaster/login/theme.properties").exists())


if __name__ == "__main__":
    unittest.main()
