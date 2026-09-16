#!/usr/bin/env python3
"""Chatterbox Multilingual ONNX device-spike host side (roadmap D5).

Mirrors the onnx-community reference inference loop EXACTLY (the README's
`run_inference`, greedy argmax + repetition penalty 1.2, KV-cache over the 30
llama layers) so a device/host difference in the D5 leg is a kernel issue, not
a pipeline one:

  * text: AutoTokenizer over the pinned `BricksDisplay/chatterbox-multilingual-
    ONNX-q4` tokenizer.json, `prepare_language` "[<lang>]" prefix (en here);
  * position_ids = where(input_ids >= 6561, 0, arange-1) — the reference shape;
  * embed_tokens {input_ids, position_ids, exaggeration=0.5} -> inputs_embeds;
  * i==0: speech_encoder over the staged 24 kHz f32 voice -> audio_features
    (33 frames), audio_tokens (prompt token), speaker_embeddings, speaker_features;
    inputs_embeds = concat(cond, text embeds); past_key_values zeroed;
  * per step: language_model {inputs_embeds, attention_mask, past.*} -> logits,
    present.*; repetition penalty over generated tokens; ARGMAX (deterministic);
    stop on 6562; next-token embed + mask/past update;
  * speech_tokens = gen[:,1:-1] ++ prompt_token; conditional_decoder ->
    waveform (24 kHz).

On-device tokenization is a recorded D5 integration gap (HF BPE + the
Cangjie/kakasi/dicta language pre-processors), so the generated
`d5_chat_inputs.json` carries the INPUT_IDS per passage and the device runner
derives position_ids with the identical formula (same arrangement as the
Pocket leg's host-prepared tokens, decisions #149/#171).

Prereqs (host): pip onnxruntime, transformers, ffmpeg. Usage:

  python3 tools/gen_chatterbox_ref.py \
      --models-dir ~/.cache/ayvu-spike/chatterbox \
      --out docs/prints/d5

Writes: voice_24k.f32 (same name as the Pocket leg), d5_chat_inputs.json,
chat_ref_audio.f32 + chat_ref_meta.json (temperature-deterministic reference,
no separate latents — the token sequence IS the latent for this engine), and
d5_chat_host_*.wav sanity renders.
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
START_SPEECH_TOKEN = 6561
STOP_SPEECH_TOKEN = 6562
EXAGGERATION = 0.5
REPETITION_PENALTY = 1.2

# Same texts as the Pocket leg (docs/prints/d5), so the D5 blind read A/Bs the
# candidates on IDENTICAL passages. ref stays GREEDY (parity gate); the longer
# passages sample per the export's generation_config (temperature 0.8, top_p
# 0.95, seeded) — greedy+rep-penalty loops to noise on ~190-token prompts
# (measured: chat-p2 1209 tokens, no EOS, flatness 0.81).
PASSAGES = [
    {"id": "chat-ref-fox", "language": "en", "max_new_tokens": 256, "ref": True,
     "text": "The quick brown fox jumps over the lazy dog."},
    {"id": "chat-p1-science", "language": "en", "max_new_tokens": 256, "ref": False,
     "temperature": 0.8, "top_p": 0.95,
     "text": "Science is organized knowledge, wisdom is organized life."},
    {"id": "chat-p2a-danius", "language": "en", "max_new_tokens": 700, "ref": False,
     "temperature": 0.8, "top_p": 0.95,
     "text": "Danius said, \"Right now we are doing nothing. I have called and sent emails "
            "to his closest collaborator and received very friendly replies. For now, that "
            "is certainly enough.\""},
    {"id": "chat-p2b-ring", "language": "en", "max_new_tokens": 700, "ref": False,
     "temperature": 0.8, "top_p": 0.95,
     "text": "Previously, Ring's CEO, Jamie Siminoff, remarked the company started when his "
            "doorbell wasn't audible from his shop in his garage. He built a WiFi door "
            "bell, he said."},
]

THREADS_LEGS = [2, 4, 6]


def repetition_penalty(scores: np.ndarray, input_ids: np.ndarray, penalty: float) -> np.ndarray:
    """The reference RepetitionPenaltyLogitsProcessor, verbatim."""
    score = np.take_along_axis(scores, input_ids, axis=1)
    score = np.where(score < 0, score * penalty, score / penalty)
    out = scores.copy()
    np.put_along_axis(out, input_ids, score, axis=1)
    return out


def top_p_sample(logits1d: np.ndarray, temperature: float, top_p: float, rng) -> int:
    """HF TopPLogitsWarper + multinomial draw (the generation_config recipe)."""
    logits = logits1d / temperature
    probs = np.exp(logits - logits.max())
    probs /= probs.sum()
    order = np.argsort(-probs)
    cum = np.cumsum(probs[order])
    keep = cum - probs[order] < top_p
    mask = np.zeros_like(probs, bool)
    mask[order[keep]] = True
    probs = np.where(mask, probs, 0.0)
    probs /= probs.sum()
    return int(rng.choice(probs.size, p=probs))


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--models-dir", required=True, type=Path, help="dir with the 4 onnx + tokenizer files")
    ap.add_argument("--voice-wav", required=True, type=Path, help="default_voice.wav (the export's CC0 prompt)")
    ap.add_argument("--out", required=True, type=Path)
    ap.add_argument("--threads", type=int, default=4)
    args = ap.parse_args()

    import onnxruntime as ort
    from transformers import AutoTokenizer

    out = args.out
    out.mkdir(parents=True, exist_ok=True)

    # 1. voice: 24 kHz mono f32, byte-identical for host and device. A
    # dedicated filename (not the Pocket leg's voice_24k.f32 — the first run
    # silently reused that file, prompt-speaker bug) and ALWAYS resampled.
    if not shutil.which("ffmpeg"):
        print("ffmpeg required for the voice resample", file=sys.stderr)
        return 1
    voice = out / "chat_voice_24k.f32"
    subprocess.run(["ffmpeg", "-y", "-i", str(args.voice_wav), "-ar", str(SR), "-ac", "1",
                    "-f", "f32le", str(voice)], check=True, capture_output=True)
    voice_pcm = np.frombuffer(voice.read_bytes(), dtype="<f4")
    print(f"voice: {args.voice_wav.name} -> {voice_pcm.size / SR:.2f}s @ {SR} Hz ({voice.name})")

    # 2. tokenize (host-only: the reference path incl. its post-processing).
    tokenizer = AutoTokenizer.from_pretrained(str(args.models_dir))
    so = ort.SessionOptions()
    so.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_ALL
    so.intra_op_num_threads = args.threads
    so.inter_op_num_threads = 1
    sessions = {n: ort.InferenceSession(str(args.models_dir / f"{n}.onnx"), sess_options=so,
                                        providers=["CPUExecutionProvider"])
                for n in ("speech_encoder", "embed_tokens", "language_model", "conditional_decoder")}

    # voice prompt conditioning is voice-only and identical for every passage.
    (audio_features, audio_tokens, speaker_embeddings, speaker_features) = sessions["speech_encoder"].run(
        None, {"audio_values": voice_pcm[None].astype(np.float32)})

    passages_json = []
    host_wavs = {}
    ref_audio = None
    ref_meta = None

    for passage in PASSAGES:
        text = f"[{passage['language']}]{passage['text']}"
        input_ids = tokenizer(text, return_tensors="np")["input_ids"].astype(np.int64)

        position_ids = np.where(input_ids >= START_SPEECH_TOKEN, 0,
                                np.arange(input_ids.shape[1])[None, :] - 1).astype(np.int64)
        emb_inputs = {"input_ids": input_ids, "position_ids": position_ids,
                      "exaggeration": np.array([EXAGGERATION], np.float32)}
        inputs_embeds = sessions["embed_tokens"].run(None, emb_inputs)[0]

        # i == 0: voice conditioning + full text prompt.
        inputs_embeds = np.concatenate((audio_features, inputs_embeds), axis=1)
        bsz, _, _ = inputs_embeds.shape
        past = {f"past_key_values.{l}.{kv}": np.zeros((bsz, 16, 0, 64), np.float32)
                for l in range(30) for kv in ("key", "value")}
        attention_mask = np.ones((bsz, inputs_embeds.shape[1]), np.int64)

        generated = np.array([[START_SPEECH_TOKEN]])
        rng = np.random.default_rng(42)
        t0 = time.time()
        lm_ms = 0.0
        stopped = False
        for step in range(passage["max_new_tokens"]):
            m0 = time.time()
            logits, *present = sessions["language_model"].run(
                None, dict(inputs_embeds=inputs_embeds, attention_mask=attention_mask, **past))
            lm_ms += time.time() - m0
            logits = logits[:, -1, :]
            next_logits = repetition_penalty(logits, generated, REPETITION_PENALTY)
            if "temperature" in passage:
                next_id = top_p_sample(next_logits[0], passage["temperature"], passage["top_p"], rng)
                next_token = np.asarray([[next_id]], np.int64)
            else:
                next_token = np.argmax(next_logits, axis=-1, keepdims=True).astype(np.int64)
            generated = np.concatenate((generated, next_token), axis=-1)
            if (next_token == STOP_SPEECH_TOKEN).all():
                stopped = True
                break
            emb_inputs["input_ids"] = next_token
            emb_inputs["position_ids"] = np.full((bsz, 1), step + 1, np.int64)
            inputs_embeds = sessions["embed_tokens"].run(None, emb_inputs)[0]
            attention_mask = np.concatenate([attention_mask, np.ones((bsz, 1), np.int64)], axis=1)
            past = {f"past_key_values.{i//2}.{'key' if i % 2 == 0 else 'value'}": present[i]
                    for i in range(len(present))}

        speech_tokens = np.concatenate([audio_tokens, generated[:, 1:-1]], axis=1)
        wav = sessions["conditional_decoder"].run(
            None, {"speech_tokens": speech_tokens,
                   "speaker_embeddings": speaker_embeddings,
                   "speaker_features": speaker_features})[0]
        wav = np.squeeze(wav, axis=0)
        wall = time.time() - t0
        rms = float(np.sqrt(np.mean(wav.astype(np.float64) ** 2)))
        print(f"{passage['id']}: {speech_tokens.shape[1]} speech tokens, {wav.size / SR:.2f}s audio "
              f"in {wall:.1f}s (RTF {wall / (wav.size / SR):.3f}, lm {lm_ms / wall * 100:.0f}%), "
              f"rms={rms:.4f}, stop={'yes' if stopped else 'CAP-HIT'}")

        sparse = {"id": passage["id"], "input_ids": input_ids[0].tolist(),
                  "max_new_tokens": passage["max_new_tokens"]}
        if "temperature" in passage:
            sparse["temperature"] = passage["temperature"]
            sparse["top_p"] = passage["top_p"]
        passages_json.append(sparse)
        host_wavs[f"d5_chat_host_{passage['id']}.wav"] = wav
        if passage["ref"]:
            ref_audio = wav
            ref_meta = {"id": passage["id"], "speech_tokens": int(speech_tokens.shape[1]),
                        "speech_tokens_ids": speech_tokens[0].tolist(),
                        "audio_samples": int(wav.size)}

    inputs = {"voice_pcm": voice.name, "threads_legs": THREADS_LEGS, "exaggeration": EXAGGERATION,
              "passages": passages_json}
    (out / "d5_chat_inputs.json").write_text(json.dumps(inputs, indent=1))
    (out / "chat_ref_audio.f32").write_bytes(np.ascontiguousarray(ref_audio, "<f4").tobytes())
    (out / "chat_ref_meta.json").write_text(json.dumps(ref_meta))
    for name, pcm in host_wavs.items():
        wav16 = np.trunc(np.clip(pcm, -1.0, 1.0) * 32767).astype(np.int16)
        import struct
        data = wav16.tobytes()
        (out / name).write_bytes(struct.pack("<4sI4s4sIHHIIHH4sI", b"RIFF", 36 + len(data), b"WAVE", b"fmt ",
                                             16, 1, 1, SR, SR * 2, 2, 16, b"data", len(data)) + data)
    print("wrote voice_24k.f32 d5_chat_inputs.json chat_ref_{meta.json,audio.f32} d5_chat_host_*.wav")
    return 0


if __name__ == "__main__":
    sys.exit(main())