# GUPet для Android — версия 2.0.0

Это независимая Android-версия GUPet с магазином скинов. Исходная папка
`app/` не изменяется.

## Что нового в 2.0.0

- Кнопка **«Открыть магазин»** оформлена так же, как **«Подробнее о Great User
  Team»**: оранжевый градиент, скруглённые углы, стрелка → и тень с подсветкой
  при наведении.
- Значок **GitHub** в карточке скина — полноценная квадратная кнопка
  40 × 40 px с `border-radius: 12px`, фоном, рамкой и оранжевой подсветкой при
  наведении. Она открывает страницу релиза в браузере.
- Кнопка **«Открыть магазин»** в разделе «Скины» открывает каталог
  GitHub Releases репозитория
  [`GreatUserTeam/petckins`](https://github.com/GreatUserTeam/petckins).
- Карточка скина показывает превью `idle.png` из `.gupet`, название, автора,
  версию и описание. Пары «English / Русский» показываются на выбранном языке
  интерфейса.
- Кнопка **«Скачать»** загружает `.gupet`, распаковывает его в папку скинов
  приложения и сразу добавляет скин в галерею. Для установленного скина
  показывается кнопка **«Удалить»**.
- Кнопка GitHub в карточке открывает страницу релиза в браузере.
- Сетевые запросы и распаковка выполняются в фоновых потоках Android;
  WebView только отображает готовый каталог.

## Сборка

Требуются JDK 17, Android SDK Platform 35 и Android Build Tools 35.0.0.

```bash
cd android_v2
./gradlew assembleRelease
```

Release-APK без подписи появится в
`app/build/outputs/apk/release/app-release-unsigned.apk`. Для ручной установки
подпишите его debug-ключом:

```bash
$ANDROID_HOME/build-tools/36.0.0/zipalign -f 4 \
  app/build/outputs/apk/release/app-release-unsigned.apk \
  /tmp/gupet-aligned.apk
$ANDROID_HOME/build-tools/36.0.0/apksigner sign \
  --ks ~/.android/debug.keystore --ks-pass pass:android \
  --out GUPet_2.0.0.apk /tmp/gupet-aligned.apk
```

Готовый установочный APK лежит в `android_v2/GUPet_2.0.0.apk`.

## Где что лежит

- `app/src/main/java/net/guteam/gupet/SkinMarket.kt` — GitHub API, загрузка
  `.gupet`, извлечение превью и id скина.
- `app/src/main/java/net/guteam/gupet/MainActivity.kt` — JS-мост
  `fetchRemoteSkins`, `downloadRemoteSkin`, `deleteFullSkin`, `openUrl`.
- `app/src/main/assets/gupet/index.html` — окно магазина, карточки, превью,
  кнопки «Скачать»/«Удалить» и ссылка на релиз.
