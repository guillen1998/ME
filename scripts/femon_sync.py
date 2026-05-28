"""
Femon Play - Sincronizador de lista
Descarga la lista de canales desde Firebase Remote Config + servidor Femon
y la descifra a un JSON legible.

Uso:
    python femon_sync.py

Genera el archivo: lista_femon.json (descifrado, listo para usar)
"""
import json
import base64
import urllib.request
import sys
import os

try:
    from Crypto.Cipher import AES
    from Crypto.Util.Padding import unpad
except ImportError:
    print("[!] Falta el paquete 'pycryptodome'. Instalando...")
    os.system(f'"{sys.executable}" -m pip install pycryptodome')
    from Crypto.Cipher import AES
    from Crypto.Util.Padding import unpad

# Credenciales Firebase extraidas de la APK
GOOGLE_API_KEY = "AIzaSyADcEYKamrewxL8CDA8NmAuRZjp8eZ2XzY"
APP_ID = "1:539591373021:android:88e80ca11e7a6d934aeb34"
PROJECT_NUMBER = "539591373021"
PROJECT_ID = "femon-play"
PACKAGE_NAME = "com.example.myapplication"

# Fallback (si Firebase falla)
FALLBACK_URL = "https://app.femon.net/pirata/piratachanel.json"
FALLBACK_KEY = "e72of82ke0gu2o2k"


def http_post_json(url, body, headers):
    data = json.dumps(body).encode("utf-8")
    req = urllib.request.Request(url, data=data, headers=headers, method="POST")
    with urllib.request.urlopen(req, timeout=30) as resp:
        return json.loads(resp.read().decode("utf-8"))


def http_get(url):
    req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
    with urllib.request.urlopen(req, timeout=60) as resp:
        return resp.read().decode("utf-8")


def fetch_firebase_config():
    """Consulta Firebase Remote Config y devuelve (json_url, aes_key)."""
    print("[*] Registrando instalacion en Firebase...")
    install = http_post_json(
        f"https://firebaseinstallations.googleapis.com/v1/projects/{PROJECT_ID}/installations",
        {
            "fid": "d000000000000000000000aaaaaaaaaaa",
            "appId": APP_ID,
            "authVersion": "FIS_v2",
            "sdkVersion": "a:17.2.0",
        },
        {
            "Content-Type": "application/json",
            "X-Android-Package": PACKAGE_NAME,
            "x-goog-api-key": GOOGLE_API_KEY,
            "X-firebase-client": "android",
        },
    )
    fid = install["fid"]
    token = install["authToken"]["token"]

    print("[*] Consultando Remote Config...")
    config = http_post_json(
        f"https://firebaseremoteconfig.googleapis.com/v1/projects/{PROJECT_NUMBER}/namespaces/firebase:fetch",
        {
            "appInstanceId": fid,
            "appInstanceIdToken": token,
            "appId": APP_ID,
            "sdkVersion": "21.6.0",
            "packageName": PACKAGE_NAME,
            "languageCode": "es-ES",
            "platformVersion": "33",
            "timeZone": "America/Asuncion",
            "appVersion": "7.0",
            "appBuild": "7",
        },
        {
            "Content-Type": "application/json",
            "x-goog-api-key": GOOGLE_API_KEY,
        },
    )
    entries = config.get("entries", {})
    return entries.get("json3_url"), entries.get("claveapp")


def decrypt_once(value, key_bytes):
    if not value:
        return value
    try:
        cipher = AES.new(key_bytes, AES.MODE_ECB)
        plain = unpad(cipher.decrypt(base64.b64decode(value)), 16)
        return plain.decode("utf-8")
    except Exception:
        return value


def decrypt_double(value, key_bytes):
    return decrypt_once(decrypt_once(value, key_bytes), key_bytes)


def decrypt_map(m, key_bytes):
    if not m:
        return
    for k in list(m.keys()):
        m[k] = decrypt_double(m[k], key_bytes)


def main():
    try:
        url, key = fetch_firebase_config()
        print(f"[+] URL obtenida: {url}")
        print(f"[+] Clave obtenida: {key}")
    except Exception as e:
        print(f"[!] Firebase fallo ({e}), usando fallback")
        url, key = FALLBACK_URL, FALLBACK_KEY

    if not url or not key:
        print("[!] URL o clave vacia, usando fallback")
        url, key = url or FALLBACK_URL, key or FALLBACK_KEY

    print(f"[*] Descargando lista desde {url}...")
    raw = http_get(url)
    data = json.loads(raw)

    key_bytes = key.encode("utf-8")
    print(f"[*] Descifrando con clave AES de {len(key_bytes)} bytes...")

    total_channels = 0
    for cat in data:
        for ch in cat.get("samples", []) or []:
            total_channels += 1
            if ch.get("url"):
                ch["url"] = decrypt_double(ch["url"], key_bytes)
            if ch.get("drm_license_uri"):
                ch["drm_license_uri"] = decrypt_double(ch["drm_license_uri"], key_bytes)
            for hkey in ("headers", "headersUrl", "headersM3u8", "headers2"):
                if hkey in ch and ch[hkey]:
                    decrypt_map(ch[hkey], key_bytes)

    out_path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "lista_femon.json")
    with open(out_path, "w", encoding="utf-8") as f:
        json.dump(data, f, indent=2, ensure_ascii=False)

    print()
    print(f"[OK] {len(data)} categorias, {total_channels} canales")
    print(f"[OK] Archivo guardado: {out_path}")


if __name__ == "__main__":
    try:
        main()
    except Exception as e:
        print(f"[ERROR] {e}")
        sys.exit(1)
