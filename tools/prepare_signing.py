"""Create private local build credentials. No project signing key is distributed."""
from pathlib import Path
import hashlib
import os
import secrets
import subprocess

root = Path(__file__).resolve().parents[1]
folder = root / '.local-signing'
folder.mkdir(mode=0o700, exist_ok=True)
folder.chmod(0o700)
key = folder / 'debug.keystore'
password = folder / 'password'
pin = folder / 'certificate.sha256'
keytool = str(Path(os.environ['JAVA_HOME']) / 'bin/keytool')

if key.exists() != password.exists():
    raise SystemExit('Incomplete local signing setup. Restore the matching key/password from your private backup.')
if not key.exists():
    if pin.exists():
        raise SystemExit('Signing pin exists but credentials are missing. Restore your private backup.')
    staged_key = folder / 'key.pending'
    staged_password = folder / 'password.pending'
    if staged_key.exists() or staged_password.exists():
        raise SystemExit('An interrupted signing setup exists. Review the private pending files before retrying.')
    with os.fdopen(os.open(staged_password, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), 'w') as stream:
        stream.write(secrets.token_urlsafe(48) + '\n')
    previous_umask = os.umask(0o077)
    try:
        subprocess.run([keytool, '-genkeypair', '-keystore', str(staged_key), '-storetype', 'PKCS12',
                        '-storepass:file', str(staged_password), '-keypass:file', str(staged_password),
                        '-alias', 'androiddebugkey', '-keyalg', 'RSA', '-keysize', '3072', '-validity', '10000',
                        '-dname', 'CN=mz music Local'], check=True)
        os.replace(staged_key, key)
        os.replace(staged_password, password)
    finally:
        os.umask(previous_umask)
key.chmod(0o600)
password.chmod(0o600)
certificate = subprocess.check_output([keytool, '-exportcert', '-keystore', str(key),
                                      '-storepass:file', str(password), '-alias', 'androiddebugkey'])
digest = hashlib.sha256(certificate).hexdigest()
if pin.exists() and pin.read_text().strip() != digest:
    raise SystemExit('Signing identity differs from the local certificate pin. Restore the matching credentials.')
if not pin.exists():
    with os.fdopen(os.open(pin, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), 'w') as stream:
        stream.write(digest + '\n')
