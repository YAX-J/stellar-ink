"""Windows 拉取与生产成功回执回归，仅使用临时文件和模拟 SSH／age。"""
import hashlib
import importlib.util
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
from unittest.mock import Mock, patch


def load(name):
    spec = importlib.util.spec_from_file_location(name, Path(__file__).with_name(name + ".py"))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


client, server = load("pull-backup"), load("backup-pull-server")


class BackupPullTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.directory = Path(self.temp.name)
        self.payload = b"test-only-encrypted-backup"
        self.name = "stellar-20261110T030000Z.tar.age"
        self.digest = hashlib.sha256(self.payload).hexdigest()
        self.info = dict(name=self.name, path="/backup/" + self.name, size=len(self.payload), sha256=self.digest)
        (self.directory / "recovery-key.txt").write_text("test-only-identity")

    def run_process(self, command, **kwargs):
        if command[0] == "ssh":
            kwargs["stdout"].write(self.payload)
        return subprocess.CompletedProcess(command, 0)

    def test_download_validates_before_receipt_and_can_retry_receipt(self):
        ack = Mock(side_effect=RuntimeError("回执连接中断"))
        with patch.object(client.subprocess, "run", side_effect=self.run_process):
            with self.assertRaises(RuntimeError):
                client.download(self.info, self.directory, ["ssh"], "age", ack)
            self.assertEqual((self.directory / self.name).read_bytes(), self.payload)
            ack.side_effect = None
            client.download(self.info, self.directory, ["ssh"], "age", ack)
        self.assertEqual(ack.call_count, 2)
        self.assertFalse(list(self.directory.glob("*.partial")))

    def test_corruption_and_decryption_failure_never_acknowledge(self):
        for mode in ("corrupt", "decrypt", "disconnect"):
            with self.subTest(mode=mode):
                ack = Mock()
                def run(command, **kwargs):
                    if command[0] == "ssh":
                        kwargs["stdout"].write(b"broken" if mode == "corrupt" else self.payload)
                    if (command[0] == "age" and mode == "decrypt") or mode == "disconnect":
                        raise subprocess.CalledProcessError(1, command)
                    return subprocess.CompletedProcess(command, 0)
                with patch.object(client.subprocess, "run", side_effect=run):
                    with self.assertRaises((ValueError, subprocess.CalledProcessError)):
                        client.download(self.info, self.directory, ["ssh"], "age", ack)
                ack.assert_not_called()
                self.assertFalse((self.directory / self.name).exists())
                self.assertFalse(list(self.directory.glob("*.partial")))

    def test_existing_backup_is_not_overwritten(self):
        (self.directory / self.name).write_bytes(b"existing-file")
        ack = Mock()
        with patch.object(client.subprocess, "run", side_effect=self.run_process):
            with self.assertRaisesRegex(ValueError, "拒绝覆盖"):
                client.download(self.info, self.directory, ["ssh"], "age", ack)
        self.assertEqual((self.directory / self.name).read_bytes(), b"existing-file")
        ack.assert_not_called()

    def test_metadata_cannot_escape_directory_and_init_refuses_overwrite(self):
        for name in ("../backup.tar.age", "C:/backup.tar.age", "backup.txt"):
            with self.assertRaises(ValueError):
                client.validate_info({**self.info, "name": name})
        with self.assertRaises(FileExistsError):
            client.initialize(self.directory, "age-keygen")

    def test_receipt_requires_matching_hash_and_uses_backup_timestamp(self):
        (self.directory / self.name).write_bytes(self.payload)
        (self.directory / (self.name + ".sha256")).write_text(self.digest + "  " + self.name + "\n")
        os.utime(self.directory / self.name, (1000000000, 1000000000))
        metrics = self.directory / "metrics"
        with self.assertRaises(ValueError):
            server.acknowledge(self.directory, metrics, self.name, "0" * 64)
        self.assertFalse(metrics.exists())
        server.acknowledge(self.directory, metrics, self.name, self.digest)
        target = metrics / "stellar_backup_last_success_timestamp_seconds.prom"
        self.assertEqual(float(target.read_text().split()[1]), 1000000000)
        (self.directory / self.name).write_bytes(b"tampered")
        with self.assertRaisesRegex(ValueError, "源备份"):
            server.acknowledge(self.directory, metrics, self.name, self.digest)
        self.assertEqual(float(target.read_text().split()[1]), 1000000000)

    @unittest.skipUnless(shutil.which("age") and shutil.which("age-keygen"), "需要 age 与 age-keygen")
    def test_real_age_download_decrypt_and_acknowledgement(self):
        local = self.directory / "windows"
        client.initialize(local, "age-keygen")
        plaintext = self.directory / "sample.txt"
        plaintext.write_text("星笺加密备份演练，不包含真实凭据", encoding="utf-8")
        encrypted = self.directory / self.name
        subprocess.run(["age", "-R", str(local / "backup-recipients.txt"), "-o", str(encrypted), str(plaintext)], check=True)
        self.payload = encrypted.read_bytes()
        digest = hashlib.sha256(self.payload).hexdigest()
        (self.directory / (self.name + ".sha256")).write_text(digest + "  " + self.name + "\n")
        info = server.describe(self.directory, self.name)
        info["path"] = "/backup/" + self.name  # 协作端实际运行在 Linux，模拟其绝对路径。
        real_run = subprocess.run
        def run(command, **kwargs):
            if command[0] == "ssh":
                kwargs["stdout"].write(self.payload)
                return subprocess.CompletedProcess(command, 0)
            return real_run(command, **kwargs)
        metrics = self.directory / "metrics"
        with patch.object(client.subprocess, "run", side_effect=run):
            client.download(info, local, ["ssh"], "age",
                            lambda name, checksum: server.acknowledge(self.directory, metrics, name, checksum))
        self.assertEqual((local / self.name).read_bytes(), self.payload)
        self.assertTrue((metrics / "stellar_backup_last_success_timestamp_seconds.prom").is_file())


if __name__ == "__main__":
    unittest.main()
