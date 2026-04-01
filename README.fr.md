<p align="center">
  <img src="docs/images/app-icon.png" width="120" alt="Castla App Icon">
  <h1 align="center">Castla</h1>
  <p align="center">
    <strong>L'alternative ultime à Android Auto pour Tesla. Waze, Google Maps et votre écran directement dans le navigateur Tesla.</strong>
  </p>
  <p align="center">
    <a href="https://github.com/Suprhimp/castla/releases/latest"><img src="https://img.shields.io/github/v/release/Suprhimp/castla?style=flat-square" alt="Latest Release"></a>
    <a href="LICENSE"><img src="https://img.shields.io/badge/license-GPL--3.0-blue?style=flat-square" alt="License"></a>
    <a href="https://ko-fi.com/suprhimp"><img src="https://img.shields.io/badge/Ko--fi-Support-ff5e5b?style=flat-square&logo=ko-fi&logoColor=white" alt="Ko-fi"></a>
  </p>
  <p align="center">
    <a href="README.md">English</a> · <a href="README.ko.md">한국어</a> · <a href="README.ja.md">日本語</a> · <a href="README.zh-CN.md">中文</a> · <a href="README.de.md">Deutsch</a> · <a href="README.es.md">Español</a>
  </p>
</p>

<p align="center">
  <img src="docs/images/hero.jpg" width="700" alt="Castla - Miroir Android sur écran Tesla">
</p>

---

## Qu'est-ce que Castla ?

**Android Auto** vous manque dans votre Tesla ? Vous voulez utiliser **Waze**, Google Maps sur le grand écran ?

Castla est une solution gratuite et open source qui diffuse l'écran de votre téléphone Android directement sur le navigateur intégré de Tesla via votre réseau WiFi local. Pas de connexion internet, pas de dongles coûteux, pas de serveurs cloud, pas d'abonnement — tout reste rapide, sécurisé et entièrement entre votre téléphone et votre voiture.

**Points forts :**

- **L'expérience Android Auto** — Utilisez vos apps de navigation et musique préférées sur l'écran Tesla
- **Miroir en temps réel** — Encodage H.264 matériel + streaming WebSocket pour une latence ultra-faible
- **Contrôle tactile complet** — Touchez, glissez et interagissez directement depuis l'écran Tesla (via Shizuku)
- **Streaming audio** — Audio de l'appareil directement sur les haut-parleurs Tesla (Android 10+)
- **100% local et privé** — Toutes les données restent sur votre WiFi/hotspot
- **Entièrement gratuit** — Sans pub, sans paywall. Open source sous GPL-3.0

## Fonctionnalités

| Fonctionnalité | Détails |
|----------------|---------|
| **Navigation grand écran** | **Waze**, Google Maps et toute app jusqu'à 1080p @ 60fps |
| **Saisie tactile** | Injection tactile complète via Shizuku. Contrôlez votre téléphone depuis l'écran de la voiture |
| **Vue partagée** | Multitâche en double panneau. Waze à gauche, YouTube à droite ! |
| **Écran virtuel** | Exécutez des apps indépendamment sur Tesla sans garder l'écran du téléphone allumé |
| **Audio** | Capture audio système (Android 10+, expérimental) |
| **Auto-détection Tesla** | BLE + détection des clients hotspot pour une connexion automatique |
| **Auto Hotspot** | Activer/désactiver le hotspot automatiquement avec le miroir |
| **Navigateur OTT** | Navigateur intégré pour le contenu DRM (YouTube, Netflix, etc.) |
| **Protection thermique** | Réduction automatique de la qualité en cas de surchauffe |
| **9 langues** | EN, KO, DE, ES, FR, JA, NL, NO, ZH |

## Prérequis

- Android 8.0+ (API 26)
- [Shizuku](https://shizuku.rikka.app/) pour le contrôle tactile et les fonctions avancées
- Véhicule Tesla avec navigateur web
- Téléphone et Tesla sur le même réseau WiFi (ou hotspot du téléphone)

## Installation

1. Allez sur [Releases](https://github.com/Suprhimp/castla/releases/latest)
2. Téléchargez le dernier fichier `.apk`
3. Installez sur votre appareil Android

## Démarrage rapide

1. **Installer Shizuku** — Ouvrez Castla, appuyez sur « Installer Shizuku »
2. **Démarrer Shizuku** — Options développeur → Débogage sans fil → Ouvrir Shizuku → « Démarrer via Débogage sans fil »
3. **Accorder la permission** — Autorisez Castla à utiliser Shizuku
4. **Connecter** — Assurez-vous que le téléphone et Tesla sont sur le même WiFi
5. **Démarrer le miroir** — Appuyez sur « Démarrer le Miroir » dans Castla
6. **Ouvrir dans Tesla** — Entrez l'URL affichée dans le navigateur Tesla

## Contribuer

Les contributions sont les bienvenues ! Consultez le [Guide de Contribution](CONTRIBUTING.md).

## Confidentialité

Castla **ne collecte aucune donnée**. Voir la [Politique de Confidentialité](PRIVACY.md).

## Soutenir le développeur indie

<a href="https://ko-fi.com/suprhimp"><img src="https://ko-fi.com/img/githubbutton_sm.svg" alt="Soutenir sur Ko-fi"></a>

## Licence

[GNU General Public License v3.0](LICENSE)
