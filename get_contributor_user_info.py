import os
import requests
from pathlib import Path
from time import sleep
from PIL import Image
from io import BytesIO
from typing import Optional
import urllib.parse

# Tuple of GitHub login ID to string resource ID
# Use following command to get a rough idea who should be on this list:
# git shortlog -n -s -- ':!app/src/main/res/values*/strings.xml' ':!fastlane/metadata/android/' ':!readme*.md'
GITHUB_USERS = [
    ("alivemaster", "contributors_alivemaster"),
    ("nift4", "contributors_nift4"),
    ("123Duo3", "contributors_123duo3"),
    ("AkaneTan", "contributors_akanetan"),
    ("mikooomich", "contributors_developer"),
    ("imjyotiraditya", "contributors_developer"),
    ("WSTxda", "contributors_wstxda"),
    ("pxeemo", "contributors_pxeemo"),
    ("saladnoober", "contributors_saladnoober"),
    ("Lambada10", "contributors_lambada10"),
    ("lightsummer233", "contributors_developer"),
    ("nicholaswww", "contributors_code_contributions"),
    ("Yuyuko1024", "contributors_yuyuko1024"),
    ("ghhccghk", "contributors_ghhccghk"),
    ("banksio", "contributors_banksio"),
    ("manum45", "contributors_code_contributions"),
    ("lucaxvi", "contributors_code_contributions"),
    ("tungnk123", "contributors_code_contributions"),
    (None, "contributors_code_contributions", "kleidis"),
    ("topazrn", "contributors_code_contributions"),
    ("strongville", "contributors_code_contributions"),
    ("SurFace81", "contributors_code_contributions"),
    ("bggRGjQaUbCoE", "contributors_code_contributions"),
    ("VishnuSanal", "contributors_code_contributions"),
    ("HotarunIchijou", "contributors_code_contributions"),
    ("someone5678", "contributors_code_contributions"),
    ("PalanixYT", "contributors_code_contributions"),
    ("N3Shemmy3", "contributors_code_contributions"),
    ("SharnavM", "contributors_code_contributions"),
]

# TODO: delete old webp files so that webp file is removed if someone is removed from credits

DRAWABLE_DIR = "app/src/main/res/drawable"
OUTPUT_KT = "app/src/main/java/org/akanework/gramophone/logic/utils/data/Contributors.kt"
API_BASE = "https://api.github.com/users/"
WEBLATE_BASE = "https://hosted.weblate.org"
# App string languages still shipped. English is the source language.
TRANSLATION_LANGUAGES = ("zh_Hans", "zh_Hant", "ja")
# Weblate Change.ACTIONS_CONTENT: the edits its credits report counts.
CONTENT_ACTIONS = {2, 5, 6, 7, 8, 9, 27, 30, 31, 32, 36, 37, 66, 76, 77}

HEADERS = {
    "Accept": "application/vnd.github+json",
}

try:
    with open("fastlane/creds.txt", "r", encoding="utf-8") as f:
        HEADERS["Authorization"] = "Bearer " + f.read().strip()
except Exception as e:
    print("Not using auth, may be subject to rate limits")

def sanitize_login(login: str) -> str:
    return ''.join(c if c.isalnum() else '_' for c in login.lower())

def fetch_user_data(login: str) -> Optional[dict]:
    url = f"{API_BASE}{login}"
    try:
        response = requests.get(url, headers=HEADERS, timeout=10)
        response.raise_for_status()
        return response.json()
    except Exception as e:
        print(f"❌ get users error {login}: {e}")
        return None

def fetch_translation_credits() -> Optional[list]:
    """Usernames with content edits on the kept string languages.

    The public changes API does not include display names. User profiles
    are not readable without a token, so the about screen shows the
    Weblate username from the change author.
    """
    try:
        names = set()
        for code in TRANSLATION_LANGUAGES:
            print(f"📦 Fetching {code} translators")
            url = (
                f"{WEBLATE_BASE}/api/translations/gramophone/strings-xml/"
                f"{code}/changes/?page_size=1000"
            )
            while url:
                response = requests.get(
                    url,
                    headers={"Accept": "application/json"},
                    timeout=30,
                )
                response.raise_for_status()
                page = response.json()
                for item in page["results"]:
                    if item.get("action") not in CONTENT_ACTIONS:
                        continue
                    author = item.get("author") or ""
                    slug = urllib.parse.unquote(author.rstrip("/").rsplit("/", 1)[-1])
                    if slug:
                        names.add(slug)
                url = page.get("next")
        return sorted(names)
    except Exception as e:
        print(f"❌ get credits error: {e}")
        return None

def download_and_save_avatar(url: str, filename: str):
    response = requests.get(url, timeout=10)
    response.raise_for_status()
    img = Image.open(BytesIO(response.content)).convert("RGBA")
    img.thumbnail((128, 128))
    os.makedirs(DRAWABLE_DIR, exist_ok=True)
    filepath = os.path.join(DRAWABLE_DIR, f"{filename}.webp")
    img.save(filepath, format="WebP", quality=50, method=6)
    print(f"✅ download ok: {filepath}")

def main():
    result = """// ===== DO NOT EDIT, CHANGES WILL BE OVERWRITTEN =====
// automatically generated by get_contributor_user_info.py

package org.akanework.gramophone.logic.utils.data

import android.net.Uri
import org.akanework.gramophone.R

object Contributors {
    private fun decode(text: String?) = text?.let { Uri.decode(it) }
    val LIST = listOf("""

    for user in GITHUB_USERS:
        login = user[0]
        lname = login
        if not login:
            lname = user[2]
        print(f"📦 Processing users: {lname}")
        filename = f"contributor_{sanitize_login(lname)}"
        if login:
            user_data = fetch_user_data(login)
            if not user_data:
                return

            avatar_url = user_data.get("avatar_url", "")
            name = ("\"" + urllib.parse.quote(user_data["name"]) + "\"") if ("name" in user_data and user_data["name"]) else "null"
        else:
            avatar_url = "https://avatars.githubusercontent.com/u/10137?s=460&v=4"
            name = f"\"{lname}\""
        download_and_save_avatar(avatar_url, filename)
        result += f"\n        GitHubUser(login = \"{lname}\", link = {"true" if login else "false"}, name = decode({name}), avatar = R.drawable.{filename}, contributed = R.string.{user[1]}),"

    result += "\n    )\n"
    print("📦 Fetching translators from Weblate")
    names = fetch_translation_credits()
    if not names:
        return
    result += "    val TRANSLATORS = listOf("
    for name in names:
        result += "\n        decode(\"" + urllib.parse.quote(name) + "\"),"
    result += "\n    )\n}\n"
    with open(OUTPUT_KT, "w", encoding="utf-8") as f:
        f.write(result)

    print(f"\n✅ All user processing is complete and results have been saved to {OUTPUT_KT}")

if __name__ == "__main__":
    main()
