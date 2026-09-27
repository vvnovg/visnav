# M1: Android-прототип визуального позиционирования — план реализации

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Android-приложение, которое раз в 0,5 с берёт кадр с камеры телефона, считает глобальный дескриптор моделью из M0 на устройстве, ищет ближайший эталон в заранее загруженной базе одного района и пишет журнал. Плюс инструменты на ПК: подготовка модели и базы для телефона и оценка журналов поездок против GPS. Критерий M1 из спецификации: **визуальная фиксация ≤ 20 м в ≥ 70 % кадров**.

**Architecture:** Две части со строгим контрактом между ними.
- **ПК (Python, `research/vpr_bench`).**
  - Модель из M0 экспортируется в ONNX со встроенной нормализацией: на вход подаётся uint8 RGB, на выходе L2-нормированный дескриптор.
  - При желании модель квантуется в INT8.
  - Дескрипторы эталонов района считаются той же ONNX-моделью и упаковываются в бинарный `refpack.bin` плюс `refpack.json`.
  - Журналы с телефона (JSONL) оцениваются против GPS, прошедшего фильтр качества из Task 12.6 этапа M0.
- **Android (`android/`).**
  - JVM-модуль `:core` содержит всю логику, которую можно тестировать без устройства: геодезию, float16, парсер refpack, поиск, выбор окна поиска, конвейер, формат журнала.
  - Модуль `:app` — тонкая обвязка: CameraX, ONNX Runtime, LocationManager, Compose-экран.

Фильтр состояния (ESKF) и повторное ранжирование в M1 не входят (спецификация, раздел 9).

**Tech Stack:**
- ПК: Python 3.11+, torch, onnx, onnxscript, onnxruntime, numpy, opencv.
- Android: Kotlin 2.1, AGP 8.7, Gradle 8.10, JDK 17, CameraX 1.4, ONNX Runtime Android 1.20, Jetpack Compose, kotlinx.serialization, JUnit 4.

## Global Constraints

- **Лицензии.** Приложение распространяется под Apache 2.0. В APK допускаются только компоненты под MIT, BSD или Apache 2.0 (SPEC §10а).
  - Сейчас это ONNX Runtime (MIT), CameraX, Compose и kotlinx.serialization (Apache 2.0).
  - Модель — победитель M0 с совместимой лицензией. Пока M0 не завершён, для разработки используется `eigenplaces-r50` (MIT).
  - SALAD (GPL-3.0), MixVPR и SuperPoint в приложение не попадают.
- **Android.** `minSdk = 29` (NFR-9), `compileSdk = targetSdk = 35`, ABI `arm64-v8a`. Пакеты `io.visnav.core` и `io.visnav.app`; applicationId `io.visnav.app` можно сменить до первого релиза.
- **Частота обработки.** 2 кадра в секунду по умолчанию, интервал 500 мс (FR-3: 2–5 fps).
- **Без Google Play Services.** Координаты берутся через `LocationManager.GPS_PROVIDER`, приложение должно работать на устройствах без GMS (RuStore, F-Droid).
- **Кадры не покидают устройство (FR-20).** В журнале нет изображений, только числа.
- **Критерий M1.** Доля кадров с ошибкой визуальной фиксации ≤ 20 м среди *покрытых* кадров в движении (≥ 2 м/с) должна быть ≥ 70 %.
  - Покрытый кадр — тот, у которого есть эталон в пределах 25 м от GPS-позиции (как в M0).
  - Стационарные кадры (скорость по отфильтрованному GPS-треку в окне ±1 с < 2 м/с, как в M0) исключаются
    из покрытия/критерия/ошибок и считаются отдельно (`n_stationary`).
  - Меряется в режиме окна поиска `gps` радиусом 500 м.
  - Режим `visual` пишется для M2 и в критерий не входит.
  - «Истинная» позиция — GPS после `clean_track` и проверки разрывов (Task 12.6 этапа M0).
- **Контракт ONNX.**
  - Вход `image`: uint8, форма `[1, H, W, 3]`, RGB.
  - Выход `descriptor`: float32, форма `[1, D]`, L2-нормирован.
  - H и W фиксированы и читаются из модели.
- **Контракт refpack v1** (little-endian):
  - заголовок 16 байт: `b"VNRP"`, `u16 version=1`, `u16 dtype=1` (float16), `u32 count`, `u32 dim`;
  - затем `f64 lats[count]`, `f64 lons[count]`, `f32 headings[count]`, `f16 descriptors[count*dim]` построчно.
  - Рядом лежит `refpack.json` с полями `format`, `model`, `created_at`, `count`, `dim`, `input_h`, `input_w`, `onnx_sha256`, `source`.
- **Контракт журнала JSONL.**
  - Первая строка — заголовок сессии `{"v":1,"type":"session",...}`.
  - Далее по строке на кадр `{"v":1,"type":"frame","t_ms":...,"mode":"gps"|"visual","gps":{...}|null,"prior":{...}|null,"top":[...],"fix":{...}|null,"lat_ms":{"pre":..,"inf":..,"search":..}}`.
  - Точная строка-образец задана в Task 4 и Task 8 и должна совпадать байт в байт.
- **Команды Gradle** выполняются из `/Users/vvnovg/navigator/android` с JDK 17:
  ```bash
  export JAVA_HOME="$(brew --prefix openjdk@17)/libexec/openjdk.jdk/Contents/Home"
  ```
  Ниже это обозначено как `J17`: `JAVA_HOME=... ./gradlew ...`.
- **Команды Python** выполняются из `/Users/vvnovg/navigator/research/vpr_bench` через `uv run`.
- **Коммиты** заканчиваются строкой `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## Решения до старта

1. **Модель.** Если M0 уже выбрал модель (`docs/research/m0-results.md`), экспортируется она. Если нет — `eigenplaces-r50`, а замена потом сводится к повторному запуску `export-onnx` и `pack-refs`: код приложения от модели не зависит.
2. **Повторное ранжирование.** Если в M0 R@1 в режиме `prior-500m` оказался ниже 70 %, критерий M1 по одному top-1 недостижим. Тогда до полевого теста нужен отдельный план повторного ранжирования по локальным признакам: ALIKED + LightGlue, SPEC §6.3.
3. **Скачивание моделей через `torch.hub`** (Task 1, шаг экспорта реальной модели) — только после того, как владелец просмотрел закреплённые репозитории (см. память проекта и план M0, Task 13).
4. **Установка JDK 17 и Android SDK и принятие лицензий SDK** выполняет владелец (Task 5, шаг 1).

## Вне рамок M1

- Фильтр состояния, IMU и одометрия (M2).
- Отбраковка смазанных кадров и маскирование капота (FR-4).
- Повторное ранжирование.
- Карта, маршруты и подсказки (M3).
- Загрузка базы по сети: база и модель кладутся на телефон через `adb push`.

---

## Структура файлов

```
navigator/
├── .gitignore                                     (Task 5: + android build dirs)
├── research/vpr_bench/
│   ├── pyproject.toml                             (Task 1: + onnx, onnxscript, onnxruntime; + vpr-m1 script)
│   ├── src/vpr_bench/
│   │   ├── onnx_export.py      Task 1  DeviceWrapper, export_onnx, OnnxEmbedder, make_parity
│   │   ├── quantize.py         Task 2  quantize_int8, cosine_parity
│   │   ├── refpack.py          Task 3  write_refpack, read_refpack
│   │   ├── fieldlog.py         Task 4  read_log, evaluate_field, render_field_report
│   │   ├── m1cli.py            Tasks 1–4  vpr-m1 export-onnx | make-parity | quantize-onnx | pack-refs | field-eval
│   │   ├── models.py           Task 1  (+ "onnx:<path>" в load_model)
│   │   ├── cli.py              Task 1  (bench принимает "onnx:<path>")
│   │   └── query.py            Task 4  (_has_gap → has_gap, публичная)
│   └── tests/
│       ├── test_onnx_export.py  test_quantize.py  test_refpack.py  test_fieldlog.py  test_m1cli.py
├── android/
│   ├── settings.gradle.kts  build.gradle.kts  gradle.properties  gradle/libs.versions.toml  gradlew …
│   ├── core/                                      JVM-модуль, всё тестируется без устройства
│   │   ├── build.gradle.kts
│   │   └── src/main/kotlin/io/visnav/core/
│   │       ├── Geo.kt  Half.kt                    Task 5
│   │       ├── RefPack.kt                         Task 6  (RefPack, RefPackMeta)
│   │       ├── GeoIndex.kt                        Task 7
│   │       ├── Prior.kt  FrameLog.kt  Preprocess.kt  Vectors.kt   Task 8
│   │       └── LocalizationPipeline.kt  SessionLogger.kt          Task 9
│   │   └── src/test/kotlin/io/visnav/core/…Test.kt, src/test/resources/refpack_fixture/…
│   └── app/
│       ├── build.gradle.kts
│       └── src/main/
│           ├── AndroidManifest.xml
│           └── kotlin/io/visnav/app/
│               ├── MainActivity.kt  M1Screen.kt  M1Controller.kt   Task 10
│               ├── OrtEmbedder.kt  FrameAnalyzer.kt  GpsSource.kt  Bundle.kt  ParityCheck.kt   Task 10
└── docs/research/m1-field-test.md                 Task 11
```

---

## Часть A. Инструменты на ПК (Python)

### Task 1: Экспорт модели в ONNX и эмбеддер на ONNX Runtime

**Files:**
- Modify: `research/vpr_bench/pyproject.toml`
- Create: `research/vpr_bench/src/vpr_bench/onnx_export.py`
- Create: `research/vpr_bench/src/vpr_bench/m1cli.py`
- Modify: `research/vpr_bench/src/vpr_bench/models.py` (функция `load_model`)
- Modify: `research/vpr_bench/src/vpr_bench/cli.py` (проверка `--models` в `bench`)
- Test: `research/vpr_bench/tests/test_onnx_export.py`, `research/vpr_bench/tests/test_m1cli.py`

**Interfaces:**
- Consumes: `models.VprModel(name, net, image_size, device)` с методом `.embed(list[BGR ndarray]) -> (N, D) float32`; `models.IMAGENET_MEAN/STD`; `models.MODEL_SPECS`; `models.load_model`.
- Produces:
  - `DeviceWrapper(net: torch.nn.Module)` — torch-модуль: uint8 NHWC RGB на входе, L2-нормированный дескриптор на выходе.
  - `export_onnx(net: torch.nn.Module, image_size: tuple[int, int], out_path: Path, opset: int = 18) -> Path`
  - `to_model_input(img_bgr: np.ndarray, image_size: tuple[int, int]) -> np.ndarray` — `[1, h, w, 3]` uint8 RGB, ресайз `cv2.INTER_AREA`.
  - `OnnxEmbedder(path: Path)` с полями `.name: str` (вида `onnx-<stem>-<sha8>`), `.image_size: (h, w)` и методами `.embed(list[BGR]) -> (N, D) float32`, `.size_mb() -> float`.
  - `onnx_sha256(path: Path) -> str`
  - `make_parity(onnx_path: Path, image_bgr: np.ndarray, out_dir: Path) -> Path` — пишет `input.png` (RGB размера модели) и `expected.f32` (little-endian float32 дескриптор).
  - `models.load_model("onnx:<path>")` возвращает `OnnxEmbedder`.
  - `vpr-m1 export-onnx --model NAME --out PATH` и `vpr-m1 make-parity --onnx PATH --image PATH --out DIR`.

- [ ] **Step 1: Добавить зависимости и скрипт**

В `research/vpr_bench/pyproject.toml` в `dependencies` добавить:
```toml
    "onnx>=1.17",
    "onnxscript>=0.2",
    "onnxruntime>=1.20",
```
и в `[project.scripts]`:
```toml
vpr-m1 = "vpr_bench.m1cli:main"
```

Run: `uv sync --extra dev`
Expected: зависимости установились. Если у `onnxruntime` нет колеса под Python 3.14, выполнить `uv python pin 3.12` (закоммитить `.python-version`), затем снова `uv sync --extra dev` и указать это в отчёте.

- [ ] **Step 2: Написать падающие тесты**

`research/vpr_bench/tests/test_onnx_export.py`:
```python
import numpy as np
import onnx
import pytest
import torch

from vpr_bench.models import VprModel, load_model
from vpr_bench.onnx_export import OnnxEmbedder, export_onnx, make_parity, to_model_input

SIZE = (32, 48)  # (h, w)


def _net():
    torch.manual_seed(0)
    return torch.nn.Sequential(
        torch.nn.Conv2d(3, 8, 3, padding=1), torch.nn.ReLU(),
        torch.nn.AdaptiveAvgPool2d(1), torch.nn.Flatten(),
    )


def _images(n):
    rng = np.random.default_rng(0)
    return [rng.integers(0, 255, (60, 80, 3), dtype=np.uint8) for _ in range(n)]


@pytest.fixture
def exported(tmp_path):
    net = _net()
    path = export_onnx(net, SIZE, tmp_path / "m.onnx")
    return net, path


def test_export_contract(exported):
    _, path = exported
    model = onnx.load(str(path))
    inp, out = model.graph.input[0], model.graph.output[0]
    assert inp.name == "image" and out.name == "descriptor"
    assert inp.type.tensor_type.elem_type == onnx.TensorProto.UINT8
    dims = [d.dim_value for d in inp.type.tensor_type.shape.dim]
    assert dims == [1, 32, 48, 3]


def test_onnx_matches_torch(exported):
    net, path = exported
    torch_desc = VprModel("t", net, SIZE).embed(_images(3))
    onnx_desc = OnnxEmbedder(path).embed(_images(3))
    cos = np.sum(torch_desc * onnx_desc, axis=1)
    assert onnx_desc.shape == (3, 8)
    assert np.all(cos > 0.9999)
    assert np.linalg.norm(onnx_desc, axis=1) == pytest.approx([1, 1, 1], abs=1e-5)


def test_embedder_metadata(exported):
    _, path = exported
    emb = OnnxEmbedder(path)
    assert emb.image_size == SIZE
    assert emb.name.startswith("onnx-m-") and len(emb.name) == len("onnx-m-") + 8
    assert emb.size_mb() > 0


def test_to_model_input_shape_and_channel_order():
    img = np.zeros((10, 10, 3), np.uint8)
    img[..., 0] = 255  # синий в BGR
    x = to_model_input(img, (4, 6))
    assert x.shape == (1, 4, 6, 3) and x.dtype == np.uint8
    assert x[0, 0, 0].tolist() == [0, 0, 255]  # RGB: синий в последнем канале


def test_load_model_onnx_prefix(exported):
    _, path = exported
    emb = load_model(f"onnx:{path}")
    assert isinstance(emb, OnnxEmbedder)


def test_make_parity_roundtrip(tmp_path, exported):
    _, path = exported
    out = make_parity(path, _images(1)[0], tmp_path / "parity")
    expected = np.fromfile(out / "expected.f32", dtype="<f4")
    assert expected.shape == (8,)
    import cv2
    png = cv2.imread(str(out / "input.png"))  # BGR
    assert png.shape == (32, 48, 3)
    again = OnnxEmbedder(path).embed([png])[0]
    assert float(np.dot(again, expected)) > 0.9999
```

`research/vpr_bench/tests/test_m1cli.py`:
```python
from vpr_bench.m1cli import build_parser, main


def test_parser_export():
    args = build_parser().parse_args(["export-onnx", "--model", "eigenplaces-r50", "--out", "m.onnx"])
    assert args.command == "export-onnx" and args.model == "eigenplaces-r50"


def test_export_unknown_model_exits_2(tmp_path, capsys):
    assert main(["export-onnx", "--model", "nope", "--out", str(tmp_path / "m.onnx")]) == 2
    assert "unknown model" in capsys.readouterr().err
```

- [ ] **Step 3: Убедиться, что тесты падают**

Run: `uv run pytest tests/test_onnx_export.py tests/test_m1cli.py -v`
Expected: FAIL с `ModuleNotFoundError: No module named 'vpr_bench.onnx_export'`.

- [ ] **Step 4: Реализовать `onnx_export.py`**

`research/vpr_bench/src/vpr_bench/onnx_export.py`:
```python
"""Экспорт VPR-модели в ONNX для телефона и эмбеддер на ONNX Runtime.

Контракт модели: вход "image" uint8 [1, H, W, 3] RGB, выход "descriptor"
float32 [1, D], L2-нормирован. Нормализация ImageNet встроена в граф, чтобы
телефону оставалось только уменьшить кадр и разложить пиксели в RGB.
"""
from __future__ import annotations

import hashlib
from pathlib import Path

import cv2
import numpy as np
import onnxruntime as ort
import torch

from vpr_bench.models import IMAGENET_MEAN, IMAGENET_STD


class DeviceWrapper(torch.nn.Module):
    def __init__(self, net: torch.nn.Module):
        super().__init__()
        self.net = net
        self.register_buffer("mean", torch.tensor(IMAGENET_MEAN).view(1, 3, 1, 1))
        self.register_buffer("std", torch.tensor(IMAGENET_STD).view(1, 3, 1, 1))

    def forward(self, image: torch.Tensor) -> torch.Tensor:
        x = image.permute(0, 3, 1, 2).float() / 255.0
        x = (x - self.mean) / self.std
        out = self.net(x)
        if isinstance(out, (tuple, list)):
            out = out[0]
        return torch.nn.functional.normalize(out.flatten(1), dim=1)


def export_onnx(net: torch.nn.Module, image_size: tuple[int, int], out_path: Path, opset: int = 18) -> Path:
    h, w = image_size
    wrapper = DeviceWrapper(net.eval()).eval()
    dummy = torch.zeros((1, h, w, 3), dtype=torch.uint8)
    out_path.parent.mkdir(parents=True, exist_ok=True)
    with torch.inference_mode():
        torch.onnx.export(
            wrapper, (dummy,), str(out_path),
            input_names=["image"], output_names=["descriptor"], opset_version=opset,
        )
    return out_path


def onnx_sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def to_model_input(img_bgr: np.ndarray, image_size: tuple[int, int]) -> np.ndarray:
    h, w = image_size
    resized = cv2.resize(img_bgr, (w, h), interpolation=cv2.INTER_AREA)
    return cv2.cvtColor(resized, cv2.COLOR_BGR2RGB)[None].astype(np.uint8)


class OnnxEmbedder:
    def __init__(self, path: Path):
        self.path = Path(path)
        self.session = ort.InferenceSession(str(self.path), providers=["CPUExecutionProvider"])
        shape = self.session.get_inputs()[0].shape  # [1, h, w, 3]
        self.image_size = (int(shape[1]), int(shape[2]))
        self.name = f"onnx-{self.path.stem}-{onnx_sha256(self.path)[:8]}"

    def embed(self, images_bgr: list[np.ndarray]) -> np.ndarray:
        out = [
            self.session.run(["descriptor"], {"image": to_model_input(img, self.image_size)})[0][0]
            for img in images_bgr
        ]
        return np.stack(out).astype(np.float32)

    def size_mb(self) -> float:
        return self.path.stat().st_size / 1e6


def make_parity(onnx_path: Path, image_bgr: np.ndarray, out_dir: Path) -> Path:
    """Эталон для проверки на телефоне: картинка уже размера модели + ожидаемый дескриптор."""
    emb = OnnxEmbedder(onnx_path)
    rgb = to_model_input(image_bgr, emb.image_size)[0]
    out_dir.mkdir(parents=True, exist_ok=True)
    cv2.imwrite(str(out_dir / "input.png"), cv2.cvtColor(rgb, cv2.COLOR_RGB2BGR))
    desc = emb.session.run(["descriptor"], {"image": rgb[None]})[0][0]
    desc.astype("<f4").tofile(out_dir / "expected.f32")
    return out_dir
```

- [ ] **Step 5: Добавить `onnx:` в `load_model` и в `bench`**

В `research/vpr_bench/src/vpr_bench/models.py` заменить функцию `load_model` целиком:
```python
def load_model(name: str, device: str = "cpu"):
    if name.startswith("onnx:"):
        from vpr_bench.onnx_export import OnnxEmbedder

        return OnnxEmbedder(Path(name[len("onnx:"):]))
    if name not in MODEL_SPECS:
        raise KeyError(f"unknown model {name!r}; known: {sorted(MODEL_SPECS)}")
    spec = MODEL_SPECS[name]
    net = torch.hub.load(spec.repo, spec.entry, trust_repo=True, **spec.kwargs)
    return VprModel(name, net, spec.image_size, device)
```
и добавить в начало файла `from pathlib import Path`, если его там нет.

В `research/vpr_bench/src/vpr_bench/cli.py` заменить строку
```python
    unknown = [m for m in model_names if m not in MODEL_SPECS]
```
на
```python
    unknown = [
        m for m in model_names
        if m not in MODEL_SPECS and not (m.startswith("onnx:") and Path(m[len("onnx:"):]).is_file())
    ]
```

- [ ] **Step 6: Реализовать `m1cli.py` с командами `export-onnx` и `make-parity`**

`research/vpr_bench/src/vpr_bench/m1cli.py`:
```python
"""CLI этапа M1: vpr-m1 export-onnx | make-parity | quantize-onnx | pack-refs | field-eval."""
from __future__ import annotations

import argparse
import sys
from pathlib import Path

import cv2

from vpr_bench.models import MODEL_SPECS, load_model
from vpr_bench.onnx_export import OnnxEmbedder, export_onnx, make_parity


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="vpr-m1")
    sub = parser.add_subparsers(dest="command", required=True)

    e = sub.add_parser("export-onnx", help="экспортировать модель из реестра в ONNX для телефона")
    e.add_argument("--model", required=True)
    e.add_argument("--out", required=True, type=Path)

    p = sub.add_parser("make-parity", help="эталон для проверки модели на телефоне")
    p.add_argument("--onnx", required=True, type=Path)
    p.add_argument("--image", required=True, type=Path)
    p.add_argument("--out", required=True, type=Path)
    return parser


def _export(args) -> int:
    if args.model not in MODEL_SPECS:
        print(f"error: unknown model {args.model!r}; known: {sorted(MODEL_SPECS)}", file=sys.stderr)
        return 2
    model = load_model(args.model)
    export_onnx(model.net.cpu(), model.image_size, args.out)
    # Проверка: ONNX и PyTorch должны давать один и тот же дескриптор.
    rng_imgs = [_noise(i) for i in range(4)]
    torch_desc = model.embed(rng_imgs)
    onnx_desc = OnnxEmbedder(args.out).embed(rng_imgs)
    min_cos = float((torch_desc * onnx_desc).sum(axis=1).min())
    print(f"exported {args.model} -> {args.out}; min cosine torch/onnx = {min_cos:.5f}")
    if min_cos < 0.999:
        print("error: ONNX output differs from PyTorch (min cosine < 0.999)", file=sys.stderr)
        return 1
    return 0


def _noise(seed: int):
    import numpy as np

    return np.random.default_rng(seed).integers(0, 255, (480, 640, 3), dtype=np.uint8)


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    if args.command == "export-onnx":
        return _export(args)
    if args.command == "make-parity":
        img = cv2.imread(str(args.image))
        if img is None:
            print(f"error: cannot read image {args.image}", file=sys.stderr)
            return 2
        out = make_parity(args.onnx, img, args.out)
        print(f"parity fixture -> {out}")
        return 0
    return 2


if __name__ == "__main__":
    sys.exit(main())
```

- [ ] **Step 7: Убедиться, что тесты проходят**

Run: `uv run pytest tests/test_onnx_export.py tests/test_m1cli.py -v`
Expected: 8 passed.

Run: `uv run pytest -q`
Expected: все тесты зелёные (108 старых + 8 новых = 116 passed, 4 deselected).

Если `torch.onnx.export` в установленной версии torch даёт другие имена входа и выхода или не поддерживает uint8-вход, остановиться и сообщить BLOCKED с текстом ошибки. Ослаблять `test_export_contract` нельзя.

- [ ] **Step 8: Экспорт реальной модели (только после проверки репозиториев владельцем)**

Run: `uv run vpr-m1 export-onnx --model eigenplaces-r50 --out data/m1/eigenplaces-r50.onnx`
Expected: `exported eigenplaces-r50 -> data/m1/eigenplaces-r50.onnx; min cosine torch/onnx = 0.99…`, код возврата 0.

Если владелец ещё не разрешил скачивание моделей через `torch.hub`, шаг пропустить и отметить в отчёте.

- [ ] **Step 9: Commit**

```bash
cd /Users/vvnovg/navigator
git add research/vpr_bench/pyproject.toml research/vpr_bench/uv.lock research/vpr_bench/src/vpr_bench/onnx_export.py research/vpr_bench/src/vpr_bench/m1cli.py research/vpr_bench/src/vpr_bench/models.py research/vpr_bench/src/vpr_bench/cli.py research/vpr_bench/tests/test_onnx_export.py research/vpr_bench/tests/test_m1cli.py
git commit -m "feat(vpr-bench): ONNX export with embedded preprocessing and ONNX embedder

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 2: Квантизация INT8 и проверка совпадения

**Files:**
- Create: `research/vpr_bench/src/vpr_bench/quantize.py`
- Modify: `research/vpr_bench/src/vpr_bench/m1cli.py` (команда `quantize-onnx`)
- Test: `research/vpr_bench/tests/test_quantize.py`

**Interfaces:**
- Consumes: `onnx_export.to_model_input`, `onnx_export.OnnxEmbedder`, `onnx_export.export_onnx`; `dataset.read_places`.
- Produces:
  - `quantize_int8(fp32_path: Path, out_path: Path, calib_images_bgr: list[np.ndarray]) -> Path` — статическая квантизация QDQ, веса int8 по каналам, активации uint8.
  - `cosine_parity(a_path: Path, b_path: Path, images_bgr: list[np.ndarray]) -> dict[str, float]` — ключи `mean`, `min`.
  - `vpr-m1 quantize-onnx --onnx FP32 --calib REFS_CSV --n 200 --out INT8`.

- [ ] **Step 1: Написать падающий тест**

`research/vpr_bench/tests/test_quantize.py`:
```python
import numpy as np
import torch

from vpr_bench.onnx_export import OnnxEmbedder, export_onnx
from vpr_bench.quantize import cosine_parity, quantize_int8


def _net():
    torch.manual_seed(0)
    return torch.nn.Sequential(
        torch.nn.Conv2d(3, 16, 3, padding=1), torch.nn.ReLU(),
        torch.nn.Conv2d(16, 16, 3, padding=1), torch.nn.ReLU(),
        torch.nn.AdaptiveAvgPool2d(1), torch.nn.Flatten(),
    )


def _images(n, seed=0):
    rng = np.random.default_rng(seed)
    return [rng.integers(0, 255, (48, 64, 3), dtype=np.uint8) for _ in range(n)]


def test_quantized_model_close_to_fp32(tmp_path):
    fp32 = export_onnx(_net(), (32, 48), tmp_path / "fp32.onnx")
    int8 = quantize_int8(fp32, tmp_path / "int8.onnx", _images(16))
    assert int8.exists()
    emb = OnnxEmbedder(int8)
    assert emb.image_size == (32, 48)
    parity = cosine_parity(fp32, int8, _images(8, seed=1))
    assert set(parity) == {"mean", "min"}
    assert parity["mean"] > 0.95
    assert parity["min"] <= parity["mean"]


def test_cosine_parity_identical_models(tmp_path):
    fp32 = export_onnx(_net(), (32, 48), tmp_path / "fp32.onnx")
    parity = cosine_parity(fp32, fp32, _images(4))
    assert parity["min"] > 0.9999
```

- [ ] **Step 2: Убедиться, что тест падает**

Run: `uv run pytest tests/test_quantize.py -v`
Expected: FAIL с `ModuleNotFoundError: No module named 'vpr_bench.quantize'`.

- [ ] **Step 3: Реализовать `quantize.py`**

`research/vpr_bench/src/vpr_bench/quantize.py`:
```python
"""Статическая INT8-квантизация ONNX-модели (QDQ) и проверка близости к FP32."""
from __future__ import annotations

import tempfile
from pathlib import Path

import numpy as np
from onnxruntime.quantization import (
    CalibrationDataReader, QuantFormat, QuantType, quantize_static,
)
from onnxruntime.quantization.shape_inference import quant_pre_process

from vpr_bench.onnx_export import OnnxEmbedder, to_model_input


class _CalibReader(CalibrationDataReader):
    def __init__(self, batches: list[dict[str, np.ndarray]]):
        self._it = iter(batches)

    def get_next(self):
        return next(self._it, None)


def quantize_int8(fp32_path: Path, out_path: Path, calib_images_bgr: list[np.ndarray]) -> Path:
    image_size = OnnxEmbedder(fp32_path).image_size
    batches = [{"image": to_model_input(img, image_size)} for img in calib_images_bgr]
    out_path.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory() as tmp:
        prepped = Path(tmp) / "prepped.onnx"
        quant_pre_process(str(fp32_path), str(prepped))
        quantize_static(
            str(prepped), str(out_path), _CalibReader(batches),
            quant_format=QuantFormat.QDQ, per_channel=True,
            weight_type=QuantType.QInt8, activation_type=QuantType.QUInt8,
        )
    return out_path


def cosine_parity(a_path: Path, b_path: Path, images_bgr: list[np.ndarray]) -> dict[str, float]:
    a = OnnxEmbedder(a_path).embed(images_bgr)
    b = OnnxEmbedder(b_path).embed(images_bgr)
    cos = np.sum(a * b, axis=1)
    return {"mean": float(cos.mean()), "min": float(cos.min())}
```

- [ ] **Step 4: Добавить команду `quantize-onnx`**

В `m1cli.py` в `build_parser()` перед `return parser` добавить:
```python
    qz = sub.add_parser("quantize-onnx", help="статическая INT8-квантизация по снимкам-эталонам")
    qz.add_argument("--onnx", required=True, type=Path)
    qz.add_argument("--calib", required=True, type=Path, help="refs.csv, из которого берутся снимки для калибровки")
    qz.add_argument("--n", type=int, default=200)
    qz.add_argument("--out", required=True, type=Path)
```
добавить функцию:
```python
def _quantize(args) -> int:
    from vpr_bench.dataset import read_places
    from vpr_bench.quantize import cosine_parity, quantize_int8

    places = read_places(args.calib)
    if not places or args.n <= 0:
        print("error: need a non-empty --calib and --n > 0", file=sys.stderr)
        return 2
    step = max(1, len(places) // args.n)
    chosen = places[::step][: args.n]
    images = [cv2.imread(str(args.calib.parent / p.path)) for p in chosen]
    if any(img is None for img in images):
        print("error: some calibration images could not be read", file=sys.stderr)
        return 2
    calib, check = images[::2], images[1::2] or images
    quantize_int8(args.onnx, args.out, calib)
    parity = cosine_parity(args.onnx, args.out, check)
    print(
        f"int8 -> {args.out} ({args.out.stat().st_size / 1e6:.1f} MB, fp32 "
        f"{args.onnx.stat().st_size / 1e6:.1f} MB); cosine mean={parity['mean']:.4f} min={parity['min']:.4f}"
    )
    return 0
```
и в `main` перед `return 2`:
```python
    if args.command == "quantize-onnx":
        return _quantize(args)
```

- [ ] **Step 5: Убедиться, что тесты проходят**

Run: `uv run pytest tests/test_quantize.py -v`
Expected: 2 passed.

Run: `uv run pytest -q`
Expected: 118 passed, 4 deselected.

- [ ] **Step 6: Commit**

```bash
cd /Users/vvnovg/navigator
git add research/vpr_bench/src/vpr_bench/quantize.py research/vpr_bench/src/vpr_bench/m1cli.py research/vpr_bench/tests/test_quantize.py
git commit -m "feat(vpr-bench): static INT8 quantization with cosine parity check

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

Как решать, какую модель класть на телефон: FP32 или INT8. Прогнать `vpr-bench bench --models onnx:<fp32>,onnx:<int8> ...` на данных M0. Если R@5 у INT8 (покрытые кадры, `prior-500m`, S1+S2) ниже FP32 не больше чем на 2 п. п., на телефон идёт INT8. Результат записывается в `docs/research/m1-field-test.md` (Task 11).

---

### Task 3: Упаковка базы эталонов для телефона (refpack)

**Files:**
- Create: `research/vpr_bench/src/vpr_bench/refpack.py`
- Modify: `research/vpr_bench/src/vpr_bench/m1cli.py` (команда `pack-refs`)
- Test: `research/vpr_bench/tests/test_refpack.py`

**Interfaces:**
- Consumes: `onnx_export.OnnxEmbedder`, `onnx_export.onnx_sha256`; `pipeline.embed_places(model, places, root)`; `dataset.read_places`; `db_builder.Corridor(tracks, buffer_m).contains(lat, lon)`; `query.parse_gpx`.
- Produces:
  - `write_refpack(out_dir: Path, lats, lons, headings, descriptors: np.ndarray, meta: dict) -> Path` — пишет `out_dir/refpack.bin` и `out_dir/refpack.json`. В `refpack.json` попадает `meta` плюс поля `format="VNRP/1"`, `count`, `dim`.
  - `read_refpack(out_dir: Path) -> RefPackData` с полями `lats`, `lons` (float64), `headings` (float32), `descriptors` (float16, `(count, dim)`), `meta` (dict).
  - `vpr-m1 pack-refs --refs REFS_CSV --onnx MODEL --out DIR [--gpx GPX ...] [--buffer-m 600]` — кладёт в DIR файлы `refpack.bin`, `refpack.json` и копию модели `model.onnx`.

- [ ] **Step 1: Написать падающие тесты**

`research/vpr_bench/tests/test_refpack.py`:
```python
import json
import struct

import cv2
import numpy as np
import pytest
import torch

from vpr_bench.dataset import Place, write_places
from vpr_bench.m1cli import main
from vpr_bench.onnx_export import export_onnx
from vpr_bench.refpack import read_refpack, write_refpack

LATS = [55.7500, 55.7509, 55.7518]
LONS = [37.6, 37.6, 37.6]
HEAD = [0.0, 90.0, 180.0]
DESC = np.array([[1, 0, 0, 0], [0, 1, 0, 0], [0, 0.6, 0.8, 0]], dtype=np.float32)


def test_binary_layout(tmp_path):
    write_refpack(tmp_path, LATS, LONS, HEAD, DESC, {"model": "m"})
    raw = (tmp_path / "refpack.bin").read_bytes()
    assert raw[:4] == b"VNRP"
    version, dtype, count, dim = struct.unpack("<HHII", raw[4:16])
    assert (version, dtype, count, dim) == (1, 1, 3, 4)
    assert len(raw) == 16 + 3 * 8 * 2 + 3 * 4 + 3 * 4 * 2
    assert struct.unpack("<d", raw[16:24])[0] == 55.75
    first_desc_off = 16 + 3 * 20
    assert raw[first_desc_off:first_desc_off + 2] == bytes.fromhex("003c")  # float16 1.0, little-endian


def test_roundtrip_and_meta(tmp_path):
    write_refpack(tmp_path, LATS, LONS, HEAD, DESC, {"model": "m", "created_at": "2026-09-27T00:00:00Z"})
    rp = read_refpack(tmp_path)
    assert rp.lats.tolist() == LATS and rp.lons.tolist() == LONS
    assert rp.headings.tolist() == HEAD
    assert rp.descriptors.dtype == np.float16
    assert rp.descriptors.astype(np.float32) == pytest.approx(DESC, abs=1e-3)
    meta = json.loads((tmp_path / "refpack.json").read_text())
    assert meta["format"] == "VNRP/1" and meta["count"] == 3 and meta["dim"] == 4 and meta["model"] == "m"


def test_length_mismatch_raises(tmp_path):
    with pytest.raises(ValueError):
        write_refpack(tmp_path, LATS[:2], LONS, HEAD, DESC, {})


def _refs(root):
    places = []
    colors = [(0, 0, 255), (0, 255, 0), (255, 0, 0)]
    (root / "images").mkdir(parents=True)
    for i, c in enumerate(colors):
        cv2.imwrite(str(root / f"images/{i}.jpg"), np.full((48, 64, 3), c, np.uint8))
        places.append(Place(f"images/{i}.jpg", LATS[i], LONS[i], HEAD[i]))
    write_places(root / "refs.csv", places)
    return root / "refs.csv"


def test_pack_refs_cli(tmp_path):
    torch.manual_seed(0)
    net = torch.nn.Sequential(torch.nn.AdaptiveAvgPool2d(1), torch.nn.Flatten())
    onnx_path = export_onnx(net, (32, 32), tmp_path / "m.onnx")
    refs = _refs(tmp_path / "refs")
    out = tmp_path / "bundle"
    assert main(["pack-refs", "--refs", str(refs), "--onnx", str(onnx_path), "--out", str(out)]) == 0
    rp = read_refpack(out)
    assert rp.descriptors.shape == (3, 3)
    assert (out / "model.onnx").read_bytes() == onnx_path.read_bytes()
    assert rp.meta["input_h"] == 32 and rp.meta["input_w"] == 32
    assert rp.meta["source"].startswith("Mapillary")
    assert len(rp.meta["onnx_sha256"]) == 64
```

- [ ] **Step 2: Убедиться, что тесты падают**

Run: `uv run pytest tests/test_refpack.py -v`
Expected: FAIL с `ModuleNotFoundError: No module named 'vpr_bench.refpack'`.

- [ ] **Step 3: Реализовать `refpack.py`**

`research/vpr_bench/src/vpr_bench/refpack.py`:
```python
"""refpack v1 — база эталонов для телефона. Формат описан в плане M1 (Global Constraints)."""
from __future__ import annotations

import json
import struct
from dataclasses import dataclass
from pathlib import Path

import numpy as np

MAGIC = b"VNRP"
VERSION = 1
DTYPE_F16 = 1
HEADER = struct.Struct("<4sHHII")


@dataclass(frozen=True)
class RefPackData:
    lats: np.ndarray
    lons: np.ndarray
    headings: np.ndarray
    descriptors: np.ndarray
    meta: dict


def write_refpack(out_dir: Path, lats, lons, headings, descriptors: np.ndarray, meta: dict) -> Path:
    descriptors = np.asarray(descriptors)
    if descriptors.ndim != 2:
        raise ValueError("descriptors must be 2-D (count, dim)")
    count, dim = descriptors.shape
    if not (len(lats) == len(lons) == len(headings) == count):
        raise ValueError("lats, lons, headings and descriptors must have equal length")
    out_dir.mkdir(parents=True, exist_ok=True)
    with (out_dir / "refpack.bin").open("wb") as f:
        f.write(HEADER.pack(MAGIC, VERSION, DTYPE_F16, count, dim))
        f.write(np.asarray(lats, dtype="<f8").tobytes())
        f.write(np.asarray(lons, dtype="<f8").tobytes())
        f.write(np.asarray(headings, dtype="<f4").tobytes())
        f.write(np.ascontiguousarray(descriptors, dtype="<f2").tobytes())
    full_meta = {**meta, "format": "VNRP/1", "count": count, "dim": dim}
    (out_dir / "refpack.json").write_text(json.dumps(full_meta, ensure_ascii=False, indent=2))
    return out_dir


def read_refpack(out_dir: Path) -> RefPackData:
    raw = (out_dir / "refpack.bin").read_bytes()
    magic, version, dtype, count, dim = HEADER.unpack_from(raw, 0)
    if magic != MAGIC or version != VERSION or dtype != DTYPE_F16:
        raise ValueError("not a refpack v1 float16 file")
    off = HEADER.size
    lats = np.frombuffer(raw, "<f8", count, off); off += 8 * count
    lons = np.frombuffer(raw, "<f8", count, off); off += 8 * count
    headings = np.frombuffer(raw, "<f4", count, off); off += 4 * count
    desc = np.frombuffer(raw, "<f2", count * dim, off).reshape(count, dim)
    if off + 2 * count * dim != len(raw):
        raise ValueError("refpack size does not match header")
    meta = json.loads((out_dir / "refpack.json").read_text())
    return RefPackData(lats.copy(), lons.copy(), headings.copy(), desc.copy(), meta)
```

- [ ] **Step 4: Добавить команду `pack-refs`**

В `m1cli.py` в `build_parser()` перед `return parser`:
```python
    pk = sub.add_parser("pack-refs", help="посчитать дескрипторы эталонов моделью телефона и упаковать refpack")
    pk.add_argument("--refs", required=True, type=Path)
    pk.add_argument("--onnx", required=True, type=Path)
    pk.add_argument("--out", required=True, type=Path)
    pk.add_argument("--gpx", action="append", default=[], type=Path, help="ограничить базу коридором вдоль треков")
    pk.add_argument("--buffer-m", type=float, default=600.0)
```
функция:
```python
def _pack(args) -> int:
    import json
    import shutil
    from datetime import datetime, timezone

    from vpr_bench.dataset import read_places
    from vpr_bench.db_builder import Corridor
    from vpr_bench.onnx_export import onnx_sha256
    from vpr_bench.pipeline import embed_places
    from vpr_bench.query import parse_gpx
    from vpr_bench.refpack import write_refpack

    places = read_places(args.refs)
    if args.gpx:
        corridor = Corridor([parse_gpx(p) for p in args.gpx], args.buffer_m)
        places = [p for p in places if corridor.contains(p.lat, p.lon)]
    if not places:
        print("error: no reference places to pack", file=sys.stderr)
        return 2
    emb = OnnxEmbedder(args.onnx)
    desc, ms = embed_places(emb, places, args.refs.parent)
    meta = {
        "model": args.onnx.stem,
        "onnx_sha256": onnx_sha256(args.onnx),
        "input_h": emb.image_size[0],
        "input_w": emb.image_size[1],
        "created_at": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "source": "Mapillary (CC BY-SA 4.0)",
    }
    refs_meta = args.refs.parent / "meta.json"
    if refs_meta.exists():
        meta["refs_meta"] = json.loads(refs_meta.read_text())
    write_refpack(
        args.out, [p.lat for p in places], [p.lon for p in places], [p.heading for p in places], desc, meta,
    )
    shutil.copyfile(args.onnx, args.out / "model.onnx")
    size_mb = (args.out / "refpack.bin").stat().st_size / 1e6
    print(f"{len(places)} refs, dim {desc.shape[1]}, {size_mb:.1f} MB -> {args.out} ({ms:.0f} ms/image on PC)")
    return 0
```
и в `main`:
```python
    if args.command == "pack-refs":
        return _pack(args)
```

- [ ] **Step 5: Убедиться, что тесты проходят**

Run: `uv run pytest tests/test_refpack.py -v`
Expected: 4 passed.

Run: `uv run pytest -q`
Expected: 122 passed, 4 deselected.

- [ ] **Step 6: Commit**

```bash
cd /Users/vvnovg/navigator
git add research/vpr_bench/src/vpr_bench/refpack.py research/vpr_bench/src/vpr_bench/m1cli.py research/vpr_bench/tests/test_refpack.py
git commit -m "feat(vpr-bench): refpack v1 writer/reader and pack-refs command

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 4: Оценка журналов поездок (field-eval)

**Files:**
- Modify: `research/vpr_bench/src/vpr_bench/query.py` (переименовать `_has_gap` в `has_gap`)
- Create: `research/vpr_bench/src/vpr_bench/fieldlog.py`
- Modify: `research/vpr_bench/src/vpr_bench/m1cli.py` (команда `field-eval`)
- Test: `research/vpr_bench/tests/test_fieldlog.py`

**Interfaces:**
- Consumes: `query.clean_track(track, max_speed_mps, max_hdop) -> (track, counts)`; `query.has_gap(times, t, max_gap_s) -> bool` (после переименования); `geo.TrackPoint(t, lat, lon)`, `geo.interpolate_track`, `geo.haversine_m`, `geo.haversine_m_vec`; `refpack.read_refpack`.
- Produces:
  - `FieldFrame(t_ms: int, mode: str, gps: tuple[float, float, float, int] | None, fix: tuple[float, float, float] | None, lat_ms: dict[str, float])`. `gps` = `(lat, lon, acc_m, t_ms)`, `fix` = `(lat, lon, sim)`.
  - `read_log(path: Path) -> tuple[dict, list[FieldFrame]]` — возвращает заголовок сессии и кадры.
  - `gps_track(frames, max_acc_m: float = 20.0) -> list[TrackPoint]`
  - `FieldResult(mode, n_frames, n_with_gt, n_covered, coverage, frac_within, threshold_m, median_err_m, p95_err_m, latency_ms: dict[str, dict[str, float]])`
  - `evaluate_field(frames, ref_lats, ref_lons, threshold_m=20.0, cover_m=25.0, max_gap_s=3.0, max_speed_mps=70.0) -> list[FieldResult]` — по одному результату на режим, в порядке первого появления.
  - `render_field_report(header: dict, results: list[FieldResult], target: float = 0.70) -> str`
  - `vpr-m1 field-eval --log SESSION.jsonl --refpack DIR --out REPORT.md`

Строка-образец кадра. Её же обязан выдавать Kotlin (Task 8), байт в байт:
```
{"v":1,"type":"frame","t_ms":1700000000123,"mode":"gps","gps":{"lat":55.75,"lon":37.6,"acc_m":4.5,"t_ms":1700000000000},"prior":{"lat":55.75,"lon":37.6,"radius_m":500.0},"top":[{"i":1,"sim":0.75,"lat":55.7509,"lon":37.6}],"fix":{"lat":55.7509,"lon":37.6,"sim":0.75},"lat_ms":{"pre":10.5,"inf":80.25,"search":2.0}}
```

- [ ] **Step 1: Переименовать `_has_gap`**

В `research/vpr_bench/src/vpr_bench/query.py` переименовать функцию `_has_gap` в `has_gap`, обновить все её вызовы в этом файле и в `tests/` (найти командой `grep -rn "_has_gap" src tests`).

Run: `uv run pytest -q`
Expected: 122 passed, 4 deselected (поведение не изменилось).

- [ ] **Step 2: Написать падающие тесты**

`research/vpr_bench/tests/test_fieldlog.py`:
```python
import json

import numpy as np
import pytest

from vpr_bench.fieldlog import FieldFrame, evaluate_field, gps_track, read_log, render_field_report
from vpr_bench.geo import offset_m

SAMPLE_LINE = (
    '{"v":1,"type":"frame","t_ms":1700000000123,"mode":"gps","gps":{"lat":55.75,"lon":37.6,"acc_m":4.5,'
    '"t_ms":1700000000000},"prior":{"lat":55.75,"lon":37.6,"radius_m":500.0},"top":[{"i":1,"sim":0.75,'
    '"lat":55.7509,"lon":37.6}],"fix":{"lat":55.7509,"lon":37.6,"sim":0.75},"lat_ms":{"pre":10.5,'
    '"inf":80.25,"search":2.0}}'
)
HEADER_LINE = json.dumps({
    "v": 1, "type": "session", "model": "m", "refpack_created_at": "2026-09-27T00:00:00Z",
    "device": "test", "started_ms": 1700000000000, "mode": "gps",
})

T0 = 1_700_000_000_000
LAT0, LON0 = 55.75, 37.6


def test_read_log_parses_sample(tmp_path):
    p = tmp_path / "s.jsonl"
    p.write_text(HEADER_LINE + "\n" + SAMPLE_LINE + "\n")
    header, frames = read_log(p)
    assert header["model"] == "m"
    assert frames == [FieldFrame(
        t_ms=1700000000123, mode="gps", gps=(55.75, 37.6, 4.5, 1700000000000),
        fix=(55.7509, 37.6, 0.75), lat_ms={"pre": 10.5, "inf": 80.25, "search": 2.0},
    )]


def _drive(n=30, fix_err_m=10.0, mode="gps", fix_every=1):
    """Едем на север 10 м/с, GPS раз в секунду, кадр раз в секунду; фиксация с ошибкой fix_err_m на восток."""
    frames = []
    for s in range(n):
        lat, lon = offset_m(LAT0, LON0, 0.0, 10.0 * s)
        fix = None
        if s % fix_every == 0:
            flat, flon = offset_m(lat, lon, fix_err_m, 0.0)
            fix = (flat, flon, 0.8)
        frames.append(FieldFrame(T0 + 1000 * s, mode, (lat, lon, 5.0, T0 + 1000 * s), fix,
                                 {"pre": 5.0, "inf": 50.0, "search": 1.0}))
    return frames


def _refs_along(n=30):
    pts = [offset_m(LAT0, LON0, 0.0, 10.0 * s) for s in range(n)]
    return np.array([p[0] for p in pts]), np.array([p[1] for p in pts])


def test_gps_track_filters_by_accuracy():
    frames = _drive(5)
    bad = FieldFrame(T0 + 500, "gps", (0.0, 0.0, 99.0, T0 + 500), None, {"pre": 0, "inf": 0, "search": 0})
    track = gps_track(frames + [bad])
    assert len(track) == 5 and all(p.lat > 50 for p in track)


def test_all_within_threshold():
    lats, lons = _refs_along()
    [r] = evaluate_field(_drive(fix_err_m=10.0), lats, lons)
    assert r.mode == "gps" and r.n_frames == 30
    assert r.n_with_gt == 28  # первый и последний кадр без окна ±1 с
    assert r.coverage == 1.0 and r.frac_within == 1.0
    assert r.median_err_m == pytest.approx(10.0, abs=0.5)
    assert r.latency_ms["total"]["p50"] == pytest.approx(56.0)


def test_far_fixes_and_missing_fixes_fail():
    lats, lons = _refs_along()
    [r] = evaluate_field(_drive(fix_err_m=50.0), lats, lons)
    assert r.frac_within == 0.0
    [r2] = evaluate_field(_drive(fix_err_m=5.0, fix_every=2), lats, lons)
    assert 0.4 < r2.frac_within < 0.6
    assert r2.p95_err_m == float("inf")


def test_uncovered_frames_excluded():
    lats, lons = _refs_along(15)  # эталоны только на первой половине пути
    [r] = evaluate_field(_drive(fix_err_m=5.0), lats, lons)
    assert r.n_covered < r.n_with_gt
    assert r.coverage == pytest.approx(r.n_covered / r.n_with_gt)
    assert r.frac_within == 1.0


def test_modes_reported_separately_and_report_marks_gps_only():
    lats, lons = _refs_along()
    frames = _drive(mode="gps") + [
        FieldFrame(f.t_ms, "visual", f.gps, f.fix, f.lat_ms) for f in _drive(fix_err_m=50.0)
    ]
    results = evaluate_field(frames, lats, lons)
    assert [r.mode for r in results] == ["gps", "visual"]
    md = render_field_report({"model": "m", "device": "d"}, results)
    lines = [l for l in md.splitlines() if l.startswith("| gps") or l.startswith("| visual")]
    assert "✅" in lines[0] and "—" in lines[1]
```

- [ ] **Step 3: Убедиться, что тесты падают**

Run: `uv run pytest tests/test_fieldlog.py -v`
Expected: FAIL с `ModuleNotFoundError: No module named 'vpr_bench.fieldlog'`.

- [ ] **Step 4: Реализовать `fieldlog.py`**

`research/vpr_bench/src/vpr_bench/fieldlog.py`:
```python
"""Оценка журнала поездки с телефона (M1): визуальные фиксации против отфильтрованного GPS."""
from __future__ import annotations

import json
from dataclasses import dataclass
from pathlib import Path

import numpy as np

from vpr_bench.geo import TrackPoint, haversine_m, haversine_m_vec, interpolate_track
from vpr_bench.query import clean_track, has_gap


@dataclass(frozen=True)
class FieldFrame:
    t_ms: int
    mode: str
    gps: tuple[float, float, float, int] | None
    fix: tuple[float, float, float] | None
    lat_ms: dict[str, float]


@dataclass(frozen=True)
class FieldResult:
    mode: str
    n_frames: int
    n_with_gt: int
    n_covered: int
    coverage: float
    frac_within: float
    threshold_m: float
    median_err_m: float
    p95_err_m: float
    latency_ms: dict[str, dict[str, float]]


def read_log(path: Path) -> tuple[dict, list[FieldFrame]]:
    header: dict = {}
    frames: list[FieldFrame] = []
    for line in path.read_text().splitlines():
        if not line.strip():
            continue
        rec = json.loads(line)
        if rec.get("type") == "session":
            header = rec
            continue
        g, f = rec.get("gps"), rec.get("fix")
        frames.append(FieldFrame(
            t_ms=int(rec["t_ms"]),
            mode=rec["mode"],
            gps=(g["lat"], g["lon"], g["acc_m"], int(g["t_ms"])) if g else None,
            fix=(f["lat"], f["lon"], f["sim"]) if f else None,
            lat_ms=dict(rec["lat_ms"]),
        ))
    return header, frames


def gps_track(frames: list[FieldFrame], max_acc_m: float = 20.0) -> list[TrackPoint]:
    seen: dict[int, TrackPoint] = {}
    for fr in frames:
        if fr.gps is None:
            continue
        lat, lon, acc, t_ms = fr.gps
        if acc <= max_acc_m:
            seen.setdefault(t_ms, TrackPoint(t_ms / 1000.0, lat, lon))
    return sorted(seen.values(), key=lambda p: p.t)


def _quantiles(values: list[float]) -> dict[str, float]:
    if not values:
        return {"p50": float("nan"), "p95": float("nan")}
    arr = np.array(values)
    return {"p50": float(np.median(arr)), "p95": float(np.quantile(arr, 0.95, method="higher"))}


def evaluate_field(
    frames: list[FieldFrame],
    ref_lats: np.ndarray,
    ref_lons: np.ndarray,
    threshold_m: float = 20.0,
    cover_m: float = 25.0,
    max_gap_s: float = 3.0,
    max_speed_mps: float = 70.0,
) -> list[FieldResult]:
    track, _ = clean_track(gps_track(frames), max_speed_mps=max_speed_mps, max_hdop=None)
    times = [p.t for p in track]
    modes = list(dict.fromkeys(fr.mode for fr in frames))
    results = []
    for mode in modes:
        mf = [fr for fr in frames if fr.mode == mode]
        n_gt = n_cov = n_ok = 0
        errors: list[float] = []
        for fr in mf:
            t = fr.t_ms / 1000.0
            gt = interpolate_track(track, t)
            if gt is None or has_gap(times, t, max_gap_s):
                continue
            n_gt += 1
            if len(ref_lats) == 0 or haversine_m_vec(gt[0], gt[1], ref_lats, ref_lons).min() > cover_m:
                continue
            n_cov += 1
            err = haversine_m(gt[0], gt[1], fr.fix[0], fr.fix[1]) if fr.fix else float("inf")
            errors.append(err)
            n_ok += err <= threshold_m
        err_arr = np.array(errors) if errors else np.array([float("nan")])
        lat = {k: _quantiles([fr.lat_ms[k] for fr in mf]) for k in ("pre", "inf", "search")}
        lat["total"] = _quantiles([sum(fr.lat_ms.values()) for fr in mf])
        results.append(FieldResult(
            mode=mode, n_frames=len(mf), n_with_gt=n_gt, n_covered=n_cov,
            coverage=n_cov / n_gt if n_gt else 0.0,
            frac_within=n_ok / n_cov if n_cov else 0.0,
            threshold_m=threshold_m,
            median_err_m=float(np.quantile(err_arr, 0.5, method="higher")),
            p95_err_m=float(np.quantile(err_arr, 0.95, method="higher")),
            latency_ms=lat,
        ))
    return results


def render_field_report(header: dict, results: list[FieldResult], target: float = 0.70) -> str:
    lines = [
        "# Полевой тест M1",
        "",
        f"Модель: {header.get('model', '—')}. Устройство: {header.get('device', '—')}.",
        f"Критерий M1: фиксация ≤ {results[0].threshold_m:g} м в ≥ {target * 100:.0f} % покрытых кадров, режим gps."
        if results else "Нет кадров.",
        "",
        "| Режим | Кадров | С GPS | Покрытие, % | ≤ порога, % | Медиана, м | P95, м "
        "| Инференс p50/p95, мс | Всего p50/p95, мс | Критерий |",
        "|---|---|---|---|---|---|---|---|---|---|",
    ]
    for r in results:
        verdict = ("✅" if r.frac_within >= target else "❌") if r.mode == "gps" else "—"
        inf, tot = r.latency_ms["inf"], r.latency_ms["total"]
        lines.append(
            f"| {r.mode} | {r.n_frames} | {r.n_with_gt} | {r.coverage * 100:.1f} | {r.frac_within * 100:.1f} "
            f"| {r.median_err_m:.1f} | {r.p95_err_m:.1f} | {inf['p50']:.0f}/{inf['p95']:.0f} "
            f"| {tot['p50']:.0f}/{tot['p95']:.0f} | {verdict} |"
        )
    return "\n".join(lines) + "\n"
```

- [ ] **Step 5: Добавить команду `field-eval`**

В `m1cli.py` в `build_parser()` перед `return parser`:
```python
    fe = sub.add_parser("field-eval", help="оценить журнал поездки с телефона")
    fe.add_argument("--log", required=True, type=Path)
    fe.add_argument("--refpack", required=True, type=Path)
    fe.add_argument("--out", required=True, type=Path)
```
функция:
```python
def _field_eval(args) -> int:
    from vpr_bench.fieldlog import evaluate_field, read_log, render_field_report
    from vpr_bench.refpack import read_refpack

    header, frames = read_log(args.log)
    if not frames:
        print("error: log has no frames", file=sys.stderr)
        return 2
    rp = read_refpack(args.refpack)
    results = evaluate_field(frames, rp.lats, rp.lons)
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(render_field_report(header, results))
    for r in results:
        print(f"{r.mode}: {r.frac_within * 100:.1f}% within {r.threshold_m:g} m "
              f"(covered {r.n_covered}/{r.n_with_gt}, coverage {r.coverage * 100:.1f}%)")
    print(f"report -> {args.out}")
    return 0
```
и в `main`:
```python
    if args.command == "field-eval":
        return _field_eval(args)
```

- [ ] **Step 6: Убедиться, что тесты проходят**

Run: `uv run pytest tests/test_fieldlog.py -v`
Expected: 6 passed.

Run: `uv run pytest -q`
Expected: 128 passed, 4 deselected.

- [ ] **Step 7: Commit**

```bash
cd /Users/vvnovg/navigator
git add research/vpr_bench/src/vpr_bench/query.py research/vpr_bench/src/vpr_bench/fieldlog.py research/vpr_bench/src/vpr_bench/m1cli.py research/vpr_bench/tests
git commit -m "feat(vpr-bench): field-eval for phone session logs against filtered GPS

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

## Часть B. Android

### Task 5: Тулчейн, каркас проекта, геодезия и float16

**Files:**
- Modify: `/Users/vvnovg/navigator/.gitignore`
- Create: `android/settings.gradle.kts`, `android/build.gradle.kts`, `android/gradle.properties`, `android/gradle/libs.versions.toml`, wrapper (`android/gradlew`, `android/gradlew.bat`, `android/gradle/wrapper/*`)
- Create: `android/core/build.gradle.kts`, `android/core/src/main/kotlin/io/visnav/core/Geo.kt`, `android/core/src/main/kotlin/io/visnav/core/Half.kt`
- Create: `android/app/build.gradle.kts`, `android/app/src/main/AndroidManifest.xml`, `android/app/src/main/kotlin/io/visnav/app/MainActivity.kt` (заглушка)
- Test: `android/core/src/test/kotlin/io/visnav/core/GeoTest.kt`, `android/core/src/test/kotlin/io/visnav/core/HalfTest.kt`

**Interfaces:**
- Produces:
  - `Geo.EARTH_RADIUS_M: Double`
  - `Geo.haversineM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double`
  - `Half.toFloat(h: Short): Float`
  - `Half.LUT: FloatArray` (65536 значений, индекс `h.toInt() and 0xFFFF`)

- [ ] **Step 1: Установка тулчейна (выполняет владелец)**

Эти команды ставят системный софт и принимают лицензии Android SDK, поэтому их запускает владелец:
```bash
brew install openjdk@17
brew install --cask android-commandlinetools
export ANDROID_HOME="$(brew --prefix)/share/android-commandlinetools"
sdkmanager --licenses
sdkmanager "platform-tools" "platforms;android-35" "build-tools;35.0.0"
brew install --cask android-platform-tools
```
После этого исполнитель проверяет, что `ls "$(brew --prefix)/share/android-commandlinetools/platforms/android-35"` выводит содержимое.

- [ ] **Step 2: Gradle wrapper**

Wrapper создаётся до build-файлов, чтобы системный Gradle не пытался собирать проект с AGP:
```bash
mkdir -p /Users/vvnovg/navigator/android && cd /Users/vvnovg/navigator/android
touch settings.gradle.kts
gradle wrapper --gradle-version 8.10.2 --distribution-type bin
```
Expected: появились `gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.properties` с `gradle-8.10.2-bin.zip`.

`android/local.properties` (в git не попадает):
```properties
sdk.dir=/opt/homebrew/share/android-commandlinetools
```

- [ ] **Step 3: Build-файлы**

Добавить в `/Users/vvnovg/navigator/.gitignore`:
```gitignore
android/.gradle/
android/build/
android/*/build/
android/local.properties
android/.kotlin/
```

`android/settings.gradle.kts`:
```kotlin
pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "visnav"
include(":core", ":app")
```

`android/gradle.properties`:
```properties
org.gradle.jvmargs=-Xmx4g -Dfile.encoding=UTF-8
android.useAndroidX=true
kotlin.code.style=official
```

`android/gradle/libs.versions.toml`:
```toml
[versions]
agp = "8.7.3"
kotlin = "2.1.0"
coreKtx = "1.15.0"
activityCompose = "1.9.3"
composeBom = "2024.12.01"
camerax = "1.4.1"
onnxruntime = "1.20.0"
serialization = "1.7.3"
junit = "4.13.2"

[libraries]
androidx-core-ktx = { group = "androidx.core", name = "core-ktx", version.ref = "coreKtx" }
androidx-activity-compose = { group = "androidx.activity", name = "activity-compose", version.ref = "activityCompose" }
compose-bom = { group = "androidx.compose", name = "compose-bom", version.ref = "composeBom" }
compose-ui = { group = "androidx.compose.ui", name = "ui" }
compose-material3 = { group = "androidx.compose.material3", name = "material3" }
camerax-core = { group = "androidx.camera", name = "camera-core", version.ref = "camerax" }
camerax-camera2 = { group = "androidx.camera", name = "camera-camera2", version.ref = "camerax" }
camerax-lifecycle = { group = "androidx.camera", name = "camera-lifecycle", version.ref = "camerax" }
camerax-view = { group = "androidx.camera", name = "camera-view", version.ref = "camerax" }
onnxruntime-android = { group = "com.microsoft.onnxruntime", name = "onnxruntime-android", version.ref = "onnxruntime" }
kotlinx-serialization-json = { group = "org.jetbrains.kotlinx", name = "kotlinx-serialization-json", version.ref = "serialization" }
junit = { group = "junit", name = "junit", version.ref = "junit" }

[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }
kotlin-android = { id = "org.jetbrains.kotlin.android", version.ref = "kotlin" }
kotlin-jvm = { id = "org.jetbrains.kotlin.jvm", version.ref = "kotlin" }
kotlin-compose = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
kotlin-serialization = { id = "org.jetbrains.kotlin.plugin.serialization", version.ref = "kotlin" }
```
Если какая-то из версий не резолвится, поднять её до ближайшей стабильной и указать это в отчёте. Ослаблять `minSdk` и другие ограничения нельзя.

`android/build.gradle.kts`:
```kotlin
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}
```

`android/core/build.gradle.kts`:
```kotlin
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
    testImplementation(kotlin("test-junit"))
}
```

`android/app/build.gradle.kts`:
```kotlin
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "io.visnav.app"
    compileSdk = 35
    defaultConfig {
        applicationId = "io.visnav.app"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1-m1"
        ndk { abiFilters += listOf("arm64-v8a") }
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation(project(":core"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.camerax.core)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.view)
    implementation(libs.onnxruntime.android)
}
```

`android/app/src/main/AndroidManifest.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <uses-feature android:name="android.hardware.camera" android:required="true" />
    <uses-permission android:name="android.permission.CAMERA" />
    <uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
    <uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />

    <application
        android:label="VisNav M1"
        android:theme="@android:style/Theme.Material.NoActionBar">
        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:screenOrientation="landscape">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
```

`android/app/src/main/kotlin/io/visnav/app/MainActivity.kt` (заглушка, полностью заменяется в Task 10):
```kotlin
package io.visnav.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Text

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { Text("VisNav M1") }
    }
}
```

- [ ] **Step 4: Написать падающие тесты**

`android/core/src/test/kotlin/io/visnav/core/GeoTest.kt`:
```kotlin
package io.visnav.core

import kotlin.test.Test
import kotlin.test.assertEquals

class GeoTest {
    @Test fun oneDegreeLatitude() =
        assertEquals(111_195.0, Geo.haversineM(0.0, 0.0, 1.0, 0.0), 111.0)

    // Эталоны посчитаны vpr_bench.geo.haversine_m — формулы должны совпадать.
    @Test fun matchesPythonImplementation() {
        assertEquals(1275.9191159603824, Geo.haversineM(55.75, 37.62, 55.76, 37.63), 1e-6)
        assertEquals(100.07543398040785, Geo.haversineM(55.75, 37.6, 55.7509, 37.6), 1e-6)
    }

    @Test fun zeroDistance() = assertEquals(0.0, Geo.haversineM(55.75, 37.6, 55.75, 37.6), 0.0)
}
```

`android/core/src/test/kotlin/io/visnav/core/HalfTest.kt`:
```kotlin
package io.visnav.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HalfTest {
    private fun h(bits: Int) = Half.toFloat(bits.toShort())

    @Test fun normals() {
        assertEquals(1.0f, h(0x3C00))
        assertEquals(-2.0f, h(0xC000))
        assertEquals(0.5f, h(0x3800))
        assertEquals(0.60009765625f, h(0x38CD)) // numpy float16(0.6)
        assertEquals(0.7998046875f, h(0x3A66))  // numpy float16(0.8)
    }

    @Test fun zerosSubnormalsAndSpecials() {
        assertEquals(0.0f, h(0x0000))
        assertEquals(-0.0f, h(0x8000))
        assertEquals(5.9604645e-8f, h(0x0001))
        assertEquals(Float.POSITIVE_INFINITY, h(0x7C00))
        assertEquals(Float.NEGATIVE_INFINITY, h(0xFC00))
        assertTrue(h(0x7E00).isNaN())
    }

    @Test fun lutMatchesFunction() {
        for (bits in listOf(0x0000, 0x0001, 0x3C00, 0x38CD, 0xC000, 0x7BFF)) {
            assertEquals(Half.toFloat(bits.toShort()), Half.LUT[bits])
        }
    }
}
```

- [ ] **Step 5: Убедиться, что тесты падают**

Run: `cd /Users/vvnovg/navigator/android && JAVA_HOME="$(brew --prefix openjdk@17)/libexec/openjdk.jdk/Contents/Home" ./gradlew :core:test`
Expected: FAIL при компиляции, `Unresolved reference: Geo` / `Half`.

- [ ] **Step 6: Реализовать `Geo.kt` и `Half.kt`**

`android/core/src/main/kotlin/io/visnav/core/Geo.kt`:
```kotlin
package io.visnav.core

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Геодезия; радиус Земли совпадает с vpr_bench.geo, чтобы расстояния на телефоне и на ПК были одинаковыми. */
object Geo {
    const val EARTH_RADIUS_M = 6_371_000.0

    fun haversineM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dp = p2 - p1
        val dl = Math.toRadians(lon2 - lon1)
        val s1 = sin(dp / 2)
        val s2 = sin(dl / 2)
        val a = s1 * s1 + cos(p1) * cos(p2) * s2 * s2
        return 2 * EARTH_RADIUS_M * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }
}
```

`android/core/src/main/kotlin/io/visnav/core/Half.kt`:
```kotlin
package io.visnav.core

/** IEEE 754 binary16 → float. Своя реализация: Float.float16ToFloat нет в Android-рантайме. */
object Half {
    fun toFloat(h: Short): Float {
        val bits = h.toInt() and 0xFFFF
        val sign = (bits ushr 15) shl 31
        val exp = (bits ushr 10) and 0x1F
        val mant = bits and 0x3FF
        val f = when {
            exp == 0 && mant == 0 -> sign
            exp == 0 -> {
                var e = -1
                var m = mant
                do { e++; m = m shl 1 } while (m and 0x400 == 0)
                sign or ((127 - 15 - e) shl 23) or ((m and 0x3FF) shl 13)
            }
            exp == 0x1F -> sign or (0xFF shl 23) or (mant shl 13)
            else -> sign or ((exp - 15 + 127) shl 23) or (mant shl 13)
        }
        return java.lang.Float.intBitsToFloat(f)
    }

    val LUT: FloatArray by lazy { FloatArray(65536) { toFloat(it.toShort()) } }
}
```

- [ ] **Step 7: Убедиться, что тесты проходят и приложение собирается**

Run: `cd /Users/vvnovg/navigator/android && JAVA_HOME="$(brew --prefix openjdk@17)/libexec/openjdk.jdk/Contents/Home" ./gradlew :core:test :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`, 6 тестов прошли, появился `app/build/outputs/apk/debug/app-debug.apk`.

- [ ] **Step 8: Commit**

```bash
cd /Users/vvnovg/navigator
git add .gitignore android/settings.gradle.kts android/build.gradle.kts android/gradle.properties android/gradle android/gradlew android/gradlew.bat android/core android/app
git commit -m "feat(android): project skeleton, core geodesy and float16 decoding

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 6: Парсер refpack на Kotlin

**Files:**
- Create: `android/core/src/main/kotlin/io/visnav/core/RefPack.kt`
- Create: `android/core/src/test/resources/refpack_fixture/refpack.bin`, `android/core/src/test/resources/refpack_fixture/refpack.json` (генерируются Python-кодом из Task 3)
- Test: `android/core/src/test/kotlin/io/visnav/core/RefPackTest.kt`

**Interfaces:**
- Consumes: `Half.toFloat`; `vpr_bench.refpack.write_refpack` (для генерации фикстуры).
- Produces:
  - `class RefPack(val count: Int, val dim: Int, val lats: DoubleArray, val lons: DoubleArray, val headings: FloatArray, val descriptors: ShortArray)` — дескрипторы хранятся как float16-биты, построчно.
  - `RefPack.parse(buf: ByteBuffer): RefPack` (бросает `IllegalArgumentException` на битых данных).
  - `RefPack.MAGIC: Int = 0x50524E56`
  - `@Serializable data class RefPackMeta(val format: String, val model: String, @SerialName("created_at") val createdAt: String, val count: Int, val dim: Int, @SerialName("input_h") val inputH: Int, @SerialName("input_w") val inputW: Int)`
  - `RefPackMeta.parse(json: String): RefPackMeta` (неизвестные поля игнорируются).

- [ ] **Step 1: Сгенерировать фикстуру эталонным Python-писателем**

```bash
cd /Users/vvnovg/navigator/research/vpr_bench
uv run python -c "
from pathlib import Path
import numpy as np
from vpr_bench.refpack import write_refpack
write_refpack(Path('../../android/core/src/test/resources/refpack_fixture'),
    [55.75, 55.7509, 55.7518], [37.6, 37.6, 37.6], [0.0, 90.0, 180.0],
    np.array([[1,0,0,0],[0,1,0,0],[0,0.6,0.8,0]], dtype=np.float32),
    {'model': 'fixture', 'created_at': '2026-09-27T00:00:00Z', 'input_h': 480, 'input_w': 640,
     'onnx_sha256': '0'*64, 'source': 'test'})
"
ls -l ../../android/core/src/test/resources/refpack_fixture
```
Expected: `refpack.bin` размером 100 байт (16 + 3·20 + 3·4·2) и `refpack.json`.

- [ ] **Step 2: Написать падающие тесты**

`android/core/src/test/kotlin/io/visnav/core/RefPackTest.kt`:
```kotlin
package io.visnav.core

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RefPackTest {
    private fun resource(name: String): ByteArray =
        requireNotNull(javaClass.getResourceAsStream("/refpack_fixture/$name")) { name }.readBytes()

    @Test fun parsesPythonWrittenFixture() {
        val rp = RefPack.parse(ByteBuffer.wrap(resource("refpack.bin")))
        assertEquals(3, rp.count)
        assertEquals(4, rp.dim)
        assertEquals(listOf(55.75, 55.7509, 55.7518), rp.lats.toList())
        assertEquals(listOf(37.6, 37.6, 37.6), rp.lons.toList())
        assertEquals(listOf(0f, 90f, 180f), rp.headings.toList())
        val row2 = (0 until 4).map { Half.toFloat(rp.descriptors[2 * 4 + it]) }
        assertEquals(0f, row2[0]); assertEquals(0.60009765625f, row2[1])
        assertEquals(0.7998046875f, row2[2]); assertEquals(0f, row2[3])
        assertEquals(1f, Half.toFloat(rp.descriptors[0]))
    }

    @Test fun parsesMeta() {
        val meta = RefPackMeta.parse(String(resource("refpack.json")))
        assertEquals("VNRP/1", meta.format)
        assertEquals("fixture", meta.model)
        assertEquals(480, meta.inputH); assertEquals(640, meta.inputW)
        assertEquals(3, meta.count); assertEquals(4, meta.dim)
    }

    @Test fun rejectsBadMagic() {
        val bytes = resource("refpack.bin").also { it[0] = 'X'.code.toByte() }
        assertFailsWith<IllegalArgumentException> { RefPack.parse(ByteBuffer.wrap(bytes)) }
    }

    @Test fun rejectsTruncatedFile() {
        val bytes = resource("refpack.bin").copyOf(90)
        assertFailsWith<IllegalArgumentException> { RefPack.parse(ByteBuffer.wrap(bytes)) }
    }

    @Test fun magicConstantIsVnrpLittleEndian() {
        val b = ByteBuffer.wrap("VNRP".toByteArray()).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(RefPack.MAGIC, b.int)
    }
}
```

- [ ] **Step 3: Убедиться, что тесты падают**

Run: `cd /Users/vvnovg/navigator/android && JAVA_HOME="$(brew --prefix openjdk@17)/libexec/openjdk.jdk/Contents/Home" ./gradlew :core:test`
Expected: FAIL при компиляции, `Unresolved reference: RefPack`.

- [ ] **Step 4: Реализовать `RefPack.kt`**

`android/core/src/main/kotlin/io/visnav/core/RefPack.kt`:
```kotlin
package io.visnav.core

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** refpack v1: заголовок 16 байт, затем lats f64, lons f64, headings f32, descriptors f16 (little-endian). */
class RefPack(
    val count: Int,
    val dim: Int,
    val lats: DoubleArray,
    val lons: DoubleArray,
    val headings: FloatArray,
    val descriptors: ShortArray,
) {
    companion object {
        const val MAGIC = 0x50524E56 // "VNRP" как little-endian int
        private const val HEADER_SIZE = 16

        fun parse(buf: ByteBuffer): RefPack {
            val b = buf.duplicate().order(ByteOrder.LITTLE_ENDIAN)
            val total = b.remaining().toLong()
            require(total >= HEADER_SIZE) { "refpack too short" }
            require(b.int == MAGIC) { "not a refpack (bad magic)" }
            val version = b.short.toInt() and 0xFFFF
            val dtype = b.short.toInt() and 0xFFFF
            require(version == 1 && dtype == 1) { "unsupported refpack version=$version dtype=$dtype" }
            val count = b.int
            val dim = b.int
            require(count >= 0 && dim > 0) { "bad header count=$count dim=$dim" }
            val expected = HEADER_SIZE + count.toLong() * 20 + count.toLong() * dim * 2
            require(total == expected) { "refpack size $total != expected $expected" }

            val lats = DoubleArray(count).also { b.asDoubleBuffer().get(it) }
            b.position(b.position() + 8 * count)
            val lons = DoubleArray(count).also { b.asDoubleBuffer().get(it) }
            b.position(b.position() + 8 * count)
            val headings = FloatArray(count).also { b.asFloatBuffer().get(it) }
            b.position(b.position() + 4 * count)
            val desc = ShortArray(count * dim).also { b.asShortBuffer().get(it) }
            return RefPack(count, dim, lats, lons, headings, desc)
        }
    }
}

@Serializable
data class RefPackMeta(
    val format: String,
    val model: String,
    @SerialName("created_at") val createdAt: String,
    val count: Int,
    val dim: Int,
    @SerialName("input_h") val inputH: Int,
    @SerialName("input_w") val inputW: Int,
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        fun parse(text: String): RefPackMeta = json.decodeFromString(serializer(), text)
    }
}
```

- [ ] **Step 5: Убедиться, что тесты проходят**

Run: `cd /Users/vvnovg/navigator/android && JAVA_HOME="$(brew --prefix openjdk@17)/libexec/openjdk.jdk/Contents/Home" ./gradlew :core:test`
Expected: `BUILD SUCCESSFUL`, 11 тестов.

- [ ] **Step 6: Commit**

```bash
cd /Users/vvnovg/navigator
git add android/core/src
git commit -m "feat(android): refpack v1 parser with Python-generated golden fixture

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 7: Поиск по базе с окном неопределённости (GeoIndex)

**Files:**
- Create: `android/core/src/main/kotlin/io/visnav/core/GeoIndex.kt`
- Test: `android/core/src/test/kotlin/io/visnav/core/GeoIndexTest.kt`

**Interfaces:**
- Consumes: `RefPack`, `Half.LUT`, `Geo.haversineM`.
- Produces:
  - `class GeoIndex(pack: RefPack)`
  - `data class Hit(val index: Int, val sim: Float)`
  - `GeoIndex.search(query: FloatArray, k: Int, centerLat: Double? = null, centerLon: Double? = null, radiusM: Double? = null): List<Hit>` — отсортировано по убыванию `sim`. Окно применяется, только если заданы все три параметра. `query.size` должен быть равен `pack.dim`, `k > 0`.

- [ ] **Step 1: Написать падающие тесты**

`android/core/src/test/kotlin/io/visnav/core/GeoIndexTest.kt`:
```kotlin
package io.visnav.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GeoIndexTest {
    private val one: Short = 0x3C00
    // Три эталона через ~100 м к северу, дескрипторы — единичные векторы.
    private val pack = RefPack(
        count = 3, dim = 3,
        lats = doubleArrayOf(55.7500, 55.7509, 55.7518),
        lons = doubleArrayOf(37.6, 37.6, 37.6),
        headings = floatArrayOf(0f, 0f, 0f),
        descriptors = shortArrayOf(one, 0, 0, 0, one, 0, 0, 0, one),
    )
    private val index = GeoIndex(pack)

    @Test fun ordersBySimilarity() {
        val hits = index.search(floatArrayOf(0f, 0.6f, 0.8f), k = 2)
        assertEquals(listOf(2, 1), hits.map { it.index })
        assertEquals(0.8f, hits[0].sim, 1e-6f)
    }

    @Test fun geoWindowExcludesFarCandidates() {
        val hits = index.search(floatArrayOf(0f, 0f, 1f), k = 3, centerLat = 55.75, centerLon = 37.6, radiusM = 50.0)
        assertEquals(listOf(0), hits.map { it.index })
    }

    @Test fun emptyWhenNothingInWindow() {
        assertTrue(index.search(floatArrayOf(1f, 0f, 0f), k = 3, centerLat = 0.0, centerLon = 0.0, radiusM = 10.0).isEmpty())
    }

    @Test fun kLargerThanCountReturnsAll() {
        assertEquals(3, index.search(floatArrayOf(1f, 1f, 1f), k = 10).size)
    }

    @Test fun rejectsWrongDimensionAndK() {
        assertFailsWith<IllegalArgumentException> { index.search(floatArrayOf(1f, 0f), k = 1) }
        assertFailsWith<IllegalArgumentException> { index.search(floatArrayOf(1f, 0f, 0f), k = 0) }
    }
}
```

- [ ] **Step 2: Убедиться, что тесты падают**

Run: `cd /Users/vvnovg/navigator/android && JAVA_HOME="$(brew --prefix openjdk@17)/libexec/openjdk.jdk/Contents/Home" ./gradlew :core:test`
Expected: FAIL при компиляции, `Unresolved reference: GeoIndex`.

- [ ] **Step 3: Реализовать `GeoIndex.kt`**

`android/core/src/main/kotlin/io/visnav/core/GeoIndex.kt`:
```kotlin
package io.visnav.core

import java.util.PriorityQueue

data class Hit(val index: Int, val sim: Float)

/**
 * Полный перебор эталонов внутри окна неопределённости. Дескрипторы L2-нормированы,
 * поэтому скалярное произведение — это косинусное сходство. Для базы одного района
 * (тысячи эталонов) перебора достаточно; ANN-индекс нужен при переходе на коридоры (M3).
 */
class GeoIndex(private val pack: RefPack) {
    fun search(
        query: FloatArray,
        k: Int,
        centerLat: Double? = null,
        centerLon: Double? = null,
        radiusM: Double? = null,
    ): List<Hit> {
        require(query.size == pack.dim) { "query dim ${query.size} != ${pack.dim}" }
        require(k > 0) { "k must be > 0" }
        val useWindow = centerLat != null && centerLon != null && radiusM != null
        val lut = Half.LUT
        val desc = pack.descriptors
        val d = pack.dim
        val heap = PriorityQueue<Hit>(k, compareBy { it.sim })
        for (i in 0 until pack.count) {
            if (useWindow && Geo.haversineM(centerLat!!, centerLon!!, pack.lats[i], pack.lons[i]) > radiusM!!) continue
            var s = 0f
            val base = i * d
            for (j in 0 until d) s += lut[desc[base + j].toInt() and 0xFFFF] * query[j]
            if (heap.size < k) {
                heap.add(Hit(i, s))
            } else if (s > heap.peek().sim) {
                heap.poll()
                heap.add(Hit(i, s))
            }
        }
        return heap.sortedByDescending { it.sim }
    }
}
```

- [ ] **Step 4: Убедиться, что тесты проходят**

Run: `cd /Users/vvnovg/navigator/android && JAVA_HOME="$(brew --prefix openjdk@17)/libexec/openjdk.jdk/Contents/Home" ./gradlew :core:test`
Expected: `BUILD SUCCESSFUL`, 16 тестов.

- [ ] **Step 5: Commit**

```bash
cd /Users/vvnovg/navigator
git add android/core/src
git commit -m "feat(android): geo-windowed brute-force descriptor search

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 8: Окно поиска, формат журнала, подготовка пикселей

**Files:**
- Create: `android/core/src/main/kotlin/io/visnav/core/Prior.kt`
- Create: `android/core/src/main/kotlin/io/visnav/core/FrameLog.kt`
- Create: `android/core/src/main/kotlin/io/visnav/core/Preprocess.kt`
- Create: `android/core/src/main/kotlin/io/visnav/core/Vectors.kt`
- Test: `android/core/src/test/kotlin/io/visnav/core/PriorTest.kt`, `FrameLogTest.kt`, `PreprocessTest.kt`

**Interfaces:**
- Produces:
  - `enum class PriorMode { GPS, VISUAL }`
  - `data class GpsFix(val lat: Double, val lon: Double, val accM: Float, val tMs: Long)`
  - `data class Fix(val lat: Double, val lon: Double, val sim: Float, val tMs: Long)`
  - `data class Prior(val lat: Double, val lon: Double, val radiusM: Double)`
  - `class PriorPolicy(val mode: PriorMode, val radiusM: Double = 500.0, val acceptSim: Float = 0.5f, val growthMps: Double = 30.0, val maxRadiusM: Double = 3000.0)` с методами `onGps(fix: GpsFix)`, `onVisualFix(fix: Fix)`, `prior(nowMs: Long): Prior?`. В режиме GPS центр — последний GPS. В режиме VISUAL — последняя принятая фиксация (`sim ≥ acceptSim`), радиус растёт на `growthMps` за каждую секунду её возраста до `maxRadiusM`; пока ни одной фиксации не принято, центр берётся из GPS.
  - JSON-классы журнала `GpsJson`, `PriorJson`, `HitJson`, `FixJson`, `LatencyJson`, `FrameRecord`, `SessionHeader` и `object LogJson { fun line(r: FrameRecord): String; fun line(h: SessionHeader): String }`.
  - `object Preprocess { fun argbToRgb(pixels: IntArray): ByteArray }`
  - `object Vectors { fun cosine(a: FloatArray, b: FloatArray): Float }`

- [ ] **Step 1: Написать падающие тесты**

`android/core/src/test/kotlin/io/visnav/core/PriorTest.kt`:
```kotlin
package io.visnav.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PriorTest {
    private val gps = GpsFix(55.75, 37.6, 5f, 1_000)

    @Test fun gpsModeFollowsGps() {
        val p = PriorPolicy(PriorMode.GPS)
        assertNull(p.prior(1_000))
        p.onGps(gps)
        p.onVisualFix(Fix(55.76, 37.61, 0.9f, 1_500))
        assertEquals(Prior(55.75, 37.6, 500.0), p.prior(2_000))
    }

    @Test fun visualModeBootstrapsFromGpsThenTracksAcceptedFixes() {
        val p = PriorPolicy(PriorMode.VISUAL)
        p.onGps(gps)
        assertEquals(Prior(55.75, 37.6, 500.0), p.prior(1_000))
        p.onVisualFix(Fix(55.76, 37.61, 0.3f, 1_500)) // ниже acceptSim — игнорируется
        assertEquals(Prior(55.75, 37.6, 500.0), p.prior(1_500))
        p.onVisualFix(Fix(55.76, 37.61, 0.9f, 2_000))
        p.onGps(GpsFix(10.0, 10.0, 5f, 2_500)) // после первой фиксации GPS больше не используется
        assertEquals(Prior(55.76, 37.61, 500.0), p.prior(2_000))
    }

    @Test fun visualRadiusGrowsWithFixAgeAndIsCapped() {
        val p = PriorPolicy(PriorMode.VISUAL)
        p.onVisualFix(Fix(55.76, 37.61, 0.9f, 0))
        assertEquals(500.0 + 30.0 * 10, p.prior(10_000)!!.radiusM, 1e-9)
        assertEquals(3000.0, p.prior(1_000_000)!!.radiusM, 1e-9)
    }
}
```

`android/core/src/test/kotlin/io/visnav/core/FrameLogTest.kt`:
```kotlin
package io.visnav.core

import kotlin.test.Test
import kotlin.test.assertEquals

class FrameLogTest {
    // Та же строка, что в research/vpr_bench/tests/test_fieldlog.py (SAMPLE_LINE) — контракт с Python.
    private val sample = "{\"v\":1,\"type\":\"frame\",\"t_ms\":1700000000123,\"mode\":\"gps\"," +
        "\"gps\":{\"lat\":55.75,\"lon\":37.6,\"acc_m\":4.5,\"t_ms\":1700000000000}," +
        "\"prior\":{\"lat\":55.75,\"lon\":37.6,\"radius_m\":500.0}," +
        "\"top\":[{\"i\":1,\"sim\":0.75,\"lat\":55.7509,\"lon\":37.6}]," +
        "\"fix\":{\"lat\":55.7509,\"lon\":37.6,\"sim\":0.75}," +
        "\"lat_ms\":{\"pre\":10.5,\"inf\":80.25,\"search\":2.0}}"

    @Test fun frameLineMatchesPythonContract() {
        val r = FrameRecord(
            tMs = 1_700_000_000_123, mode = "gps",
            gps = GpsJson(55.75, 37.6, 4.5f, 1_700_000_000_000),
            prior = PriorJson(55.75, 37.6, 500.0),
            top = listOf(HitJson(1, 0.75f, 55.7509, 37.6)),
            fix = FixJson(55.7509, 37.6, 0.75f),
            latMs = LatencyJson(10.5, 80.25, 2.0),
        )
        assertEquals(sample, LogJson.line(r))
    }

    @Test fun nullsAreWrittenExplicitly() {
        val r = FrameRecord(tMs = 1, mode = "visual", gps = null, prior = null, top = emptyList(), fix = null,
            latMs = LatencyJson(0.0, 0.0, 0.0))
        assertEquals(
            "{\"v\":1,\"type\":\"frame\",\"t_ms\":1,\"mode\":\"visual\",\"gps\":null,\"prior\":null,\"top\":[]," +
                "\"fix\":null,\"lat_ms\":{\"pre\":0.0,\"inf\":0.0,\"search\":0.0}}",
            LogJson.line(r),
        )
    }

    @Test fun sessionHeaderLine() {
        val h = SessionHeader(model = "m", refpackCreatedAt = "2026-09-27T00:00:00Z", device = "d",
            startedMs = 5, mode = "gps")
        assertEquals(
            "{\"v\":1,\"type\":\"session\",\"model\":\"m\",\"refpack_created_at\":\"2026-09-27T00:00:00Z\"," +
                "\"device\":\"d\",\"started_ms\":5,\"mode\":\"gps\"}",
            LogJson.line(h),
        )
    }
}
```

`android/core/src/test/kotlin/io/visnav/core/PreprocessTest.kt`:
```kotlin
package io.visnav.core

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class PreprocessTest {
    @Test fun argbToRgbDropsAlphaKeepsOrder() {
        val px = intArrayOf(0xFF102030.toInt(), 0x80FF0001.toInt())
        assertContentEquals(byteArrayOf(0x10, 0x20, 0x30, 0xFF.toByte(), 0x00, 0x01), Preprocess.argbToRgb(px))
    }

    @Test fun cosine() {
        assertEquals(1f, Vectors.cosine(floatArrayOf(1f, 2f), floatArrayOf(2f, 4f)), 1e-6f)
        assertEquals(0f, Vectors.cosine(floatArrayOf(1f, 0f), floatArrayOf(0f, 3f)), 1e-6f)
    }
}
```

- [ ] **Step 2: Убедиться, что тесты падают**

Run: `cd /Users/vvnovg/navigator/android && JAVA_HOME="$(brew --prefix openjdk@17)/libexec/openjdk.jdk/Contents/Home" ./gradlew :core:test`
Expected: FAIL при компиляции (`Unresolved reference: PriorPolicy`, `FrameRecord`, `Preprocess`).

- [ ] **Step 3: Реализовать**

`android/core/src/main/kotlin/io/visnav/core/Prior.kt`:
```kotlin
package io.visnav.core

import kotlin.math.max
import kotlin.math.min

enum class PriorMode { GPS, VISUAL }

data class GpsFix(val lat: Double, val lon: Double, val accM: Float, val tMs: Long)
data class Fix(val lat: Double, val lon: Double, val sim: Float, val tMs: Long)
data class Prior(val lat: Double, val lon: Double, val radiusM: Double)

/**
 * Окно поиска без фильтра (M1). GPS — как в режиме prior-500m бенчмарка M0; по нему считается
 * критерий M1. VISUAL имитирует пропажу GPS: после первой уверенной фиксации центр окна —
 * последняя принятая фиксация, радиус растёт с её возрастом (машина могла уехать).
 */
class PriorPolicy(
    val mode: PriorMode,
    val radiusM: Double = 500.0,
    val acceptSim: Float = 0.5f,
    val growthMps: Double = 30.0,
    val maxRadiusM: Double = 3000.0,
) {
    private var lastGps: GpsFix? = null
    private var lastAccepted: Fix? = null

    fun onGps(fix: GpsFix) { lastGps = fix }

    fun onVisualFix(fix: Fix) { if (fix.sim >= acceptSim) lastAccepted = fix }

    fun prior(nowMs: Long): Prior? {
        val gps = lastGps
        return when (mode) {
            PriorMode.GPS -> gps?.let { Prior(it.lat, it.lon, radiusM) }
            PriorMode.VISUAL -> {
                val fix = lastAccepted
                if (fix != null) {
                    val ageS = max(0L, nowMs - fix.tMs) / 1000.0
                    Prior(fix.lat, fix.lon, min(maxRadiusM, radiusM + growthMps * ageS))
                } else {
                    gps?.let { Prior(it.lat, it.lon, radiusM) }
                }
            }
        }
    }
}
```

`android/core/src/main/kotlin/io/visnav/core/FrameLog.kt`:
```kotlin
package io.visnav.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// Формат журнала — контракт с research/vpr_bench/src/vpr_bench/fieldlog.py. Порядок и имена полей не менять.
@Serializable data class GpsJson(
    val lat: Double, val lon: Double, @SerialName("acc_m") val accM: Float, @SerialName("t_ms") val tMs: Long,
)
@Serializable data class PriorJson(val lat: Double, val lon: Double, @SerialName("radius_m") val radiusM: Double)
@Serializable data class HitJson(val i: Int, val sim: Float, val lat: Double, val lon: Double)
@Serializable data class FixJson(val lat: Double, val lon: Double, val sim: Float)
@Serializable data class LatencyJson(val pre: Double, val inf: Double, val search: Double)

@Serializable data class FrameRecord(
    val v: Int = 1,
    val type: String = "frame",
    @SerialName("t_ms") val tMs: Long,
    val mode: String,
    val gps: GpsJson?,
    val prior: PriorJson?,
    val top: List<HitJson>,
    val fix: FixJson?,
    @SerialName("lat_ms") val latMs: LatencyJson,
)

@Serializable data class SessionHeader(
    val v: Int = 1,
    val type: String = "session",
    val model: String,
    @SerialName("refpack_created_at") val refpackCreatedAt: String,
    val device: String,
    @SerialName("started_ms") val startedMs: Long,
    val mode: String,
)

object LogJson {
    private val json = Json { encodeDefaults = true }
    fun line(r: FrameRecord): String = json.encodeToString(FrameRecord.serializer(), r)
    fun line(h: SessionHeader): String = json.encodeToString(SessionHeader.serializer(), h)
}
```

`android/core/src/main/kotlin/io/visnav/core/Preprocess.kt`:
```kotlin
package io.visnav.core

/** Пиксели Bitmap (ARGB_8888 как Int) → плотный RGB uint8, вход ONNX-модели "image" [1, H, W, 3]. */
object Preprocess {
    fun argbToRgb(pixels: IntArray): ByteArray {
        val out = ByteArray(pixels.size * 3)
        for (i in pixels.indices) {
            val p = pixels[i]
            out[3 * i] = (p shr 16 and 0xFF).toByte()
            out[3 * i + 1] = (p shr 8 and 0xFF).toByte()
            out[3 * i + 2] = (p and 0xFF).toByte()
        }
        return out
    }
}
```

`android/core/src/main/kotlin/io/visnav/core/Vectors.kt`:
```kotlin
package io.visnav.core

import kotlin.math.sqrt

object Vectors {
    fun cosine(a: FloatArray, b: FloatArray): Float {
        require(a.size == b.size) { "size mismatch ${a.size} != ${b.size}" }
        var dot = 0.0; var na = 0.0; var nb = 0.0
        for (i in a.indices) { dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i] }
        return (dot / (sqrt(na) * sqrt(nb))).toFloat()
    }
}
```

- [ ] **Step 4: Убедиться, что тесты проходят**

Run: `cd /Users/vvnovg/navigator/android && JAVA_HOME="$(brew --prefix openjdk@17)/libexec/openjdk.jdk/Contents/Home" ./gradlew :core:test`
Expected: `BUILD SUCCESSFUL`, 24 теста.

Если `frameLineMatchesPythonContract` падает из-за формата чисел, вывод kotlinx.serialization подгонять нельзя. Нужно выяснить расхождение и сообщить NEEDS_CONTEXT: строку-образец в Python (Task 4) и Kotlin можно менять только вместе.

- [ ] **Step 5: Commit**

```bash
cd /Users/vvnovg/navigator
git add android/core/src
git commit -m "feat(android): search-window policy, JSONL log format, pixel packing

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 9: Конвейер локализации и запись журнала

**Files:**
- Create: `android/core/src/main/kotlin/io/visnav/core/LocalizationPipeline.kt`
- Create: `android/core/src/main/kotlin/io/visnav/core/SessionLogger.kt`
- Test: `android/core/src/test/kotlin/io/visnav/core/LocalizationPipelineTest.kt`, `SessionLoggerTest.kt`

**Interfaces:**
- Consumes: `RefPack`, `GeoIndex`, `Hit`, `PriorPolicy`, `GpsFix`, `Fix`, JSON-классы и `LogJson` из Task 8.
- Produces:
  - `interface Embedder { val name: String; fun embed(rgb: ByteArray, width: Int, height: Int): FloatArray }`
  - `class LocalizationPipeline(pack: RefPack, embedder: Embedder, priorPolicy: PriorPolicy, k: Int = 5, nanoTime: () -> Long = System::nanoTime)` с методом `process(tMs: Long, rgb: ByteArray, width: Int, height: Int, gps: GpsFix?, preMs: Double): FrameRecord`.
  - `class SessionLogger(file: File) : Closeable` с методами `header(h: SessionHeader)`, `frame(r: FrameRecord)` (сброс на диск каждые 10 кадров) и `close()`.

- [ ] **Step 1: Написать падающие тесты**

`android/core/src/test/kotlin/io/visnav/core/LocalizationPipelineTest.kt`:
```kotlin
package io.visnav.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class LocalizationPipelineTest {
    private val one: Short = 0x3C00
    private val pack = RefPack(
        3, 3, doubleArrayOf(55.7500, 55.7509, 55.7518), doubleArrayOf(37.6, 37.6, 37.6),
        floatArrayOf(0f, 0f, 0f), shortArrayOf(one, 0, 0, 0, one, 0, 0, 0, one),
    )

    private class FakeEmbedder(val out: FloatArray) : Embedder {
        override val name = "fake"
        var calls = 0
        override fun embed(rgb: ByteArray, width: Int, height: Int): FloatArray { calls++; return out }
    }

    private fun clock(vararg ns: Long): () -> Long { var i = 0; return { ns[i++] } }

    @Test fun gpsModeRecordsFixPriorAndLatency() {
        val emb = FakeEmbedder(floatArrayOf(0f, 1f, 0f))
        val p = LocalizationPipeline(pack, emb, PriorPolicy(PriorMode.GPS), k = 2,
            nanoTime = clock(0, 40_000_000, 43_000_000))
        val r = p.process(1_000, ByteArray(12), 2, 2, GpsFix(55.7509, 37.6, 4f, 990), preMs = 7.0)
        assertEquals("gps", r.mode)
        assertEquals(PriorJson(55.7509, 37.6, 500.0), r.prior)
        assertEquals(1, r.top[0].i) // окно 500 м вокруг эталона 1 содержит все три; у 0 и 2 сходство 0
        assertEquals(2, r.top.size)
        assertEquals(FixJson(55.7509, 37.6, 1f), r.fix)
        assertEquals(LatencyJson(7.0, 40.0, 3.0), r.latMs)
        assertEquals(1, emb.calls)
    }

    @Test fun noGpsNoWindowSearchesEverything() {
        val p = LocalizationPipeline(pack, FakeEmbedder(floatArrayOf(0f, 0f, 1f)), PriorPolicy(PriorMode.GPS),
            nanoTime = clock(0, 0, 0))
        val r = p.process(1_000, ByteArray(12), 2, 2, gps = null, preMs = 0.0)
        assertNull(r.prior)
        assertNull(r.gps)
        assertEquals(2, r.top.first().i)
    }

    @Test fun visualModeUsesPreviousFixAsWindowCenter() {
        val p = LocalizationPipeline(pack, FakeEmbedder(floatArrayOf(0f, 0f, 1f)), PriorPolicy(PriorMode.VISUAL),
            nanoTime = clock(0, 0, 0, 0, 0, 0))
        p.process(1_000, ByteArray(12), 2, 2, GpsFix(55.7518, 37.6, 4f, 990), 0.0)
        val second = p.process(2_000, ByteArray(12), 2, 2, gps = null, preMs = 0.0)
        val prior = assertNotNull(second.prior)
        assertEquals(55.7518, prior.lat)
        assertEquals(530.0, prior.radiusM, 1e-9) // 500 м + 30 м/с × 1 с
    }
}
```

`android/core/src/test/kotlin/io/visnav/core/SessionLoggerTest.kt`:
```kotlin
package io.visnav.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

class SessionLoggerTest {
    @Test fun writesHeaderThenFramesAsJsonLines() {
        val f = File.createTempFile("session", ".jsonl")
        SessionLogger(f).use { log ->
            log.header(SessionHeader(model = "m", refpackCreatedAt = "c", device = "d", startedMs = 1, mode = "gps"))
            repeat(12) {
                log.frame(FrameRecord(tMs = it.toLong(), mode = "gps", gps = null, prior = null, top = emptyList(),
                    fix = null, latMs = LatencyJson(0.0, 0.0, 0.0)))
            }
        }
        val lines = f.readLines()
        assertEquals(13, lines.size)
        assertEquals(true, lines[0].contains("\"type\":\"session\""))
        assertEquals(true, lines[12].contains("\"t_ms\":11"))
    }
}
```

- [ ] **Step 2: Убедиться, что тесты падают**

Run: `cd /Users/vvnovg/navigator/android && JAVA_HOME="$(brew --prefix openjdk@17)/libexec/openjdk.jdk/Contents/Home" ./gradlew :core:test`
Expected: FAIL при компиляции (`Unresolved reference: LocalizationPipeline`, `Embedder`, `SessionLogger`).

- [ ] **Step 3: Реализовать**

`android/core/src/main/kotlin/io/visnav/core/LocalizationPipeline.kt`:
```kotlin
package io.visnav.core

interface Embedder {
    val name: String
    /** rgb — плотный RGB uint8 размера width × height (уже под вход модели). Возвращает L2-нормированный дескриптор. */
    fun embed(rgb: ByteArray, width: Int, height: Int): FloatArray
}

/** Один кадр → дескриптор → окно поиска → top-k эталонов → запись журнала. Без фильтра (M1). */
class LocalizationPipeline(
    private val pack: RefPack,
    private val embedder: Embedder,
    private val priorPolicy: PriorPolicy,
    private val k: Int = 5,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private val index = GeoIndex(pack)

    fun process(tMs: Long, rgb: ByteArray, width: Int, height: Int, gps: GpsFix?, preMs: Double): FrameRecord {
        if (gps != null) priorPolicy.onGps(gps)
        val t0 = nanoTime()
        val desc = embedder.embed(rgb, width, height)
        val t1 = nanoTime()
        val prior = priorPolicy.prior(tMs)
        val hits = index.search(desc, k, prior?.lat, prior?.lon, prior?.radiusM)
        val t2 = nanoTime()
        val best = hits.firstOrNull()
        val fix = best?.let { Fix(pack.lats[it.index], pack.lons[it.index], it.sim, tMs) }
        if (fix != null) priorPolicy.onVisualFix(fix)
        return FrameRecord(
            tMs = tMs,
            mode = priorPolicy.mode.name.lowercase(),
            gps = gps?.let { GpsJson(it.lat, it.lon, it.accM, it.tMs) },
            prior = prior?.let { PriorJson(it.lat, it.lon, it.radiusM) },
            top = hits.map { HitJson(it.index, it.sim, pack.lats[it.index], pack.lons[it.index]) },
            fix = fix?.let { FixJson(it.lat, it.lon, it.sim) },
            latMs = LatencyJson(preMs, (t1 - t0) / 1e6, (t2 - t1) / 1e6),
        )
    }
}
```

`android/core/src/main/kotlin/io/visnav/core/SessionLogger.kt`:
```kotlin
package io.visnav.core

import java.io.Closeable
import java.io.File

/** JSONL-журнал сессии: заголовок, затем по строке на кадр. Сбрасывается на диск каждые 10 кадров и при закрытии. */
class SessionLogger(file: File) : Closeable {
    private val writer = file.bufferedWriter()
    private var frames = 0

    fun header(h: SessionHeader) {
        writer.write(LogJson.line(h)); writer.newLine(); writer.flush()
    }

    fun frame(r: FrameRecord) {
        writer.write(LogJson.line(r)); writer.newLine()
        if (++frames % 10 == 0) writer.flush()
    }

    override fun close() {
        writer.flush(); writer.close()
    }
}
```

- [ ] **Step 4: Убедиться, что тесты проходят**

Run: `cd /Users/vvnovg/navigator/android && JAVA_HOME="$(brew --prefix openjdk@17)/libexec/openjdk.jdk/Contents/Home" ./gradlew :core:test`
Expected: `BUILD SUCCESSFUL`, 28 тестов.

- [ ] **Step 5: Commit**

```bash
cd /Users/vvnovg/navigator
git add android/core/src
git commit -m "feat(android): localization pipeline and JSONL session logger

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 10: Приложение — камера, модель, GPS, экран

**Files:**
- Create: `android/app/src/main/kotlin/io/visnav/app/OrtEmbedder.kt`
- Create: `android/app/src/main/kotlin/io/visnav/app/Bundle.kt`
- Create: `android/app/src/main/kotlin/io/visnav/app/FrameAnalyzer.kt`
- Create: `android/app/src/main/kotlin/io/visnav/app/GpsSource.kt`
- Create: `android/app/src/main/kotlin/io/visnav/app/ParityCheck.kt`
- Create: `android/app/src/main/kotlin/io/visnav/app/M1Controller.kt`
- Create: `android/app/src/main/kotlin/io/visnav/app/M1Screen.kt`
- Modify: `android/app/src/main/kotlin/io/visnav/app/MainActivity.kt` (заменить целиком)

**Interfaces:**
- Consumes: всё из `:core`.
- Produces: APK. Приложение читает `<externalFiles>/refpack/{refpack.bin, refpack.json, model.onnx}` и, если есть, `<externalFiles>/refpack/parity/{input.png, expected.f32}`. Журналы пишутся в `<externalFiles>/logs/session-<startedMs>-<mode>.jsonl`, проверка совпадения — в `<externalFiles>/logs/parity.json`. `<externalFiles>` = `/sdcard/Android/data/io.visnav.app/files`.

Эта часть держится на Android API и проверяется сборкой и ручным запуском на телефоне (Task 11). Вся логика, которую можно проверить тестами, уже в `:core`.

- [ ] **Step 1: `OrtEmbedder.kt`**

```kotlin
package io.visnav.app

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import io.visnav.core.Embedder
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** ONNX-модель с контрактом M1: "image" uint8 [1,H,W,3] RGB → "descriptor" float32 [1,D]. */
class OrtEmbedder(modelFile: File, override val name: String) : Embedder, AutoCloseable {
    private val env = OrtEnvironment.getEnvironment()
    private val session: OrtSession = env.createSession(
        modelFile.absolutePath,
        OrtSession.SessionOptions().apply { setIntraOpNumThreads(4) },
    )
    val inputH: Int
    val inputW: Int

    init {
        val shape = (session.inputInfo.getValue("image").info as TensorInfo).shape
        inputH = shape[1].toInt()
        inputW = shape[2].toInt()
    }

    override fun embed(rgb: ByteArray, width: Int, height: Int): FloatArray {
        require(width == inputW && height == inputH) { "expected ${inputW}x$inputH, got ${width}x$height" }
        val buf = ByteBuffer.allocateDirect(rgb.size).order(ByteOrder.nativeOrder())
        buf.put(rgb).rewind()
        OnnxTensor.createTensor(env, buf, longArrayOf(1, height.toLong(), width.toLong(), 3), OnnxJavaType.UINT8)
            .use { tensor ->
                session.run(mapOf("image" to tensor)).use { result ->
                    @Suppress("UNCHECKED_CAST")
                    return (result[0].value as Array<FloatArray>)[0]
                }
            }
    }

    override fun close() = session.close()
}
```

- [ ] **Step 2: `Bundle.kt` — загрузка базы и модели с диска**

```kotlin
package io.visnav.app

import io.visnav.core.RefPack
import io.visnav.core.RefPackMeta
import java.io.File
import java.nio.ByteBuffer

class LoadedBundle(val pack: RefPack, val meta: RefPackMeta, val embedder: OrtEmbedder)

object BundleLoader {
    /** Бросает IllegalStateException с понятным текстом, если файлы не положены через adb push. */
    fun load(dir: File): LoadedBundle {
        val bin = File(dir, "refpack.bin")
        val json = File(dir, "refpack.json")
        val model = File(dir, "model.onnx")
        for (f in listOf(bin, json, model)) check(f.isFile) { "нет файла ${f.absolutePath}" }
        val meta = RefPackMeta.parse(json.readText())
        val pack = RefPack.parse(ByteBuffer.wrap(bin.readBytes()))
        val embedder = OrtEmbedder(model, meta.model)
        check(embedder.inputH == meta.inputH && embedder.inputW == meta.inputW) {
            "модель ${embedder.inputW}x${embedder.inputH} не совпадает с refpack ${meta.inputW}x${meta.inputH}"
        }
        return LoadedBundle(pack, meta, embedder)
    }
}
```

- [ ] **Step 3: `FrameAnalyzer.kt` — кадр камеры → RGB под вход модели**

```kotlin
package io.visnav.app

import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import io.visnav.core.Preprocess

/** Пропускает кадры чаще intervalMs; остальные поворачивает, уменьшает до входа модели и отдаёт в onFrame. */
class FrameAnalyzer(
    private val intervalMs: Long,
    private val inputW: Int,
    private val inputH: Int,
) : ImageAnalysis.Analyzer {
    @Volatile var onFrame: ((tMs: Long, rgb: ByteArray, preMs: Double) -> Unit)? = null
    private var lastMs = 0L

    override fun analyze(image: ImageProxy) {
        val handler = onFrame
        val now = System.currentTimeMillis()
        if (handler == null || now - lastMs < intervalMs) { image.close(); return }
        lastMs = now
        val t0 = System.nanoTime()
        val rgb = try {
            val bmp = image.toBitmap()
            val rot = image.imageInfo.rotationDegrees
            val upright = if (rot == 0) bmp else
                Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(rot.toFloat()) }, true)
            val scaled = Bitmap.createScaledBitmap(upright, inputW, inputH, true)
            val px = IntArray(inputW * inputH)
            scaled.getPixels(px, 0, inputW, 0, 0, inputW, inputH)
            Preprocess.argbToRgb(px)
        } finally {
            image.close()
        }
        handler(now, rgb, (System.nanoTime() - t0) / 1e6)
    }
}
```

- [ ] **Step 4: `GpsSource.kt` — GPS без Google Play Services**

```kotlin
package io.visnav.app

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import io.visnav.core.GpsFix

class GpsSource(context: Context) : LocationListener {
    private val lm = context.getSystemService(LocationManager::class.java)
    @Volatile var latest: GpsFix? = null
        private set

    @SuppressLint("MissingPermission") // разрешение проверяет MainActivity до start()
    fun start() = lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this, Looper.getMainLooper())

    fun stop() = lm.removeUpdates(this)

    /** Последний GPS не старше maxAgeMs, иначе null. */
    fun fresh(nowMs: Long, maxAgeMs: Long = 3000): GpsFix? = latest?.takeIf { nowMs - it.tMs <= maxAgeMs }

    override fun onLocationChanged(l: Location) {
        latest = GpsFix(l.latitude, l.longitude, l.accuracy, l.time)
    }

    // На API 29 эти методы ещё абстрактные — реализуем явно.
    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
    override fun onProviderEnabled(provider: String) = Unit
    override fun onProviderDisabled(provider: String) = Unit
}
```

- [ ] **Step 5: `ParityCheck.kt` — проверка, что модель на телефоне даёт то же, что на ПК**

```kotlin
package io.visnav.app

import android.graphics.BitmapFactory
import io.visnav.core.Preprocess
import io.visnav.core.Vectors
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

object ParityCheck {
    /** Если в dir/parity лежат input.png и expected.f32 (vpr-m1 make-parity), считает косинус и пишет out. */
    fun runIfPresent(dir: File, embedder: OrtEmbedder, out: File): Float? {
        val png = File(dir, "parity/input.png")
        val exp = File(dir, "parity/expected.f32")
        if (!png.isFile || !exp.isFile) return null
        val bmp = BitmapFactory.decodeFile(png.absolutePath)
        check(bmp.width == embedder.inputW && bmp.height == embedder.inputH) { "parity image size mismatch" }
        val px = IntArray(bmp.width * bmp.height)
        bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        val got = embedder.embed(Preprocess.argbToRgb(px), bmp.width, bmp.height)
        val fb = ByteBuffer.wrap(exp.readBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        val expected = FloatArray(fb.remaining()).also { fb.get(it) }
        val cos = Vectors.cosine(got, expected)
        out.parentFile?.mkdirs()
        out.writeText("{\"cosine\":$cos,\"dim\":${got.size}}\n")
        return cos
    }
}
```

- [ ] **Step 6: `M1Controller.kt` — связывает камеру, GPS, конвейер и журнал**

```kotlin
package io.visnav.app

import android.content.Context
import android.os.Build
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import io.visnav.core.Geo
import io.visnav.core.LocalizationPipeline
import io.visnav.core.PriorMode
import io.visnav.core.PriorPolicy
import io.visnav.core.SessionHeader
import io.visnav.core.SessionLogger
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

data class UiState(
    val status: String = "Загрузка базы…",
    val loaded: Boolean = false,
    val running: Boolean = false,
    val mode: PriorMode = PriorMode.GPS,
    val frames: Int = 0,
    val lastSim: Float? = null,
    val lastErrM: Double? = null,
    val lastInfMs: Double? = null,
    val gpsAccM: Float? = null,
)

class M1Controller(private val context: Context, private val lifecycleOwner: LifecycleOwner) {
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    private val filesDir = requireNotNull(context.getExternalFilesDir(null))
    private val dataDir = File(filesDir, "refpack")
    private val logDir = File(filesDir, "logs")
    private val executor = Executors.newSingleThreadExecutor()
    private val gps = GpsSource(context)
    private val analyzer = AtomicReference<FrameAnalyzer?>(null)
    private var bundle: LoadedBundle? = null
    private var logger: SessionLogger? = null

    init {
        executor.execute {
            try {
                val b = BundleLoader.load(dataDir)
                bundle = b
                analyzer.set(FrameAnalyzer(intervalMs = 500, inputW = b.meta.inputW, inputH = b.meta.inputH))
                val parity = ParityCheck.runIfPresent(dataDir, b.embedder, File(logDir, "parity.json"))
                val parityText = parity?.let { " · parity cos=%.4f".format(it) } ?: ""
                _state.update {
                    it.copy(loaded = true, status = "База: ${b.pack.count} эталонов, модель ${b.meta.model}$parityText")
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(status = "Нет базы: ${e.message}. Скопируйте файлы: adb push <bundle>/. " +
                        "/sdcard/Android/data/io.visnav.app/files/refpack/")
                }
            }
        }
    }

    fun setMode(mode: PriorMode) { if (!_state.value.running) _state.update { it.copy(mode = mode) } }

    fun bindCamera(previewView: PreviewView) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            val analysis = ImageAnalysis.Builder()
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setResolutionSelector(
                    ResolutionSelector.Builder().setResolutionStrategy(
                        ResolutionStrategy(Size(1280, 720), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)
                    ).build()
                )
                .build()
            analysis.setAnalyzer(executor) { image -> analyzer.get()?.analyze(image) ?: image.close() }
            provider.unbindAll()
            provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
        }, ContextCompat.getMainExecutor(context))
    }

    fun start() {
        val b = bundle ?: return
        val frameAnalyzer = analyzer.get() ?: return
        val mode = _state.value.mode
        val pipeline = LocalizationPipeline(b.pack, b.embedder, PriorPolicy(mode))
        logDir.mkdirs()
        val startedMs = System.currentTimeMillis()
        val log = SessionLogger(File(logDir, "session-$startedMs-${mode.name.lowercase()}.jsonl"))
        log.header(SessionHeader(
            model = b.meta.model, refpackCreatedAt = b.meta.createdAt,
            device = "${Build.MANUFACTURER} ${Build.MODEL}", startedMs = startedMs, mode = mode.name.lowercase(),
        ))
        logger = log
        gps.start()
        frameAnalyzer.onFrame = { tMs, rgb, preMs ->
            val fix = gps.fresh(tMs)
            val rec = pipeline.process(tMs, rgb, b.meta.inputW, b.meta.inputH, fix, preMs)
            log.frame(rec)
            val err = if (fix != null && rec.fix != null) Geo.haversineM(fix.lat, fix.lon, rec.fix!!.lat, rec.fix!!.lon) else null
            _state.update {
                it.copy(frames = it.frames + 1, lastSim = rec.fix?.sim, lastErrM = err,
                    lastInfMs = rec.latMs.inf, gpsAccM = fix?.accM)
            }
        }
        _state.update { it.copy(running = true, frames = 0, status = "Запись: ${mode.name}") }
    }

    fun stop() {
        analyzer.get()?.onFrame = null
        gps.stop()
        val log = logger
        logger = null
        // Закрываем на потоке анализа — после кадра, который, возможно, ещё обрабатывается.
        executor.execute { log?.close() }
        _state.update { it.copy(running = false, status = "Остановлено, кадров: ${it.frames}") }
    }
}
```

- [ ] **Step 7: `M1Screen.kt` и `MainActivity.kt`**

`android/app/src/main/kotlin/io/visnav/app/M1Screen.kt`:
```kotlin
package io.visnav.app

import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.visnav.core.PriorMode

@Composable
fun M1Screen(controller: M1Controller, permissionsGranted: Boolean) {
    val s by controller.state.collectAsState()
    Row(Modifier.fillMaxSize()) {
        if (permissionsGranted) {
            AndroidView(
                factory = { ctx -> PreviewView(ctx).also { controller.bindCamera(it) } },
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
        } else {
            Text("Нужны разрешения на камеру и геопозицию", Modifier.weight(1f).padding(16.dp))
        }
        Column(Modifier.width(280.dp).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(s.status)
            Text("Кадров: ${s.frames}")
            Text("Сходство: ${s.lastSim?.let { "%.3f".format(it) } ?: "—"}")
            Text("Ошибка к GPS: ${s.lastErrM?.let { "%.0f м".format(it) } ?: "—"}")
            Text("Инференс: ${s.lastInfMs?.let { "%.0f мс".format(it) } ?: "—"}")
            Text("Точность GPS: ${s.gpsAccM?.let { "%.0f м".format(it) } ?: "нет сигнала"}")
            OutlinedButton(
                onClick = { controller.setMode(if (s.mode == PriorMode.GPS) PriorMode.VISUAL else PriorMode.GPS) },
                enabled = !s.running,
            ) { Text("Режим: ${if (s.mode == PriorMode.GPS) "окно по GPS" else "визуальное слежение"}") }
            Button(
                onClick = { if (s.running) controller.stop() else controller.start() },
                enabled = s.loaded && permissionsGranted,
            ) { Text(if (s.running) "Стоп" else "Старт") }
        }
    }
}
```

`android/app/src/main/kotlin/io/visnav/app/MainActivity.kt` (заменить целиком):
```kotlin
package io.visnav.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {
    private val permissions = arrayOf(
        Manifest.permission.CAMERA,
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
    )
    private val granted = mutableStateOf(false)
    private val request = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        granted.value = result[Manifest.permission.CAMERA] == true &&
            result[Manifest.permission.ACCESS_FINE_LOCATION] == true
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        granted.value = listOf(Manifest.permission.CAMERA, Manifest.permission.ACCESS_FINE_LOCATION).all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
        if (!granted.value) request.launch(permissions)
        val controller = M1Controller(applicationContext, this)
        setContent { MaterialTheme { M1Screen(controller, granted.value) } }
    }
}
```

- [ ] **Step 8: Собрать и прогнать lint**

Run: `cd /Users/vvnovg/navigator/android && JAVA_HOME="$(brew --prefix openjdk@17)/libexec/openjdk.jdk/Contents/Home" ./gradlew :core:test :app:assembleDebug :app:lintDebug`
Expected: `BUILD SUCCESSFUL`. Lint не должен выдавать ошибок (errors); предупреждения перечислить в отчёте. Если API в CameraX 1.4.1 или ORT 1.20 отличается от кода выше (например, `setSurfaceProvider`), поправить по документации этой версии и описать правку в отчёте.

- [ ] **Step 9: Commit**

```bash
cd /Users/vvnovg/navigator
git add android/app/src
git commit -m "feat(android): M1 app — CameraX analysis, ONNX Runtime embedder, GPS, session logging UI

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 11: Проверка на телефоне и полевой тест (выполняет владелец вместе с Claude)

**Files:**
- Create: `docs/research/m1-field-test.md`

- [ ] **Step 1: Создать протокол и шаблон итогов**

`docs/research/m1-field-test.md`:
````markdown
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
Проверка: `adb pull /sdcard/Android/data/io.visnav.app/files/logs/parity.json` — `cosine` ≥ 0.98
(расхождение только из-за различий ресайза и float16 не должно быть больше).

## Поездка
- Телефон на держателе горизонтально, камера смотрит вперёд (как в протоколе M0), на зарядке.
- Маршрут — внутри коридора базы (тот же район, что S1), вне зоны подмены GPS у Кремля.
- Сессия 1: режим «окно по GPS», ≥ 30 минут езды. Сессия 2: режим «визуальное слежение», тот же маршрут.
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
- Нагрев/троттлинг за 30 минут (субъективно + падение fps по журналу): …

## Критерий выхода M1
- [ ] Фиксация ≤ 20 м в ≥ 70 % покрытых кадров, режим gps — факт: …
- [ ] parity cos ≥ 0.98 — факт: …

## Что переносится в M2
- Если критерий не достигнут: повторное ранжирование (ALIKED + LightGlue), см. SPEC §6.3.
- Поведение режима visual (сколько держится без GPS до срыва) — вход для настройки фильтра в M2.
````

- [ ] **Step 2: Commit**

```bash
cd /Users/vvnovg/navigator
git add docs/research/m1-field-test.md
git commit -m "docs: M1 on-device parity check and field test protocol

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

- [ ] **Step 3: Выполнить протокол (владелец, после M0)**

Владелец ставит приложение, проводит поездки и передаёт журналы. Claude прогоняет `field-eval` и заполняет раздел «Итоги». Решение о переходе к M2 принимает владелец.
