# Полевой тест M1

## Подготовка на ПК
```bash
cd /Users/vvnovg/navigator/research/vpr_bench
# 1. Модель (после проверки репозиториев владельцем)
uv run vpr-m1 export-onnx --model <модель из M0> --out data/m1/model-fp32.onnx
uv run vpr-m1 quantize-onnx --onnx data/m1/model-fp32.onnx --calib data/refs/refs.csv --n 200 --out data/m1/model-int8.onnx
uv run vpr-bench bench --refs data/refs/refs.csv --queries S1=data/queries/S1/queries.csv \
  --queries S2=data/queries/S2/queries.csv --pool S1,S2 \
  --models onnx:data/m1/model-fp32.onnx,onnx:data/m1/model-int8.onnx --out data/m1/bench
# 2. База одного района — коридор вдоль маршрута M1 (GPX маршрута заранее: например, S1)
uv run vpr-m1 pack-refs --refs data/refs/refs.csv --onnx data/m1/<выбранная модель>.onnx \
  --gpx data/drives/S1/track.gpx --buffer-m 600 --out data/m1/bundle
# 3. Эталон для проверки модели на телефоне
uv run vpr-m1 make-parity --onnx data/m1/bundle/model.onnx --image data/refs/images/<любой>.jpg \
  --out data/m1/bundle/parity
```
Модель на телефон — INT8, если её R@5 (покрытые кадры, prior-500m, S1+S2) ниже FP32 не более чем на 2 п. п.; иначе FP32.

## Установка на телефон
```bash
cd /Users/vvnovg/navigator/android
JAVA_HOME="$(brew --prefix openjdk@17)/libexec/openjdk.jdk/Contents/Home" ./gradlew :app:installDebug
adb shell mkdir -p /sdcard/Android/data/io.visnav.app/files/refpack
adb push /Users/vvnovg/navigator/research/vpr_bench/data/m1/bundle/. /sdcard/Android/data/io.visnav.app/files/refpack/
```
Запустить приложение: в статусе должно быть «База: N эталонов … parity cos=0.99xx».
Проверка: `adb pull /sdcard/Android/data/io.visnav.app/files/logs/parity.json` — смотреть `cosine`
по критерию ниже (в зависимости от того, FP32 модель или INT8).

Что именно сверяет parity: ORT на ARM (телефон) против ORT на x86 (ПК, `make-parity`) на одном и том же
`input.png` (уже приведённом к размеру входа модели — ресайз не участвует в сравнении) — то есть только
порядок каналов RGB и численные различия рантайма ONNX Runtime между архитектурами. Ожидание: FP32
cos ≥ 0.999, INT8 cos ≥ 0.98. Расхождения самого ресайза (area-average на телефоне против cv2.INTER_AREA
на ПК) parity-тестом не покрываются — см. отдельный golden-тест `ResizeGoldenTest` в `android/core`.

## Разминочный заезд (обязателен перед основным)
Перед часовой поездкой — 5 минут в разных условиях (двор, улица, поворот), проверить:
- строку gps_lag stats в отчёте `field-eval` (p50 в разумных пределах, [0, 1500] мс — иначе часы кадра
  и GPS рассинхронизированы);
- разрешение анализа на экране («Кадр: W×H») и его соответствие ожидаемому;
- эффективный fps по разнице `t_ms` между кадрами журнала (не обязательно 2 — см. ниже);
- что `field-eval` вообще отрабатывает на этом коротком журнале без ошибок.

## Поездка
- Телефон на держателе горизонтально, камера смотрит вперёд (как в протоколе M0), на зарядке.
- Приложение должно оставаться на переднем плане и экран — включённым всю поездку (иначе CameraX
  останавливает анализ).
- Маршрут — внутри коридора базы (тот же район, что S1), вне зоны подмены GPS у Кремля.
- Сессия 1: режим «окно по GPS», ≥ 30 минут езды. Сессия 2: режим «визуальное слежение», тот же маршрут.
- Счётчик «Ошибок» на экране должен оставаться 0; если растёт — сохранить журнал и сообщить.
- Разрешение анализа (показано на экране, «Кадр: W×H») и HFOV камеры должны соответствовать значениям,
  использованным для `fetch-refs --fov/--width/--height` (иначе перспектива эталонов и кадров с телефона
  не совпадает).
- На ResNet50 на CPU 2 fps может быть недостижимо — в этом случае фиксируем фактический (эффективный)
  fps по журналу, а не требуем ровно 2.
- После поездки:
```bash
adb pull /sdcard/Android/data/io.visnav.app/files/logs /Users/vvnovg/navigator/research/vpr_bench/data/m1/logs
cd /Users/vvnovg/navigator/research/vpr_bench
uv run vpr-m1 field-eval --log data/m1/logs/session-<…>-gps.jsonl --refpack data/m1/bundle --out data/m1/field-gps.md
uv run vpr-m1 field-eval --log data/m1/logs/session-<…>-visual.jsonl --refpack data/m1/bundle --out data/m1/field-visual.md
```

## Итоги (заполнить)
- Модель, формат (FP32/INT8), размер, R@5 на бенчмарке M0 (FP32 vs INT8): …
- Телефон (модель, SoC), parity cos: …
- Таблицы из field-gps.md и field-visual.md: …
- Задержка: инференс p50/p95, всего p50/p95 (цель NFR-4 ≤ 300 мс — в M4, здесь фиксируем): …
- Эффективный fps (по разности `t_ms` соседних кадров журнала, не обязательно целевые 2 fps на ResNet50/CPU): …
- Нагрев/троттлинг за 30 минут (субъективно + падение fps по журналу): …
- Лаг часов кадра/GPS (`gps_lag_ms` из отчёта field-eval, p50/p95): …

## Критерий выхода M1
- [ ] Фиксация ≤ 20 м в ≥ 70 % покрытых кадров в движении (≥ 2 м/с), режим gps — факт: …
- [ ] parity cos ≥ 0.98 (FP32 ≥ 0.999) — факт: …
- [ ] Разрешение анализа (на экране) и HFOV камеры соответствуют значениям `fetch-refs --fov/--width/--height` — факт: …

## Что переносится в M2
- Если критерий не достигнут: повторное ранжирование (ALIKED + LightGlue), см. SPEC §6.3.
- Поведение режима visual (сколько держится без GPS до срыва) — вход для настройки фильтра в M2.
