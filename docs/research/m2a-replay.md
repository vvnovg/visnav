# Replay M2a: фильтр и пропадания GPS

## Запись
Та же поездка и то же приложение, что в протоколе M1 (docs/research/m1-field-test.md), режим «окно по GPS».
Приложение теперь пишет три файла на сессию в `/sdcard/Android/data/io.visnav.app/files/logs/`:
`session-<ms>-gps.jsonl` (кадры), `.sensors.jsonl` (гироскоп и акселерометр 100 Гц, GNSS со скоростью и курсом,
статус спутников) и `.desc` (дескрипторы кадров, ~30 МБ/ч). Изображения не сохраняются.

Требования: телефон жёстко закреплён (дребезг держателя попадает в гироскоп), поездка ≥ 25 минут по городу
вне зоны подмены GPS и без тоннелей — GPS нужен как истинная позиция на всём пути.

## Пропадания
GPS «выключается» программно на replay. Стандартный набор для сессии ≥ 25 мин (секунды от начала):
`--outage 120:30 --outage 300:60 --outage 600:120 --outage 900:300`.

Если в отчёте стоит «⚠️ нет данных для проверки», значит в окнах пропаданий не нашлось кадров в движении с GPS — сдвиньте окна или выберите другую сессию.

```bash
cd /Users/vvnovg/navigator/android
export JAVA_HOME="$(brew --prefix openjdk@17)/libexec/openjdk.jdk/Contents/Home"
S=/Users/vvnovg/navigator/research/vpr_bench/data/m1/logs/session-<ms>-gps
R=/Users/vvnovg/navigator/research/vpr_bench/data/m1/bundle
O="--outage 120:30 --outage 300:60 --outage 600:120 --outage 900:300"
./gradlew -q :replay:run --args="--session $S --refpack $R --out $S.replay-visual.jsonl $O"
./gradlew -q :replay:run --args="--session $S --refpack $R --out $S.replay-dr.jsonl $O --no-visual"
cd /Users/vvnovg/navigator/research/vpr_bench
uv run vpr-m2 replay-eval --traj $S.replay-visual.jsonl --log $S.jsonl --out data/m2a/replay-visual.md
uv run vpr-m2 replay-eval --traj $S.replay-dr.jsonl --log $S.jsonl --out data/m2a/replay-dr.md
```

## Итоги (заполнить)
- Сессии (дата, маршрут, длительность, телефон): …
- Таблицы из replay-visual.md и replay-dr.md: …
- Параметры фильтра (если меняли FilterConfig): …

## Критерий M2a
- [ ] NFR-1 на replay с визуальными фиксациями: P50 ≤ 5 м, P95 ≤ 15 м — факт: …
- [ ] NFR-5 на replay без визуальных фиксаций: дрейф ≤ 3 % пути — факт: …

## Что передаётся в M2b/M2c
- Эпизоды, где фильтр держался на правильной улице / съезжал на соседнюю (вход для привязки к дорогам, M2b).
- Поведение статуса спутников (`gnss` в .sensors.jsonl) в местах подмены и глушения (вход для монитора GNSS, M2c).
