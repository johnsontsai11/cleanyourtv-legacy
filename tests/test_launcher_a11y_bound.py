"""The launcher installers warn when an accessibility service of the launcher is switched on but not bound.

Android can keep a service in enabled_accessibility_services and never bind it again after the APK is replaced
(Sony BRAVIA 2015, Android 7: until a reboot). build-and-install.sh and .ps1 send the same check to the TV, which
prints `a11y-check:` and the services still unbound. Here the TV side runs in a POSIX shell against fake
`settings` and `dumpsys`, the two scripts' copies are compared, and the bash side's reading of tv.py output is
checked against the command echo, CRLF and a missing answer.

Run from the repository root: python3 -m unittest discover -s tests -v
"""
import os
from pathlib import Path
import shutil
import stat
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[1]
SH = ROOT / "launcher" / "build-and-install.sh"
PS1 = ROOT / "launcher" / "build-and-install.ps1"

BOUND = """  * ServiceRecord{f4ec332 u0 com.gathin.tvhome/.SettingsGuardService}
    intent={cmp=com.gathin.tvhome/.SettingsGuardService}
  * ConnectionRecord{ea92894 u0 CR FGSA com.gathin.tvhome/.SettingsGuardService:@86884e7}
"""
# Unbound: only the connection lines name the service, never a ServiceRecord.
UNBOUND = """ACTIVITY MANAGER SERVICES (dumpsys activity services)
  Connection bindings to services:
    binding=AppBindRecord{30a2585 com.gathin.tvhome/.SettingsGuardService:system}
"""


def quoted(path, prefix, quote):
    """The quoted value of the line in `path` that starts with `prefix`."""
    line = next(l for l in path.read_text(encoding="utf-8").splitlines() if l.startswith(prefix))
    value = line[len(prefix):]
    assert value[0] == quote and value[-1] == quote, line
    return value[1:-1]


def sh_check():
    return quoted(SH, "CHECK=", "'")


def run_on_fake_tv(enabled, a11y_on, dumpsys):
    """Runs the TV side with `settings` and `dumpsys` answering as given, and `sleep` instant."""
    with tempfile.TemporaryDirectory() as tmp:
        bin_dir = Path(tmp)
        (bin_dir / "dumpsys.txt").write_text(dumpsys, encoding="utf-8")
        fakes = {
            "settings": f"""#!/bin/sh
case "$3" in enabled_accessibility_services) echo '{enabled}' ;; accessibility_enabled) echo {a11y_on} ;; esac
""",
            "dumpsys": f"#!/bin/sh\ncat '{bin_dir / 'dumpsys.txt'}'\n",
            "sleep": "#!/bin/sh\n:\n",
        }
        for name, body in fakes.items():
            path = bin_dir / name
            path.write_text(body, encoding="utf-8")
            path.chmod(path.stat().st_mode | stat.S_IEXEC)
        env = dict(os.environ, PATH=f"{bin_dir}{os.pathsep}{os.environ['PATH']}")
        return subprocess.run(["sh", "-c", sh_check()], capture_output=True, text=True, timeout=30,
                              env=env, check=True).stdout.strip()


@unittest.skipUnless(shutil.which("sh") and shutil.which("bash"), "needs a POSIX shell")
class A11yBoundTest(unittest.TestCase):
    def test_the_powershell_check_is_the_bash_check(self):
        self.assertEqual(quoted(PS1, "$check = ", "'"), sh_check())

    def test_the_tv_side(self):
        guard = "com.gathin.tvhome/com.gathin.tvhome.SettingsGuardService"
        cases = [
            (guard, 1, BOUND, "a11y-check:"),
            ("com.gathin.tvhome/.SettingsGuardService", 1, BOUND, "a11y-check:"),
            (guard, 1, UNBOUND, "a11y-check: SettingsGuardService"),
            (f"other.app/.Svc:{guard}:com.gathin.tvhome/.HomeKeyService", 1, BOUND, "a11y-check: HomeKeyService"),
            ("other.app/.Svc", 1, UNBOUND, "a11y-check:"),
            ("null", 1, UNBOUND, "a11y-check:"),
            (guard, 0, UNBOUND, "a11y-check:"),  # accessibility off: Android binds nothing, nothing to warn about
        ]
        for enabled, on, dumpsys, expected in cases:
            with self.subTest(enabled=enabled, on=on):
                self.assertEqual(run_on_fake_tv(enabled, on, dumpsys), expected)

    def test_bash_reads_only_the_returned_line(self):
        lines = SH.read_text(encoding="utf-8").splitlines()
        start = lines.index('UNBOUND=""; CHECKED=0')
        end = next(i for i in range(start, len(lines)) if lines[i] == "fi")
        reader = "\n".join(lines[start:end + 1])
        cases = [
            ("a11y-check:\r\n", None),
            ('$ s=x; echo "a11y-check:$miss"\na11y-check: SettingsGuardService\r\n', "SettingsGuardService"),
            ('$ s=x; echo "a11y-check:$miss"\nerror: closed\n', "could not check"),
            ("", "could not check"),
        ]
        for out, warning in cases:
            with self.subTest(out=out):
                result = subprocess.run(["bash", "-c", f'OUT="$1"\n{reader}', "check", out],
                                        capture_output=True, text=True, timeout=30)
                if warning is None:
                    self.assertEqual(result.stderr, "")
                else:
                    self.assertIn(warning, result.stderr)


if __name__ == "__main__":
    unittest.main()
