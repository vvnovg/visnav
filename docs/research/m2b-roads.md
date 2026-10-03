# M2b: привязка к дорожному графу OSM

## Что нужно
- Выгрузка OSM: Центральный федеральный округ с Geofabrik (`central-fed-district-latest.osm.pbf`). Скачивает владелец, около сотен МБ.
- Записанные поездки M2a (`.jsonl`, `.sensors.jsonl`, `.desc`) и комплект эталонов.

## 1. Граф для поездок
```bash
cd /Users/vvnovg/navigator/research/vpr_bench
mkdir -p data/osm
curl -L -o data/osm/cfd.osm.pbf https://download.geofabrik.de/russia/central-fed-district-latest.osm.pbf
# необязательно, но быстрее: вырезать Москву (brew install osmium-tool)
osmium extract -b 37.30,55.55,37.95,55.95 data/osm/cfd.osm.pbf -o data/osm/moscow.osm.pbf
uv sync --extra dev --extra osm
S=data/m1/logs/session-<ms>-gps
uv run vpr-m2 pack-roads --pbf data/osm/moscow.osm.pbf --log $S.jsonl --buffer-m 300 --out data/m2b/roads
```
В граф попадают проезжие дороги (`motorway` … `service`, без парковочных проездов и закрытых для машин) в коридоре ±300 м вокруг трека.

## 2. Replay: с подсказкой дорогой и без
```bash
cd /Users/vvnovg/navigator/android
export JAVA_HOME="$(brew --prefix openjdk@17)/libexec/openjdk.jdk/Contents/Home"
S=/Users/vvnovg/navigator/research/vpr_bench/data/m1/logs/session-<ms>-gps
R=/Users/vvnovg/navigator/research/vpr_bench/data/m1/bundle
G=/Users/vvnovg/navigator/research/vpr_bench/data/m2b/roads
O="--outage 300:120 --outage 900:180"
./gradlew -q :replay:run --args="--session $S --refpack $R --roads $G --out $S.m2b.jsonl $O"
./gradlew -q :replay:run --args="--session $S --refpack $R --roads $G --out $S.m2b-nc.jsonl $O --no-road-constraint"
./gradlew -q :replay:run --args="--session $S --refpack $R --roads $G --out $S.m2b-dr.jsonl $O --no-visual"
cd /Users/vvnovg/navigator/research/vpr_bench
for t in m2b m2b-nc m2b-dr; do
  uv run vpr-m2 road-eval --traj $S.$t.jsonl --log $S.jsonl --roads data/m2b/roads --out data/m2b/roads-$t.md
  uv run vpr-m2 replay-eval --traj $S.$t.jsonl --log $S.jsonl --out data/m2b/replay-$t.md
done
```
- `road-eval` сравнивает дорогу каждой строки с офлайн-привязкой очищенного GPS-трека к тому же графу.
- `replay-eval` показывает, как подсказка дорогой влияет на точность и дрейф в пропаданиях. Прогон `m2b-dr` — без камеры, только счисление и дорога.

## 3. Телефон
Положить `roadpack.bin` и `roadpack.json` рядом с базой эталонов:
```bash
adb push data/m2b/roads/roadpack.bin data/m2b/roads/roadpack.json /sdcard/Android/data/io.visnav.app/files/refpack/
```
На экране появится строка «Дорога: привязана, N %» и атрибуция OSM. В `.fusion.jsonl` у каждой строки есть дорога. Её можно оценить так же:
```bash
uv run vpr-m2 road-eval --traj <сессия>.fusion.jsonl --log <сессия>.jsonl --roads data/m2b/roads --out data/m2b/roads-phone.md
```

## Как устроена привязка (после ревью)
- **Кандидаты.** От каждой дороги OSM берётся ближайший отрезок, и к ним добавляются отрезки текущих гипотез. Иначе в реальном OSM, где узлы стоят через 5–15 м, правильная параллельная улица выпадает из кандидатов.
- **Подсказка фильтру включается, только если выполнены все условия:**
  - GPS не сливался в фильтр дольше 2 с;
  - уверенность ≥ 90 %;
  - `fit`: до оси дороги не дальше min(2,5σ, 50 м), курс расходится не больше чем на 30°;
  - рядом нет перекрёстка (расстояние до перекрёстка по графу считается через несколько отрезков);
  - скорость ≥ 2 м/с.
- **Поправка по оси дороги:**
  - меняет только положение и только поперёк дороги;
  - не делает фильтр увереннее половины точности самой дороги, поэтому вернувшийся GPS всегда может поправить позицию, даже если ось OSM смещена от полосы.
- **Курс по дороге** (σ 10°) подсказывается со скорости 3 м/с.

## Известные ограничения (до полевых данных)
- **Неуверенная привязка — без подсказки.** Уверенность — это доля среди кандидатов, а не вероятность правильной дороги. Привязка, у которой не выполнено `fit`, фильтру не подсказывает.
- **Ось дороги OSM ≠ полоса движения.** На широких магистралях отклонение — до полуширины проезжей части. Синтетика со смещением оси на 8 м даёт ошибку до 10 м, пока нет GPS.
- **Задержка первого кадра.** Индекс графа строится при первом фиксе в потоке кадров, на большом коридоре первый кадр может задержаться.
- **Дороги вне OSM** (дворы, новые развязки) дают «вне дорог» или неверную привязку. Это видно в таблице неверных отрезков `road-eval`.

## Лицензия
Граф — производная база данных OpenStreetMap (ODbL 1.0, © участники OpenStreetMap).
- В репозиторий он не коммитится.
- Приложение и отчёты показывают атрибуцию.
- При передаче графа другим людям он распространяется под ODbL.

## Итоги (заполнить)
- Сессии и граф (дата выгрузки, число рёбер): …
- Таблицы road-eval и replay-eval для `m2b`, `m2b-nc`, `m2b-dr`: …
- Отрезки с неверной дорогой: где и почему (параллельные улицы, развязки, нет дороги в OSM): …

## Критерий M2b (предложение плана; владелец может скорректировать)
- [ ] NFR-3: правильная дорога ≥ 98 % времени движения — факт: …
- [ ] Правильная дорога в пропаданиях ≥ 98 % — факт: …
- [ ] Подсказка дорогой не ухудшает дрейф NFR-5 (`m2b` против `m2b-nc`) — факт: …
