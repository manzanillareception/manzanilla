"""PR publication policy, including committed Git-tree behavior."""
from pathlib import Path
import subprocess

import pytest

from scripts.release_policy import check_repository, release_impact, validate_change


VERSIONS = {"android/VERSION", "android/VERSION_CODE"}


@pytest.mark.parametrize("path", [
    "android/hotel/src/main/java/HotelActivity.kt", "android/hotel/src/test/java/UpdaterTest.kt",
    "ui/reception/reception.js", "package.json", "package-lock.json", "scripts/build-reception.cjs",
    "scripts/release_policy.py", "scripts/release_policy_check.py", "scripts/publish_release.py", "scripts/verify-signed-upgrade.sh",
    "scripts/run-updater-emulator.sh",
    ".github/workflows/publish.yml", ".github/workflows/release.yml", ".github/workflows/verify.yml",
])
def test_release_inputs_require_both_version_changes(path):
    assert release_impact(path)
    with pytest.raises(ValueError, match="both"):
        validate_change([path], "17.0.28", "58", "17.0.29", "59")
    assert validate_change(VERSIONS | {path}, "17.0.28", "58", "17.0.29", "59")


def test_docs_backend_and_test_only_changes_do_not_require_an_apk():
    paths = ["README.md", "docs/update-delivery.md", "AGENTS.md", "cloud/operations.py",
             "tests/test_operations.py", "tests/test_release_policy.py", "android-guide.md"]
    assert not any(release_impact(path) for path in paths)
    assert validate_change(paths, "17.0.28", "58", "17.0.28", "58") is False


@pytest.mark.parametrize("paths,name,code", [
    ({"android/VERSION"}, "17.0.29", "59"),
    ({"android/VERSION_CODE"}, "17.0.29", "59"),
    (VERSIONS, "17.0.28", "59"),
    (VERSIONS, "17.0.29", "58"),
    (VERSIONS, "17.0.27", "60"),
    (VERSIONS, "17.0.30", "57"),
    (VERSIONS, "17.0.29-rc.1", "59"),
    (VERSIONS, "17.00.29", "59"),
    (VERSIONS, "v17.0.29", "59"),
    (VERSIONS, "17.0.29", "059"),
    (VERSIONS, "17.0.29", "0"),
    (VERSIONS, "17.0.29", "-59"),
    (VERSIONS, "17.0.29", "2100000001"),
])
def test_invalid_partial_unchanged_or_lower_identity_is_rejected(paths, name, code):
    with pytest.raises(ValueError):
        validate_change(paths, "17.0.28", "58", name, code)


def test_semantic_order_is_numeric_and_version_only_bump_is_valid():
    assert validate_change(VERSIONS, "17.9.9", "58", "17.10.0", "59")


def git(path: Path, *arguments: str) -> str:
    return subprocess.run(["git", "-C", str(path), *arguments], check=True,
                          capture_output=True, text=True).stdout.strip()


def commit(path: Path, message: str) -> str:
    git(path, "add", ".")
    git(path, "-c", "user.name=Fixture", "-c", "user.email=fixture@example.invalid",
        "-c", "commit.gpgsign=false", "commit", "-qm", message)
    return git(path, "rev-parse", "HEAD")


def test_git_policy_reads_committed_versions_and_detects_a_missing_bump(tmp_path):
    git(tmp_path, "init", "-q")
    (tmp_path / "android").mkdir()
    (tmp_path / "android/VERSION").write_text("17.0.28\n")
    (tmp_path / "android/VERSION_CODE").write_text("58\n")
    base = commit(tmp_path, "base")
    (tmp_path / "android/Updater.kt").write_text("// synthetic app change\n")
    candidate = commit(tmp_path, "app without bump")
    # Uncommitted edits must not let an unversioned PR pass.
    (tmp_path / "android/VERSION").write_text("17.0.29\n")
    (tmp_path / "android/VERSION_CODE").write_text("59\n")
    with pytest.raises(ValueError, match="both"):
        check_repository(tmp_path, base, candidate)
    bumped = commit(tmp_path, "explicit candidate")
    assert check_repository(tmp_path, base, bumped)


def test_git_policy_cannot_hide_a_removed_app_file_as_a_docs_rename(tmp_path):
    git(tmp_path, "init", "-q")
    (tmp_path / "android").mkdir()
    (tmp_path / "android/VERSION").write_text("17.0.28\n")
    (tmp_path / "android/VERSION_CODE").write_text("58\n")
    (tmp_path / "android/Updater.kt").write_text("// synthetic source\n")
    base = commit(tmp_path, "base")
    (tmp_path / "docs").mkdir()
    git(tmp_path, "mv", "android/Updater.kt", "docs/Updater.kt")
    candidate = commit(tmp_path, "moved source")
    with pytest.raises(ValueError, match="both"):
        check_repository(tmp_path, base, candidate)


def test_git_policy_fails_closed_when_the_base_is_missing(tmp_path):
    git(tmp_path, "init", "-q")
    (tmp_path / "android").mkdir()
    (tmp_path / "android/VERSION").write_text("17.0.30\n")
    (tmp_path / "android/VERSION_CODE").write_text("60\n")
    candidate = commit(tmp_path, "base")
    with pytest.raises(subprocess.CalledProcessError):
        check_repository(tmp_path, "does-not-exist", candidate)


@pytest.mark.parametrize("prefix,suffix", [("nvapi-", ""), ("eyJ", ".")])
def test_credential_scan_excludes_fixtures_and_never_prints_matches(tmp_path, prefix, suffix):
    import sys

    git(tmp_path, "init", "-q")
    (tmp_path / "android/hotel").mkdir(parents=True)
    (tmp_path / "android/hotel/build.gradle.kts").write_text('applicationId = "dev.orange.hotel"\n')
    (tmp_path / "docs").mkdir()
    synthetic = prefix + "a" * 24 + suffix
    (tmp_path / "docs/example.txt").write_text(synthetic)
    commit(tmp_path, "base")
    guard = Path(__file__).resolve().parents[1] / "scripts/release_policy_check.py"
    def scan():
        return subprocess.run([sys.executable, str(guard)], cwd=tmp_path, text=True, capture_output=True)
    assert scan().returncode == 0  # documentation fixtures are excluded
    (tmp_path / "app.py").write_text(synthetic)
    commit(tmp_path, "synthetic literal")
    failed = scan()
    assert failed.returncode == 1
    assert "app.py" in failed.stderr and synthetic not in failed.stderr


def test_credential_scan_failure_does_not_pass(tmp_path, monkeypatch, capsys):
    import importlib.util
    from types import SimpleNamespace

    (tmp_path / "android/hotel").mkdir(parents=True)
    (tmp_path / "android/hotel/build.gradle.kts").write_text('applicationId = "dev.orange.hotel"\n')
    guard = Path(__file__).resolve().parents[1] / "scripts/release_policy_check.py"
    spec = importlib.util.spec_from_file_location("fixture_release_guard", guard)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    def fake_run(argv, **kwargs):
        return SimpleNamespace(stdout=str(tmp_path), returncode=0) if argv[1] == "rev-parse" else SimpleNamespace(returncode=2)
    monkeypatch.setattr(module.subprocess, "run", fake_run)
    assert module.main() == 1
    assert "scan could not complete" in capsys.readouterr().err
