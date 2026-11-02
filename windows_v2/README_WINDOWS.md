# GUPet для Windows — версия 2.0.1

Это независимая Windows-сборка GUPet с магазином скинов. Дизайн окна
настроек и магазина полностью совпадает с Linux-версией из `linux_v2/`.

## Что нового в 2.0.1

- Кнопка **«Открыть магазин»** оформлена так же, как **«Подробнее о Great User
  Team»**: оранжевый градиент, скруглённые углы, стрелка → и тень с подсветкой
  при наведении.
- Значок **GitHub** в карточке скина — полноценная квадратная кнопка
  40 × 40 px с `border-radius: 12px`, фоном, рамкой и оранжевой подсветкой при
  наведении. Открывает страницу релиза в браузере.
- Магазин загружает список скинов из GitHub Releases репозитория
  [`GreatUserTeam/petckins`](https://github.com/GreatUserTeam/petckins).
- Карточка показывает превью, название, автора, версию и описание. Пары
  «English / Русский» показываются на выбранном языке интерфейса.
- Кнопки **«Скачать»** / **«Удалить»** устанавливают и удаляют `.gupet`,
  обновляют галерею и переключают питомца на новый скин.
- Готовый портативный `.exe` не требует установки. Рядом с ним должен лежать
  `WebView2Loader.dll` (он уже входит в комплект и в ZIP-архив).

## Готовые файлы

```text
windows_v2/GUPet_2.0.1_windows_x64.exe   — портативная сборка
windows_v2/WebView2Loader.dll            — обязательная библиотека рядом с .exe
windows_v2/GUPet_2.0.1_windows_x64.zip   — архив с .exe и .dll
```

Запуск: распакуйте ZIP в любую папку и откройте `GUPet.exe`. На Windows 10/11
нужен установленный WebView2 Runtime (обычно уже есть в системе).

## Сборка на Windows

1. Установите Rust: <https://rustup.rs/>
2. Установите Visual Studio Build Tools с компонентом **Desktop development
   with C++**.
3. Установите WebView2 Runtime:
   <https://developer.microsoft.com/microsoft-edge/webview2/>
4. Откройте терминал в `windows_v2/src-tauri` и выполните:

```powershell
cargo tauri build
```

Готовый `.exe` и установщики появятся в `target/release/bundle/`.

## Кросс-сборка из Linux

Сборка проверена с target `x86_64-pc-windows-gnu` и MinGW-w64:

```bash
rustup target add x86_64-pc-windows-gnu
cd windows_v2/src-tauri
cargo build --release --target x86_64-pc-windows-gnu
```

Результат:

```text
windows_v2/src-tauri/target/x86_64-pc-windows-gnu/release/gupet.exe
windows_v2/src-tauri/target/x86_64-pc-windows-gnu/release/WebView2Loader.dll
```

## Где что лежит

- `src-tauri/src/market.rs` — GitHub API, разбор релизов и скачивание `.gupet`.
- `src-tauri/src/main.rs` — команды `fetch_remote_skins` и
  `download_remote_skin`, окно настроек и оверлей питомца.
- `src-tauri/src/skins.rs` — импорт скачанного архива в папку скинов.
- `src-tauri/dist/index.html` — окно настроек и магазина с карточками,
  кнопками «Скачать»/«Удалить» и кнопкой GitHub.
- `src-tauri/dist/bridge.js` — вызовы команд магазина из интерфейса.
