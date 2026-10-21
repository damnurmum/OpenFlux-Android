<div align="center">
  <img src="design/logo/icon.svg" width="112" alt="Логотип OpenFlux">
  <h1>OpenFlux Android</h1>
  <p>Зашифрованный туннель через документ-транспорт - для Android, десктопных клиентов и Linux-выходных нод.</p>
  <p>
    <a href="https://github.com/damnurmum/OpenFlux-Android/releases/latest"><img src="https://img.shields.io/github/v/release/damnurmum/OpenFlux-Android?display_name=tag&amp;sort=semver&amp;style=flat-square&amp;color=4F7CFF&amp;labelColor=314D9E" alt="Последний релиз"></a>
    <a href="https://github.com/damnurmum/OpenFlux-Android/actions/workflows/ci.yml"><img src="https://img.shields.io/github/actions/workflow/status/damnurmum/OpenFlux-Android/ci.yml?branch=main&amp;style=flat-square&amp;label=CI&amp;logo=githubactions&amp;logoColor=white&amp;color=4F7CFF&amp;labelColor=314D9E" alt="Статус CI"></a>
    <a href="LICENSE"><img src="https://img.shields.io/github/license/damnurmum/OpenFlux-Android?style=flat-square&amp;color=4F7CFF&amp;labelColor=314D9E" alt="Лицензия GPL-3.0"></a>
    <img src="https://img.shields.io/badge/Android-8.0%2B-4F7CFF?style=flat-square&amp;logo=android&amp;logoColor=white&amp;color=4F7CFF&amp;labelColor=314D9E" alt="Android 8 и новее">
    <a href="https://discord.gg/8a4S3QAh62"><img src="https://img.shields.io/discord/1550202172304785479?style=flat-square&amp;logo=discord&amp;logoColor=white&amp;label=Discord&amp;color=4F7CFF&amp;labelColor=314D9E" alt="Присоединиться к нашему Discord"></a>
    <a href="https://t.me/openflux_chat"><img src="https://img.shields.io/badge/dynamic/xml?url=https%3A%2F%2Ft.me%2Fopenflux_chat&amp;query=%2F%2Fdiv%5B%40class%3D%27tgme_page_extra%27%5D&amp;label=Telegram&amp;logo=telegram&amp;logoColor=white&amp;style=flat-square&amp;color=4F7CFF&amp;labelColor=314D9E" alt="Присоединиться к нашему Telegram-чату"></a>
  </p>
  <p>
    <img src="https://img.shields.io/badge/Go-1.26.4%2B-4F7CFF?style=flat-square&amp;logo=go&amp;logoColor=white&amp;color=4F7CFF&amp;labelColor=314D9E" alt="Go 1.26.4 и новее">
    <img src="https://img.shields.io/badge/Java-17-4F7CFF?style=flat-square&amp;logo=openjdk&amp;logoColor=white&amp;color=4F7CFF&amp;labelColor=314D9E" alt="Java 17">
    <img src="https://img.shields.io/badge/ABI-ARM64%20%7C%20ARMv7%20%7C%20x86__64%20%7C%20x86-4F7CFF?style=flat-square&amp;color=4F7CFF&amp;labelColor=314D9E" alt="Поддерживаемые архитектуры Android">
    <img src="https://img.shields.io/badge/IPv4%20%2F%20TCP-experimental-4F7CFF?style=flat-square&amp;color=4F7CFF&amp;labelColor=314D9E" alt="Экспериментальная поддержка IPv4 и TCP">
  </p>
  <p><a href="README.md">English</a> · <strong>Русский</strong></p>
</div>

OpenFlux - исследовательский TCP-туннель, маскирующий трафик под сессию
совместного редактирования документа (Yandex Docs, Mail.ru Docs, Cups.online,
MAX) вместо обычного VPN-протокола. OpenFlux Android - приложение для него:
клиент в режиме туннеля или SOCKS5-прокси, выходная нода прямо на телефоне и
мастер, который ставит выходные ноды на ваш сервер.

Приложение работает на ядре OpenFlux
([p1neappleXpress/OpenFlux](https://github.com/p1neappleXpress/OpenFlux)),
которое подключено без изменений сабмодулем `core/`, поэтому оно сразу
совместимо с выходными нодами и десктопными клиентами ядра.

**[Скачать последний релиз для Android](https://github.com/damnurmum/OpenFlux-Android/releases/latest)**

Впервые здесь? [docs/ru/GUIDE.md](docs/ru/GUIDE.md) - пошаговое руководство для
новичков по развёртыванию выходной ноды на VPS и подключению с Android.

```text
Android-туннель или SOCKS5-клиент -> зашифрованный документ-транспорт -> Linux-выходная нода -> Интернет
```

## Возможности

- Android 8+ клиент на системном API `VpnService`, сборки под ARM, ARM64,
  x86 и x86_64;
- второй режим подключения на Android - локальный SOCKS5-прокси, когда
  системный туннель не нужен целиком - опционально доступен из локальной сети
  с авторизацией SOCKS5, плюс `socks://`-ссылка и QR-код;
- профили: сохраняйте несколько конфигураций выходной ноды (транспорт, URL
  документа, секрет) и переключайтесь между ними без повторного ввода;
- мастер «Профили → + → Установить ноду на VPS»: подключение по SSH, выбор Yandex Docs,
  Mail.ru Docs и Cups.online (Direct остаётся резервным), план установки,
  проверка канала и опциональное автообновление ноды;
- режим профиля «Без сервера»: выход - PHP-нода на обычном хостинге
  (`deploy/phpbox` апстрима), связь через одну комнату Cups.online или документ
  Mail.ru, без ключа и Session; через неё идёт только TCP на портах 80 и 443,
  остальной UDP и IPv6 в полном туннеле отбрасываются;
- экспериментальные JS-транспорты («Настройки» -> «JS-транспорты»): подписанные
  скрипты, которые ядро запускает рядом со встроенными транспортами в Session;
  транспорты OpenFlux идут в комплекте, свои импортируются по ссылке или из
  файла после проверки ключа автора, обновляются и откатываются на месте;
- восемь подключаемых транспортов - Yandex.Docs, Yandex Volga, Yandex Board,
  Яндекс Телемост (экспериментально), Mail.ru Docs, Cups.online, Avito Code (экспериментально) и MAX/OneMe - плюс выбор кодека канала (batched+zstd по
  умолчанию, либо legacy per-packet LZ4 для совместимости со старыми
  выходными нодами);
- опциональное аутентифицированное шифрование AES-256-GCM с ключом,
  производным через scrypt, wire-совместимое с бинарниками exit-node и
  клиента апстрима; оставьте ключ пустым для подключения без шифрования к
  обычной выходной ноде;
- хранение URL документа и общего секрета через Android Keystore;
- поля DNS-сервера и MTU применяются только по явному сохранению - уход со
  страницы без сохранения отменяет правку; DNS по UDP/53 идёт через выходную
  ноду, по умолчанию используется `1.1.1.1`;
- маршрутизация трафика по приложениям (белый или чёрный список);
- закреплённое уведомление с живой скоростью загрузки/отдачи и кнопкой
  отключения, для обоих режимов подключения;
- десктопный SOCKS5/utun-клиент и режимы Linux-выходной ноды (`l3` - сырой
  SNAT/DNAT, или `l4` - gVisor proxy) для того же туннеля, см.
  [Десктопный CLI](#десктопный-cli-выходная-нода-и-клиент) ниже.

> **Предупреждение про MAX-транспорт:** бэкенд MAX отправляет пакеты через
> WebRTC DataChannel на вашем аккаунте MAX. Не используйте основной или важный
> аккаунт; запуск с внешнего VPS может привести к ограничениям аккаунта,
> которые сохранятся и после остановки OpenFlux. Считайте MAX-транспорт
> экспериментальным, пока не станет понятнее его детектирование и блокировка.

## Важные ограничения

OpenFlux - экспериментальное исследовательское ПО, не аудированная замена
WireGuard или другого зрелого VPN. Android-туннель поддерживает IPv4 TCP и UDP,
включая DNS по UDP/53 при наличии поддержки UDP на выходной ноде. IPv6 не
туннелируется. Свой DNS-сервер должен быть числовым IPv4-адресом, доступным
с выходной ноды; со старой нодой без UDP DNS через туннель не заработает.
Провайдер документа всё ещё может наблюдать метаданные - время соединений,
объёмы трафика, зашифрованные payload'ы. Любой с доступом на редактирование
документа может разорвать соединение.

Используйте ПО только на системах и в сетях, которыми вы владеете или на
тестирование которых у вас есть разрешение.

### Создание ноды из приложения

Отключите OpenFlux, откройте «Профили → + → Установить ноду на VPS» и введите адрес
Linux-сервера, пользователя SSH и пароль или приватный ключ. Сверьте показанный
отпечаток SSH-ключа с сервером, выберите транспорты и просмотрите план перед
установкой. Для Cups.online приложение создаст комнаты само. Настройка
автообновления общая для всех каналов на этом VPS; мастер показывает её
текущее состояние. Перед установкой профиль сохраняется как непроверенный,
пока проверка соединения не подтвердит работу ноды. SSH-пароль и приватный
ключ приложение не сохраняет.

QR-код профиля можно отсканировать камерой или выбрать фото из галереи через
кнопку QR в разделе «Профили».

## Требования

- Go 1.26.4 или новее для десктопного клиента и выходной ноды;
- Linux VPS/VDS с root-доступом для режима `l3` выходной ноды (`l4` root не
  требует, на любой ОС);
- для сборки Android: Java 17, Android SDK/API 35, Build Tools 35.0.0,
  NDK 27.0.12077973, Gradle 8.14.3 и `gomobile`;
- редактируемый документ, открытый в старом редакторе, для транспортов на
  основе документов (Yandex Docs, Yandex Volga, Mail.ru Docs).

## Подготовка приватной конфигурации

Создайте эти файлы локально и скопируйте те же значения на выходную ноду.
Они исключены `.gitignore` и никогда не должны попадать в коммит:

```bash
printf '%s\n' 'https://ваш-собственный-url-документа' > document-url
openssl rand -base64 32 > encryption-key
chmod 600 document-url encryption-key
```

Ключ шифрования опционален: не указывайте `--encryption-key-file` на обеих
сторонах (и оставьте поле ключа пустым в приложении), чтобы подключаться к
обычной, немодифицированной выходной ноде без шифрования транспорта. Если
задаёте ключ - он должен содержать не менее 16 символов, быть уникальным
случайным значением, а не переиспользованным паролем, и совпадать на обеих
сторонах. Ротируйте URL документа и ключ, если один из них был раскрыт.

## Десктопный CLI (выходная нода и клиент)

Выходная нода и десктопный клиент - один и тот же бинарник, различаются
только флаги. Готовые бинарники под Linux `amd64`/`arm64` (плюс сборки под
macOS и Windows) доступны в [последнем релизе апстрима](https://github.com/p1neappleXpress/OpenFlux/releases/latest).
Релизы этого репозитория содержат Android APK. Ядро это репозиторий апстрима
[OpenFlux](https://github.com/p1neappleXpress/OpenFlux), подключённый здесь
сабмодулем `core/`. Чтобы собрать самостоятельно:

```bash
git clone --recursive https://github.com/damnurmum/OpenFlux-Android.git
cd OpenFlux-Android/core
go build -o openflux .
```

### Выходная нода - l3 (Linux, root)

```bash
sudo ./openflux --role=exit --mode=l3 --transport=yandex \
    --url="$(cat document-url)" --encryption-key-file=encryption-key
```

`l3` форвардит сырые IP-пакеты с SNAT/DNAT (conntrack + фильтр по
egress-IP) - одно TCP-соединение end-to-end, без двойной терминации, но
только Linux и нужен root. TCP-соединения выходной ноды живут в userspace-
стеке, поэтому у ядра нет для них сокета, и оно шлёт RST на каждый ответ,
разрывая туннель. Этот RST нужно гасить - точечно, не хостом целиком:

```bash
# Точечно (рекомендуется): выделите второй/алиас-IP под туннель, запустите
# с --local-ip, затем:
sudo iptables -A OUTPUT -p tcp --tcp-flags RST RST -s 203.0.113.10 -j DROP
sudo ./openflux --role=exit --mode=l3 --local-ip=203.0.113.10 ...

# Fallback на весь хост (дропает ВСЕ исходящие RST; закрытые порты будут
# выглядеть filtered, и хост перестанет сбрасывать посторонние соединения;
# только на однозадачном сервере):
sudo iptables -C OUTPUT -p tcp --tcp-flags RST RST -j DROP 2>/dev/null || \
  sudo iptables -I OUTPUT 1 -p tcp --tcp-flags RST RST -j DROP
```

Пример [systemd-юнита](deploy/openflux.service) ожидает бинарник и приватные
файлы в `/root/openflux`. Проверьте пути перед установкой:

```bash
sudo install -d -m 700 /root/openflux
sudo install -m 755 ./openflux /root/openflux/openflux
sudo install -m 600 ./document-url ./encryption-key /root/openflux/
sudo install -m 644 deploy/openflux.service /etc/systemd/system/openflux.service
sudo systemctl daemon-reload
sudo systemctl enable --now openflux
sudo systemctl status openflux
```

### Выходная нода - l4 (любая ОС, без root)

```bash
./openflux --role=exit --mode=l4 --transport=yandex \
    --url="$(cat document-url)" --encryption-key-file=encryption-key
```

Терминирует TCP в userspace-стеке gVisor и переподключается к реальному
серверу через `net.Dial` - работает везде, ценой двойной терминации TCP.
Используйте на Windows, macOS или Linux-хосте без root.

### Клиент - SOCKS5 (все платформы)

```bash
./openflux --role=client --inbound=socks5 --transport=yandex \
    --url="$(cat document-url)" --encryption-key-file=encryption-key \
    --socks5=127.0.0.1:1080
```

Настройте браузер / приложение на `127.0.0.1:1080` как SOCKS5-прокси. Режим
по умолчанию на всех платформах, кроме macOS.

### Клиент - macOS utun (по умолчанию на macOS)

```bash
sudo ./openflux --role=client --transport=yandex \
    --url="$(cat document-url)" --encryption-key-file=encryption-key
```

Создаёт utun-интерфейс, направляет собственное соединение транспорта
напрямую (в обход туннеля, чтобы оно не зациклилось само на себя), затем
забирает default-маршрут. SOCKS5 не нужен; весь остальной трафик идёт через
туннель.

### Другие транспорты

```bash
# Yandex Volga (HTTP relay + WS)
./openflux --role=exit --mode=l3 --transport=vyandex --url="..." --debug

# MAX / OneMe (WebRTC DataChannel)
./openflux --role=exit --mode=l3 --transport=oneme \
    --maxToken="..." --maxUid="..." --debug

# Cups.online (Centrifugo-комнаты)
./openflux --role=exit --mode=l3 --transport=cupsonline --url="..." --debug

# Mail.ru Docs (WS)
./openflux --role=exit --mode=l3 --transport=mailru \
    --url="https://cloud.mail.ru/public/AbCdEfGh1/IjKlMnOp2" --debug
```

Добавляйте `--debug` только при диагностике проблемы и проверяйте логи перед
тем, как ими делиться - в них может попасть URL документа.

### Флаги

| Флаг | Короткий | По умолчанию | Описание |
|------|----------|--------------|----------|
| `--role` | `-r` | `client` | `client` \| `exit` \| `bench-send` \| `bench-sink` |
| `--inbound` | `-i` | (платформа) | Только клиент: `tun` (macOS) \| `socks5` (по умолчанию везде ещё) |
| `--transport` | `-t` | `yandex` | `yandex` \| `vyandex` \| `oneme` \| `cupsonline` \| `mailru` |
| `--mode` | `-m` | `l3` | Только выходная нода: `l3` \| `l4` |
| `--codec` | `-c` | `batched` | `batched` (zstd+склейка) \| `legacy` (per-packet LZ4) |
| `--url` | `-u` | пусто | URL документа (или weblink для `mailru`) |
| `--socks5` | `-s` | `:1080` | Адрес SOCKS5 (клиент, `--inbound=socks5`) |
| `--local-ip` | `-l` | (авто) | Egress IP выходной ноды, для точечного правила RST (только `l3`) |
| `--encryption-key-file` | | пусто | Файл общего секрета AES-256-GCM; без флага - без шифрования |
| `--maxToken` / `--maxUid` | | пусто | Токен / ID пользователя MAX (`--transport=oneme`) |
| `--bench-bytes` / `--bench-compressible` | | `0` / `false` | Размер / сжимаемость payload'а бенчмарка (`--role=bench-send`) |
| `--debug` | `-d` | `false` | Подробное логирование |

Устаревшие (оставлены на один релиз, маппятся автоматически): `--client`,
`--exit-node`, `--tun`, `--socks5-mode`, `--legacy`, `--bench-send`,
`--bench-sink`.

Batched и legacy wire-форматы не совместимы между собой - клиент и выходная
нода должны использовать один и тот же `--codec`.

## Сборка и установка Android-приложения

Задайте `ANDROID_SDK_ROOT` (или `ANDROID_HOME`), убедитесь, что доступны
`gomobile` и Gradle. Клонируйте с `--recursive` (или выполните
`git submodule update --init` в уже склонированном репозитории), чтобы появился
сабмодуль `core/`, затем выполните:

```bash
go install golang.org/x/mobile/cmd/gomobile@v0.0.0-20260908204917-8b95e45f8d3e
go install golang.org/x/mobile/cmd/gobind@v0.0.0-20260908204917-8b95e45f8d3e
gomobile init
./build_android_app.sh
```

Сборка создаёт отдельные APK для `arm64-v8a`, `armeabi-v7a`, `x86_64` и
`x86`, плюс `OpenFlux-android-universal-debug.apk` для устройств с неизвестной
архитектурой. Перенесите подходящий APK на устройство с Android 8+,
установите, создайте профиль со своим URL документа и общим секретом, затем
подтвердите системный запрос на туннель.

Настройки сохраняются при обычном обновлении приложения "поверх", если ID
приложения и сертификат подписи не меняются. Очистка данных приложения или
удаление стирает их. APK, подписанные другим сертификатом, не могут обновить
существующую установку. Артефакты CI - debug-сборки; APK в GitHub Releases
подписаны постоянным релизным сертификатом проекта. Переход с debug-сборки
на релизный канал требует одного удаления и, соответственно, стирает
сохранённые настройки.

Подробности по Android - в [android/README.md](android/README.md).

## Структура

```
OpenFlux-Android/
  core/                            # Апстрим OpenFlux (сабмодуль): CLI, транспорты, туннель, мост mobile/
  android/                         # Приложение для Android
  build_android_app.sh             # Сборка Android APK поверх core/mobile
  deploy/openflux.service          # Пример systemd-юнита для выходной ноды
  docs/                            # Руководства и документация проекта
```

## Разработка и безопасность

Изменения ядра отправляются в репозиторий апстрима, его CI гоняет Go-тесты.
Чтобы перевести этот репозиторий на более новое ядро, обновите сабмодуль и
пересоберите:

```bash
git -C core fetch origin && git -C core checkout origin/main
./build_android_app.sh
git add core && git commit -m "core: update to upstream <commit>"
```

Как контрибьютить - в [docs/ru/CONTRIBUTING.md](docs/ru/CONTRIBUTING.md).
Пожалуйста, прочитайте [docs/ru/SECURITY.md](docs/ru/SECURITY.md) перед тем, как
сообщать об уязвимости. Список изменений - в
[заметках к релизам](https://github.com/damnurmum/OpenFlux-Android/releases).

## Лицензия

OpenFlux распространяется под лицензией GNU General Public License v3.0 или
новее. См. [LICENSE](LICENSE), [COPYRIGHT](COPYRIGHT) и [NOTICE](NOTICE).
Приложение не одобрено Яндексом или Mail.ru и не связано с ними.
