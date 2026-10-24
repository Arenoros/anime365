# anime365

Неофициальный Android-клиент [anime365](https://smotret-anime.co): каталог
аниме, просмотр эпизодов, локальная Библиотека, скачивание и публикация
переводов. Для просмотра видео нужен аккаунт с подпиской на сайте.

Статус: веха M6 — первый релиз ([docs/roadmap.md](docs/roadmap.md)).

## Скриншоты

Снято с приложения на реальном устройстве. Нажмите на изображение, чтобы
открыть его в полном разрешении.

| Главная | Каталог | Страница аниме |
|:---:|:---:|:---:|
| [<img src="docs/screenshots/home.png" width="260" alt="Главная: продолжить просмотр и новые серии">](docs/screenshots/home.png) | [<img src="docs/screenshots/catalog.png" width="260" alt="Каталог аниме с постерами и фильтрами">](docs/screenshots/catalog.png) | [<img src="docs/screenshots/series.png" width="260" alt="Страница аниме: описание, рейтинг и жанры">](docs/screenshots/series.png) |

| Плеер | Список | Выбор перевода |
|:---:|:---:|:---:|
| [<img src="docs/screenshots/player-controls.png" width="260" alt="Плеер с видео, управлением и списком серий">](docs/screenshots/player-controls.png) | [<img src="docs/screenshots/library.png" width="260" alt="Локальная библиотека аниме">](docs/screenshots/library.png) | [<img src="docs/screenshots/translations.png" width="260" alt="Выбор команды перевода и разрешения">](docs/screenshots/translations.png) |

[Полная галерея — 28 снимков экранов и меню](docs/screenshots/README.md):
фильтры, серии, вход, настройки, скачивание, мини-плеер и полный экран.

## Установка

APK — в [Releases](https://github.com/Arenoros/anime365/releases). Почти всем
телефонам нужен `arm64-v8a`; старым 32-битным — `armeabi-v7a`. Дальше
приложение само раз в сутки проверяет, нет ли новой версии.

Каждый APK — целый, а не часть split-APK: его ставит системный установщик,
если открыть файл из «Загрузок» или файлового менеджера. SAI и похожие
установщики не нужны. SAI на некоторых телефонах падает с
`DISPLAY_NAME column is null`: например, на Redmi Note 14 с Android 15.

## Сборка

Нужны JDK 17+ (подойдёт JBR из Android Studio) и Android SDK.

```bash
./gradlew assembleDevDebug
```

Проверки, которые гоняет CI:

```bash
./gradlew spotlessCheck detekt lint test buildHealth assembleDevDebug -Proborazzi.test.verify=true
```

`spotlessApply` правит форматирование автоматически.

Скриншот-тесты экранов (Roborazzi) сверяются с эталонами в
`feature/*/src/test/screenshots`. Экран изменился намеренно — перезаписать
эталоны и посмотреть разницу в коммите:

```bash
./gradlew recordRoborazziDebug
```

Флейворы: `dev` (`io.github.arenoros.anime365.dev`, ставится рядом с
релизной) и `prod`.

## Документация

- [GLOSSARY.md](GLOSSARY.md) — термины предметной области.
- [docs/adr](docs/adr) — архитектурные решения и почему они такие.
- [docs/screens.md](docs/screens.md) — экраны приложения.
- [docs/roadmap.md](docs/roadmap.md) — вехи.
- [docs/api-notes.md](docs/api-notes.md) — поведение API сверх спецификации.
- [docs/release.md](docs/release.md) — версия, ключ подписи, Sentry, Baseline Profile.

## Тесты против живого API

Ловят изменения API, которых не видят записанные ответы сервера. CI их не
запускает:

```bash
./gradlew :core:network:testDebugUnitTest -PliveApi
```

Каталогу Токен не нужен; тест Видеопотока без Токена пропускается. Токен —
в `secrets.properties` в корне (файл в `.gitignore`):

```properties
ANIME365_ACCESS_TOKEN=<токен>
```

## Лицензия

[GPL-3.0](LICENSE).
