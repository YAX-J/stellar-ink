"""安全恢复与部署配置回归；仅使用临时目录和模拟数据。"""
import gzip
import importlib.util
import io
import json
from pathlib import Path
import tarfile
import tempfile
import unittest


def load(name):
    spec = importlib.util.spec_from_file_location(name, Path(__file__).with_name(name + ".py"))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


restore = load("restore-backup")
alerts = load("configure-alerts")
preflight = load("production-preflight")


class ProductionP0Test(unittest.TestCase):
    def smtp_values(self, host):
        return dict(SMTP_SMARTHOST=host, SMTP_FROM="same@example.invalid", SMTP_AUTH_USERNAME="same@example.invalid",
                    SMTP_AUTH_PASSWORD="test-only-secret", ALERT_EMAIL_TO="same@example.invalid")

    def test_465_uses_implicit_tls_with_certificate_validation(self):
        config = alerts.build_config(self.smtp_values("smtp.163.com:465"))
        self.assertFalse(config["global"]["smtp_require_tls"])
        self.assertEqual(config["global"]["smtp_smarthost"], "smtp.163.com:465")
        email = config["receivers"][0]["email_configs"][0]
        self.assertEqual(email["to"], config["global"]["smtp_from"])
        self.assertFalse(email["tls_config"]["insecure_skip_verify"])
        self.assertEqual(email["tls_config"]["min_version"], "TLS12")

    def test_other_ports_require_starttls_and_unsupported_addresses_are_rejected(self):
        for host in ("smtp.example.invalid:587", "smtp.example.invalid:25", "[::1]:587"):
            self.assertTrue(alerts.build_config(self.smtp_values(host))["global"]["smtp_require_tls"])
        for host in ("smtp.163.com:994", "smtp.163.com:0465", "smtp.163.com", "smtp.163.com:0",
                     "smtp.163.com:65536", "ssl://smtp.163.com:465", "smtp.163.com:465/path", "user@smtp.163.com:465"):
            with self.subTest(host=host), self.assertRaises(ValueError):
                alerts.build_config(self.smtp_values(host))

    def test_private_config_preserves_password_punctuation(self):
        with tempfile.TemporaryDirectory() as tmp:
            config = Path(tmp) / "alerts.env"
            config.write_text('SMTP_AUTH_PASSWORD=secret"\nALERT_EMAIL_TO="to@example.invalid"\n', encoding="utf-8")
            values = alerts.load_env(config)
            self.assertEqual(values["SMTP_AUTH_PASSWORD"], 'secret"')
            self.assertEqual(values["ALERT_EMAIL_TO"], "to@example.invalid")

    def package(self, root):
        source = root / "source"
        source.mkdir()
        (source / "env.backup").write_text("SA_TOKEN_JWT_SECRET=old-signing-key\nAI_SECRET_MASTER_KEY=preserve-me\n", encoding="utf-8")
        (source / "mysql.sql.gz").write_bytes(gzip.compress(b"CREATE TABLE example(id INT);"))
        (source / "data/qa-eval-reports").mkdir(parents=True)
        (source / "data/qa-eval-reports/report.json").write_text('{"review":"已复核"}', encoding="utf-8")
        restore.manifest(source)
        archive = root / "backup.tar.gz"
        with tarfile.open(archive, "w:gz") as tar:
            tar.add(source, arcname=".")
        return source, archive

    def test_restore_rotates_jwt_preserves_master_key_and_reports(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            _, archive = self.package(root)
            target = root / "restored"
            restore.extract_verified(archive, target)
            env = (target / ".env").read_text(encoding="utf-8")
            self.assertNotIn("old-signing-key", env)
            self.assertIn("AI_SECRET_MASTER_KEY=preserve-me", env)
            self.assertTrue((target / "data/qa-eval-reports/report.json").is_file())
            with self.assertRaises(FileExistsError):
                restore.extract_verified(archive, target)

    def test_corruption_is_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            source, archive = self.package(root)
            (source / "mysql.sql.gz").write_bytes(b"changed")
            with tarfile.open(archive, "w:gz") as tar:
                tar.add(source, arcname=".")
            with self.assertRaisesRegex(ValueError, "清单校验"):
                restore.extract_verified(archive, root / "restored")

    def test_path_escape_and_links_are_rejected(self):
        for name, kind in (("../outside", tarfile.REGTYPE), ("/outside", tarfile.REGTYPE), ("link", tarfile.SYMTYPE)):
            with tempfile.TemporaryDirectory() as tmp:
                root = Path(tmp)
                archive = root / "malicious.tar.gz"
                with tarfile.open(archive, "w:gz") as tar:
                    member = tarfile.TarInfo(name)
                    member.type = kind
                    member.linkname = "../outside"
                    tar.addfile(member, io.BytesIO())
                with self.assertRaises(ValueError):
                    restore.extract_verified(archive, root / "target")

    def test_default_credentials_and_incomplete_smtp_are_rejected(self):
        self.assertTrue(preflight.check({"SPRING_PROFILES_ACTIVE": "dev", "NACOS_PASSWORD": "nacos"}))
        with self.assertRaises(ValueError):
            alerts.build_config({"ALERT_EMAIL_TO": "test@example.invalid"})
        values = dict(SMTP_SMARTHOST="smtp.example.invalid:587", SMTP_FROM="from@example.invalid",
                      SMTP_AUTH_USERNAME="sender", SMTP_AUTH_PASSWORD='secret"with\\characters', ALERT_EMAIL_TO="to@example.invalid")
        config = json.loads(json.dumps(alerts.build_config(values)))
        self.assertTrue(config["global"]["smtp_require_tls"])
        self.assertTrue(config["receivers"][0]["email_configs"][0]["send_resolved"])
        self.assertEqual(config["global"]["smtp_auth_password"], values["SMTP_AUTH_PASSWORD"])


if __name__ == "__main__":
    unittest.main()
