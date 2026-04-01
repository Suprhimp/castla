# Contributing to Castla Mirror

Thank you for your interest in contributing to Castla Mirror! This guide will help you get started.

## Reporting Bugs

Please use [GitHub Issues](../../issues) with the **Bug Report** template. Include:
- Device model and Android version
- Shizuku version (if applicable)
- Tesla model and browser version
- Steps to reproduce
- Logs (if available)

## Suggesting Features

Use the **Feature Request** issue template. Describe the use case and any alternatives you've considered.

## Development Setup

1. Clone the repository
2. Open in Android Studio (Hedgehog or newer)
3. Sync Gradle and build
4. For full testing, install [Shizuku](https://shizuku.rikka.app/) on your device

```bash
git clone https://github.com/user/castla-mirror.git
cd castla-mirror
./gradlew assembleRelease
```

## Pull Request Process

1. Fork the repository
2. Create a feature branch (`git checkout -b feature/my-feature`)
3. Commit your changes with clear messages
4. Push and open a Pull Request against `master`
5. Ensure the build passes

## Code Style

- Follow standard Kotlin conventions
- Use Jetpack Compose for UI components
- Keep functions small and focused
- Write meaningful commit messages

## Adding Translations

Castla currently supports 9 languages. To add a new language:

1. Copy `app/src/main/res/values/strings.xml`
2. Create a new folder `app/src/main/res/values-xx/` (where `xx` is the language code)
3. Translate all strings in the copied file
4. Submit a Pull Request

Existing translations: en, ko, de, es, fr, ja, nl, no, zh-CN

## License

By contributing, you agree that your contributions will be licensed under the GPL-3.0 License.
