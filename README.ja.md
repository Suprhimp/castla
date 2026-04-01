<p align="center">
  <img src="docs/images/app-icon.png" width="120" alt="Castla App Icon">
  <h1 align="center">Castla</h1>
  <p align="center">
    <strong>Tesla向け究極のAndroid Auto代替アプリ。Waze、Googleマップをテスラのブラウザで直接表示。</strong>
  </p>
  <p align="center">
    <a href="https://github.com/Suprhimp/castla/releases/latest"><img src="https://img.shields.io/github/v/release/Suprhimp/castla?style=flat-square" alt="Latest Release"></a>
    <a href="LICENSE"><img src="https://img.shields.io/badge/license-GPL--3.0-blue?style=flat-square" alt="License"></a>
    <a href="https://ko-fi.com/suprhimp"><img src="https://img.shields.io/badge/Ko--fi-Support-ff5e5b?style=flat-square&logo=ko-fi&logoColor=white" alt="Ko-fi"></a>
  </p>
  <p align="center">
    <a href="README.md">English</a> · <a href="README.ko.md">한국어</a> · <a href="README.zh-CN.md">中文</a> · <a href="README.de.md">Deutsch</a> · <a href="README.es.md">Español</a> · <a href="README.fr.md">Français</a>
  </p>
</p>

<p align="center">
  <img src="docs/images/hero.jpg" width="700" alt="Castla - AndroidをTeslaにミラーリング">
</p>

---

## Castlaとは？

テスラに**Android Auto**がなくて困っていませんか？**Waze**やGoogleマップを大画面で使いたくありませんか？

Castlaは、ローカルWiFiネットワーク経由でAndroidスマートフォンの画面をテスラの内蔵ブラウザに直接ストリーミングする無料のオープンソースソリューションです。インターネット接続、高価なドングル、クラウドサーバー、サブスクリプション不要 — すべてが高速、安全、完全にスマートフォンと車両の間だけで完結します。

**主な特徴：**

- **Android Auto体験** — お気に入りのナビや音楽アプリをテスラ画面で使用
- **リアルタイムミラーリング** — H.264ハードウェアエンコード + WebSocketストリーミングで超低遅延
- **フルタッチコントロール** — テスラ画面から直接タップ、スワイプ、操作（Shizuku使用）
- **オーディオストリーミング** — デバイスオーディオをテスラスピーカーに直接送信（Android 10+）
- **100%ローカル＆プライベート** — すべてのデータはWiFi/ホットスポット内のみ
- **完全無料** — 広告なし、課金なし。GPL-3.0オープンソース

## 機能

| 機能 | 詳細 |
|------|------|
| **大画面ナビゲーション** | **Waze**、Googleマップなど最大1080p @ 60fpsでスムーズに実行 |
| **タッチ入力** | Shizukuによる完全なタッチインジェクション。車の画面からスマホを操作 |
| **分割表示** | デュアルパネルマルチタスキング。左にWaze、右にYouTube！ |
| **仮想ディスプレイ** | スマホ画面をオンにしなくてもテスラで独立してアプリ実行 |
| **オーディオ** | システムオーディオキャプチャ（Android 10+、実験的） |
| **テスラ自動検出** | BLE + ホットスポットクライアント検出で自動接続 |
| **自動ホットスポット** | ミラーリング開始/停止時にホットスポットを自動オン/オフ |
| **OTTブラウザ** | DRMコンテンツ用内蔵ブラウザ（YouTube、Netflixなど） |
| **発熱保護** | デバイス過熱時にバッテリー保護のため自動画質調整 |
| **9言語対応** | EN, KO, DE, ES, FR, JA, NL, NO, ZH |

## 要件

- Android 8.0+（API 26）
- タッチコントロールと高度な機能には[Shizuku](https://shizuku.rikka.app/)が必要
- ウェブブラウザ搭載のテスラ車両
- スマートフォンとテスラが同じWiFiネットワーク（またはスマートフォンのホットスポット）

## インストール

1. [Releases](https://github.com/Suprhimp/castla/releases/latest)にアクセス
2. 最新の`.apk`ファイルをダウンロード
3. Androidデバイスにインストール

## クイックスタート

1. **Shizukuをインストール** — Castlaを開き「Shizukuをインストール」をタップ
2. **Shizukuを起動** — 開発者オプション → ワイヤレスデバッグ → Shizukuを開く → 「ワイヤレスデバッグで開始」
3. **権限を付与** — Shizukuの使用を許可
4. **接続** — スマートフォンとテスラを同じWiFiに接続
5. **ミラーリング開始** — Castlaで「ミラーリング開始」をタップ
6. **テスラで開く** — 表示されたURLをテスラブラウザに入力

## 貢献

貢献を歓迎します！詳細は[貢献ガイド](CONTRIBUTING.md)をご覧ください。

## プライバシー

Castlaは**一切のデータを収集しません**。詳細は[プライバシーポリシー](PRIVACY.md)をご確認ください。

## インディー開発者をサポート

<a href="https://ko-fi.com/suprhimp"><img src="https://ko-fi.com/img/githubbutton_sm.svg" alt="Ko-fiでサポート"></a>

## ライセンス

[GNU General Public License v3.0](LICENSE)
