<div align="center">

<br/>

<img src="Logo.png" alt="PurpleLaya app icon" width="180" />

# PurpleLaya

### Client Desktop (Windows / Linux / macOS) & Android pour [PurpleMusic](https://github.com/Axolat000/PurpleMusic)

Fork open-source ([GNU GPLv3](LICENSE)) de [Laya](https://github.com/impin2rex/Laya) remplaçant YouTube Music par votre propre serveur auto-hébergé **[PurpleMusic](https://github.com/Axolat000/PurpleMusic)** tout en conservant l'interface Material 3 frosted-glass et tous les extras audio avancés de Laya.

<br/>

[![PurpleMusic Server](https://img.shields.io/badge/Server-PurpleMusic-9C27B0?style=for-the-badge)](https://github.com/Axolat000/PurpleMusic)
[![License](https://img.shields.io/badge/License-GPLv3-blue?style=for-the-badge)](LICENSE)

</div>

---

## Configuration rapide

1. Lancez **PurpleLaya** sur **Windows** ou **Android**.
2. Entrez simplement **l'URL de votre instance PurpleMusic** (ex: `https://music.monsite.com` ou `http://localhost:51837`).
3. Connectez-vous avec votre compte PurpleMusic, créez un compte directement depuis l'application, ou cliquez sur **« Utiliser l'URL sans compte »** pour écouter le catalogue public du serveur.

---

## Fonctionnalités

### Propulsé par votre serveur PurpleMusic
- **Streaming direct & Lossless** — Lecture directe depuis `api.php?action=stream` (`MP3`, `FLAC`, `WAV`, `OGG`) avec détection automatique des pistes Lossless (`FLAC` / `WAV`).
- **Accueil dynamique** — *Recommandé pour toi* (`action=recommendations`), *Mix du jour* par genre (`action=daily_mixes`), *Ajouts récents*, *Les plus écoutés* (`action=server_top`), *Playlists*, *Albums* et *Artistes*.
- **Recherche unifiée** — Recherche serveur (`action=search`) et filtrage par titres, albums, artistes et playlists avec suggestions instantanées.
- **Bibliothèque & Playlists** — Synchronisation des titres likés (`my_likes`, `toggle_like`), création, édition, réordonnancement et suppression de playlists (`playlist_save`, `playlist_mod`, `playlist_reorder`, `delete_playlist`).
- **Pages Artistes & Albums** — Discographie complète, biographie Wikipédia mise en cache par le serveur (`artist_bio`), et suivi d'artistes (`follow_artist`, `my_follows`).
- **Paroles synchronisées hybrides** — Récupère en priorité les paroles synchronisées de votre serveur PurpleMusic (`get_lyrics`) avec repli automatique sur les sources de Laya (*LRCLIB*, *BetterLyrics*, *Musixmatch*, *KuGou*, *Genius*, etc.).
- **Analytique d'écoute** — Remonte automatiquement vos écoutes réelles (`report_listen` après 10s) pour alimenter le compteur d'écoutes, vos statistiques et le moteur de recommandations de votre serveur.

### Extras de Laya conservés
- **Interface Frosted-Glass Material 3** — Palette dynamique extraite des pochettes, mini-lecteur flottant et mode Picture-in-Picture sur Desktop.
- **Égaliseur, Reverb & Karaoke IA** — Égaliseur paramétrique/dynamique, réverbération (*Room*, *Hall*, *Plate*, *Cathedral*) et séparation vocale IA locale (*UVR MDX-Net*).
- **Automix & Crossfade** — Transitions fluides et mixage automatique entre les morceaux.
- **Téléchargements hors-ligne** — Sauvegarde des pistes (`FLAC` / `MP3` / `OGG` / `WAV`) dans `Music/PurpleLaya` pour une écoute sans connexion.
- **Intégrations** — **Discord Rich Presence**, scrobbling **Last.fm** & **ListenBrainz**, **Listen Together**, et lecture des fichiers locaux / **WebDAV** / **SMB**.

---

## Compiler le projet

### Windows (Desktop)
```powershell
# Lancer les tests unitaires
.\gradlew.bat :shared:jvmTest :desktopUi:jvmTest :desktopApp:test

# Générer l'application Windows exécutable (desktopApp/build/compose/binaries/main/app/)
.\gradlew.bat :desktopApp:createDistributable
```

### Android
```bash
# Générer l'APK de développement
./gradlew :app:assembleDevDebug

# Générer l'APK de production
./gradlew :app:assembleProdRelease
```

---

## Crédits & Licence

- Projet original : **[Laya](https://github.com/impin2rex/Laya)** par *impin2rex* & les contributeurs BitChord / Laya.
- Serveur backend : **[PurpleMusic](https://github.com/Axolat000/PurpleMusic)** par *Axolat000*.
- Distribué sous licence **[GNU General Public License v3.0 (GPLv3)](LICENSE)**.
