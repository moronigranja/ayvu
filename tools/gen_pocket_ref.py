#!/usr/bin/env python3
"""D5 Pocket TTS device-spike host side (roadmap D5, decisions #149/#153).

Generates the inputs the on-device `PocketProbeRunner` reads and the
temperature-0 parity reference it compares against — mirroring the runner's
Kotlin loop exactly (same graphs, same state feedback, same stop rule), so a
parity diff on device is a kernel difference, not a pipeline difference:

  * text SentencePiece tokenization over the bundle's tokenizer.model with the
    C++-reference prepare_text semantics the runner documents (strip,
    newline->space, capitalize first letter, terminal '.', and the bundle's
    pad_with_spaces_for_short_inputs flag — FALSE for english_2026-04);
  * voice: the staged 24 kHz f32 mono PCM (voice_24k.f32) through mimi_encoder
    (fp32) — the runner feeds the identical bytes;
  * main: flow_lm_main_int8 stateful graph; sentence 0 voice-conditions then
    text-conditions, snapshotting the KV state; later sentences restore it
    (PocketTTS.cpp tier-1 cache); conditionings happen through the text slot
    with an empty [1,0,32] sequence (the runner's own scheme);
  * AR: one main call per frame with the NaN first frame (graph BOS), eos stop
    at eos_extra frames past a logit crossing -4.0, zeros noise (temperature 0
    => x is zeros and flow integrates deterministically);
  * decoder: mimi_decoder_int8 per latent frame (1-frame canonical chunk);
  * audio: the reference audio file is quantized exactly like the device WAV
    writer+reader pair (truncate(clip*32767)/32768) so the device parity
    comparison is int16-comparable.

Prereqs (host): pip onnxruntime + sentencepiece; ffmpeg for the voice
resample. Usage:

  python3 tools/gen_pocket_ref.py \
      --models-dir ~/.cache/ayvu-spike/pocket-tts/english_2026-04 \
      --voice-wav /tmp/peter_yearsley.wav \
      --out docs/prints/d5

Writes: out/voice_24k.f32, out/d5_inputs.json, out/pocket_ref_meta.json,
out/pocket_ref_latents.f32, out/pocket_ref_audio.f32, and d5_host_*.wav
(listening sanity). The device stage then copies the first five + the graphs
per build.md "D5 Pocket TTS staging".

Passages are fixed here so the leg is reproducible across devices (S22 / Fold);
each ever-changing text must driver no behavior change in the runner.
"""

from __future__ import annotations

import argparse
import json
import shutil
import subprocess
import sys
import time
from pathlib import Path

import numpy as np

SR = 24000
SAMPLES_PER_FRAME = 1920
EOS_THRESHOLD = -4.0
XFADE_SAMPLES = 240
KV_CAPACITY = 1000
LATENT_DIM = 32
COND_DIM = 1024
MAX_FRAMES = 500

# (id, temperature, [(sentence, eos_extra, is_ref)]) — kept in this file so the
# S22 and Fold legs measure the same passages.
PASSAGES = [
    {
        "id": "ref",
        "temperature": 0.0,
        "sentences": [
            ("The quick brown fox jumps over the lazy dog.", 3, True),
        ],
    },
    {
        "id": "p1",
        "temperature": 0.7,
        "sentences": [
            ("Science is organized knowledge, wisdom is organized life.", 3, False),
        ],
    },
    {
        "id": "p2",
        "temperature": 0.7,
        "sentences": [
            ("The committee met at nine o'clock on a cold and clear morning.", 3, False),
            ("Siminoff said sales doubled after his appearance on the shopping channel.", 3, False),
        ],
    },
]

THREADS_LEGS = [2, 4, 6]


def prepare_text(text: str, pad_short: bool) -> str:
    """C++-reference prepare_text (strip, newlines collapse, capitalize,
    terminal punctuation; pad only when the bundle asks for it)."""
    t = text.strip().replace("\n", " ").replace("\r", " ").replace("  ", " ")
    if not t:
        raise ValueError("empty text")
    if not t[0].isupper():
        t = t[0].upper() + t[1:]
    if t[-1].isalnum():
        t += "."
    if pad_short and len(t.split()) < 5:
        t = " " * 8 + t
    return t


def eos_extra_for(text: str) -> int:
    """KDoc contract: eos_extra 3 normal, 5 for <5-word sentences."""
    return 5 if len(text.split()) < 5 else 3


class Graph:
    def __init__(self, sess):
        self.sess = sess
        self.states = {
            i.name: np.zeros(i.shape, dtype={
                "tensor(float)": np.float32, "tensor(int64)": np.int64, "tensor(bool)": np.bool_,
            }.get(i.type, np.float32))
            for i in sess.get_inputs() if i.name.startswith("state_")
        }
        self._snap: dict[str, np.ndarray] = {}

    def reinit(self):
        for k in self.states:
            self.states[k][:] = 0

    def snapshot(self):
        self._snap = {k: v.copy() for k, v in self.states.items()}

    def restore(self):
        for k, v in self._snap.items():
            self.states[k][:] = v

    def run(self, feeds: dict, want: list[str]) -> dict:
        inputs = dict(feeds)
        inputs.update(self.states)
        outs = self.sess.run([f"out_{s}" for s in self.states] + want, inputs)
        for s, v in zip(self.states, outs[: len(self.states)]):
            self.states[s][:] = v
        return dict(zip(want, outs[len(self.states):]))


def run(
    models_dir: Path,
    tokenizer,
    voice_pcm: np.ndarray,
    passages: list[dict],
    out_dir: Path,
    threads: int,
):
    import onnxruntime as ort

    so = ort.SessionOptions()
    so.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_ALL
    so.intra_op_num_threads = threads
    so.inter_op_num_threads = 1

    def open_model(name: str):
        return ort.InferenceSession(str(models_dir / name), sess_options=so, providers=["CPUExecutionProvider"])

    enc = open_model("mimi_encoder.onnx")
    txt = open_model("text_conditioner.onnx")
    main_s = open_model("flow_lm_main_int8.onnx")
    flow_s = open_model("flow_lm_flow_int8.onnx")
    dec_s = open_model("mimi_decoder_int8.onnx")
    main = Graph(main_s)
    dec = Graph(dec_s)

    empty_seq = np.zeros((1, 0, LATENT_DIM), np.float32)
    empty_text = np.zeros((1, 0, COND_DIM), np.float32)

    # voice encoding — identical bytes the runner feeds.
    encoded = enc.run(None, {"audio": voice_pcm[None, None, :]})[0]  # [1,F,1024]

    # insert_bos_before_voice=true (bundle.json): the voice conditioning slots a
    # BOS embedding AHEAD of the voice embeddings. Dropping it (the first
    # version of this tool AND the device runner did) makes the whole
    # generation degenerate — near-silent renders whose "parity" was agreement
    # between two identically-wrong pipelines (owner's ear, 2026-09-16).
    bos = np.load(models_dir / "bos_before_voice.npy")  # [1,1,1024]
    voice = np.concatenate([bos[0], encoded[0]], axis=0)  # [F+1,1024]

    ref_meta = None
    ref_latents = None
    ref_audio = None
    host_wavs = {}

    for passage in passages:
        pid = passage["id"]
        temp = passage["temperature"]
        t0 = time.time()
        all_pcm = np.zeros(0, np.float32)
        total_frames = 0
        eos_frame = -1
        refs_sent = None

        for si, (sent, extra, is_ref) in enumerate(passage["sentences"]):
            prepared = prepare_text(sent, pad_short=False)
            tokens = np.asarray(tokenizer.Encode(prepared), dtype=np.int64)[None, :]  # [1,T]

            temb = txt.run(None, {"token_ids": tokens})[0]  # [1,T,1024]

            main.reinit()
            if si == 0:
                main.run({"sequence": empty_seq, "text_embeddings": voice[None]}, ["conditioning"])
                main.snapshot()
            else:
                main.restore()
            main.run({"sequence": empty_seq, "text_embeddings": temb}, ["conditioning"])

            ctx_budget = int(KV_CAPACITY - voice.shape[0] - temb.shape[1])
            cap = min(MAX_FRAMES, ctx_budget)
            curr = np.full((1, 1, LATENT_DIM), np.nan, np.float32)
            latents = []
            local_eos = -1
            for _ in range(cap):
                outs = main.run({"sequence": curr, "text_embeddings": empty_text}, ["conditioning", "eos_logit"])
                cond = outs["conditioning"][0]
                eos = float(outs["eos_logit"].reshape(-1)[0])
                if local_eos < 0 and eos > EOS_THRESHOLD:
                    local_eos = len(latents)
                if local_eos >= 0 and len(latents) >= local_eos + extra:
                    break
                x = np.zeros((1, LATENT_DIM), np.float32)  # temperature 0: zero noise
                s = 0.0
                dt = 1.0
                out = flow_s.run(None, {
                    "c": cond[None].astype(np.float32),
                    "s": np.asarray([[s]], np.float32),
                    "t": np.asarray([[s + dt]], np.float32),
                    "x": x,
                })[0]
                x = x + out * dt
                latents.append(x)
                curr = x[None]

            if local_eos >= 0 and eos_frame < 0:
                eos_frame = total_frames + local_eos
            total_frames += len(latents)

            pcm = np.zeros((len(latents) * SAMPLES_PER_FRAME,), np.float32)
            dec.reinit()
            for f, lat in enumerate(latents):
                a = dec.run({"latent": lat[None].astype(np.float32)}, ["audio_frame"])["audio_frame"]
                pcm[f * SAMPLES_PER_FRAME : (f + 1) * SAMPLES_PER_FRAME] = a.reshape(-1)

            all_pcm = pcm if all_pcm.size == 0 else crossfade(all_pcm, pcm)

            if is_ref:
                refs_sent = {"eos_frame": eos_frame, "frames": total_frames,
                             "latents": np.asarray(latents, np.float32).reshape(-1)}

        wall = time.time() - t0
        audio_s = all_pcm.size / SR
        rtf = wall / audio_s
        rms = float(np.sqrt(np.mean(all_pcm.astype(np.float64) ** 2)))
        print(f"{pid}: {total_frames} frames, {audio_s:.2f}s audio in {wall:.1f}s (RTF {rtf:.3f}), eos={eos_frame}, rms={rms:.4f}")

        host_wavs[f"d5_host_{pid}.wav"] = all_pcm
        if pid == "ref":
            ref_meta = {"id": pid, "eos_frame": eos_frame, "frames": total_frames}
            ref_latents = refs_sent["latents"]
            ref_audio = all_pcm

    return ref_meta, ref_latents, ref_audio, host_wavs


def crossfade(prev: np.ndarray, nxt: np.ndarray) -> np.ndarray:
    x = min(XFADE_SAMPLES, min(prev.size, nxt.size))
    out = np.zeros(prev.size + nxt.size - x, np.float32)
    out[: prev.size] = prev
    for j in range(x):
        t = j / x
        out[prev.size - x + j] = prev[prev.size - x + j] * (1 - t) + nxt[j] * t
    out[prev.size :] = nxt[x:]
    return out


def wav(mono_f32: np.ndarray, rate: int) -> bytes:
    """PCM16 mono WAV, Wav.write semantics (truncation), for host sanity files."""
    import struct
    pcm = np.trunc(np.clip(mono_f32, -1.0, 1.0) * 32767).astype(np.int16)
    data = pcm.tobytes()
    header = struct.pack("<4sI4s4sIHHIIHH4sI", b"RIFF", 36 + len(data), b"WAVE", b"fmt ", 16, 1, 1, rate, rate * 2, 2, 16, b"data", len(data))
    return header + data


def quantize_audio(a: np.ndarray) -> np.ndarray:
    """The device comparison value: truncate(clip*32767)/32768 (Wav.write+read)."""
    return np.trunc(np.clip(a, -1.0, 1.0) * 32767) / 32768.0


def read_f32(path: Path) -> np.ndarray:
    return np.frombuffer(path.read_bytes(), dtype="<f4")


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--models-dir", required=True, type=Path)
    ap.add_argument("--voice-wav", required=True, type=Path, help="seed voice .wav (kyutai/tts-voices, CC0/CC-BY-4.0)")
    ap.add_argument("--out", required=True, type=Path)
    ap.add_argument("--threads", type=int, default=4)
    args = ap.parse_args()

    import sentencepiece as spm

    out = args.out
    out.mkdir(parents=True, exist_ok=True)

    # 1. voice: resample to the model's 24 kHz mono f32 (byte-identical host/device).
    tmp_f32 = out / "voice_24k.f32"
    if not (shutil.which("ffmpeg")):
        print("ffmpeg required for the voice resample", file=sys.stderr)
        return 1
    subprocess.run(
        ["ffmpeg", "-y", "-i", str(args.voice_wav), "-ar", str(SR), "-ac", "1", "-f", "f32le", str(tmp_f32)],
        check=True,
        capture_output=True,
    )
    voice_pcm = read_f32(tmp_f32)
    print(f"voice: {args.voice_wav.name} -> {voice_pcm.size / SR:.2f}s @ {SR} Hz ({tmp_f32.name}, {tmp_f32.stat().st_size} B)")

    # 2. tokenizer + tokenized inputs.
    sp = spm.SentencePieceProcessor(model_file=str(args.models_dir / "tokenizer.model"))
    bundle = json.loads((args.models_dir / "bundle.json").read_text())
    pad_short = bool(bundle.get("pad_with_spaces_for_short_inputs", False))
    passages_json = []
    for p in PASSAGES:
        sentences = []
        for sent, extra, is_ref in p["sentences"]:
            if extra is None:
                extra = eos_extra_for(sent)
            tokens = sp.Encode(prepare_text(sent, pad_short))
            sentences.append({"tokens": tokens, "eos_extra": extra, "ref": is_ref})
        passages_json.append({"id": p["id"], "temperature": p["temperature"], "sentences": sentences})
    inputs = {"voice_pcm": tmp_f32.name, "threads_legs": THREADS_LEGS, "precision": "int8", "passages": passages_json}
    (out / "d5_inputs.json").write_text(json.dumps(inputs, indent=1))
    print(f"d5_inputs.json: {len(passages_json)} passages, threads legs {THREADS_LEGS}")

    # 3. host temp-0 reference + sanity WAVs.
    # The device stage needs the BOS embedding as a raw f32 (the Kotlin runner
    # has no .npy parser): same bytes the mirror concatenates ahead of the voice.
    bos_npy = np.load(args.models_dir / "bos_before_voice.npy")
    (out / "bos_before_voice.f32").write_bytes(bos_npy.astype("<f4").tobytes())
    meta, latents, audio, wavs = run(args.models_dir, sp, voice_pcm, PASSAGES, out, args.threads)
    (out / "pocket_ref_meta.json").write_text(json.dumps(meta))
    (out / "pocket_ref_latents.f32").write_bytes(latents.astype("<f4").tobytes())
    (out / "pocket_ref_audio.f32").write_bytes(quantize_audio(audio).astype("<f4").tobytes())
    for name, pcm in wavs.items():
        (out / name).write_bytes(wav(pcm, SR))
    print("wrote voice_24k.f32 d5_inputs.json pocket_ref_{meta.json,latents.f32,audio.f32} d5_host_*.wav")
    return 0


if __name__ == "__main__":
    sys.exit(main())