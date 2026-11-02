# GUPet для Linux — версия 2.0.1

Это независимая копия Linux-версии GUPet. Папка `linux/` с версией 1 не изменяется.

## Что нового в 2.0.1

- Кнопка **«Открыть магазин»** оформлена так же, как **«Подробнее о Great User
  Team»**: оранжевый градиент, скруглённые углы, стрелка → и тень с подсветкой
  при наведении.
- Значок **GitHub** в карточке скина стал полноценной квадратной кнопкой
  40 × 40 px с `border-radius: 12px`, фоном, рамкой и оранжевой подсветкой при
  наведении. Она открывает страницу релиза в браузере.
- Кнопка **«Открыть магазин»** в разделе скинов открывает отдельное
  полноэкранное окно с каталогом GitHub Releases репозитория
  [`GreatUserTeam/petckins`](https://github.com/GreatUserTeam/petckins).
- Карточка скина показывает увеличенное превью `idle.png`, название, автора,
  версию и описание. Названия и описания вида «English / Русский»
  показываются на выбранном языке интерфейса.
- Кнопки **«Скачать»** и **«Удалить»** (для уже установленного скина), а также
  значок GitHub со ссылкой на релиз:
  - скачивает `.gupet` по `download_url`;
  - устанавливает скин в `~/.config/gupet/skins/`;
  - сразу обновляет галерею локальных скинов;
  - переключает питомца на новый скин;
  - сохраняет исходный архив как `~/.config/gupet/skins/<id>.gupet`.
- Если GitHub недоступен или исчерпан лимит запросов, показывается
  «Не удалось загрузить магазин».
- HTTP-запросы выполняются в Rust через `ureq`, поэтому код одинаково
  работает на Linux и Windows и не зависит от CORS.

## Сборка

```bash
cd linux_v2/src-tauri
cargo check
cargo tauri build
```

В `tauri.conf.json` включены три формата: `deb`, `rpm` и `appimage`.
Готовые пакеты:

```text
linux_v2/src-tauri/target/release/bundle/deb/GUPet_2.0.1_amd64.deb
linux_v2/src-tauri/target/release/bundle/rpm/GUPet-2.0.1-1.x86_64.rpm
linux_v2/src-tauri/target/release/bundle/appimage/GUPet_2.0.1_amd64.AppImage
```

Копии для передачи лежат прямо в `linux_v2/`.

## Установка

Debian/Ubuntu:

```bash
sudo dpkg -i linux_v2/GUPet_2.0.1_amd64.deb
```

Fedora/RHEL/openSUSE:

```bash
sudo rpm -i linux_v2/GUPet-2.0.1-1.x86_64.rpm
```

AppImage не требует установки:

```bash
chmod +x linux_v2/GUPet_2.0.1_amd64.AppImage
./linux_v2/GUPet_2.0.1_amd64.AppImage
```

Если версия 1 уже установлена, пакет обновится до 2.0.1.
Для отката используйте `.deb` версии 1 из `linux/src-tauri/target/release/bundle/deb/`.

## Где что лежит

- `src-tauri/src/market.rs` — GitHub API, разбор релизов и скачивание `.gupet`.
- `src-tauri/src/main.rs` — команды `fetch_remote_skins` и `download_remote_skin`.
- `src-tauri/src/skins.rs` — импорт скачанного архива в папку скинов.
- `src-tauri/dist/index.html` — полноэкранное окно магазина, сетка карточек
  с превью и кнопками «Скачать»/«Удалить».
- `src-tauri/dist/bridge.js` — вызовы команд магазина и удаления скина из фронтенда.
