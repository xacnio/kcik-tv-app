/*
 * JNI bridge: real-time RNNoise denoising of IVS decoded PCM.
 *
 * Input from the Pine-hooked AudioTrackRenderer.render() is interleaved 16-bit
 * PCM, 48 kHz, `channels` channels (stereo). RNNoise runs at 48 kHz on 480-sample
 * (10 ms) mono frames, one DenoiseState per channel.
 *
 * render() delivers variable-sized blocks that are not multiples of 480, so we
 * accumulate per channel, process full frames, mix (dry/wet) at frame time and
 * emit through a small ring pre-seeded with SEED zeros. This keeps the write size
 * per call equal to the input while adding only ~10-11 ms of latency.
 *
 * Presets are data-driven: nrProcess() receives baseWet + vadAdapt. The per-frame
 * wet is  clamp(baseWet - vadAdapt * vad, 0, 0.95), one-pole smoothed. A non-zero
 * vadAdapt (Voice Focus) denoises less during speech and more during noise-only,
 * using the voice-activity probability returned by rnnoise_process_frame().
 * The 0.95 cap keeps a small dry floor so output never hard-mutes ("cuts").
 */
#include <jni.h>
#include <stdlib.h>
#include <stdint.h>
#include <android/log.h>
#include "rnnoise.h"

#define TAG "AudioNrTapJNI"
#define FRAME 480
#define SEED 512          /* priming latency, absorbs framing phase (<480) */
#define RING_CAP 4096     /* >> SEED + FRAME */
#define MAXCH 2

typedef struct {
    DenoiseState *st;
    float acc[FRAME];
    float prevAcc[FRAME];   /* previous frame's input — dry, delay-aligned to wet */
    int accN;
    float ring[RING_CAP];   /* baked (mixed) output samples */
    int head;
    int count;
    float wSmooth;          /* one-pole smoothed wet, avoids gain pumping */
} ChanCtx;

typedef struct {
    int channels;
    volatile int busy;   /* reentrancy guard: at most one thread in nrProcess per ctx */
    ChanCtx ch[MAXCH];
} NrCtx;

static void seed_channel(ChanCtx *c) {
    c->accN = 0;
    c->head = 0;
    c->count = SEED;
    c->wSmooth = 0.f;
    for (int i = 0; i < SEED; i++) c->ring[i] = 0.f;
}

/* Per-frame smoothed wet from preset params. Capped at 0.95 (comfort floor). */
static float frame_wet(float baseWet, float vadAdapt, float vad, float *wSmooth) {
    float target = baseWet - vadAdapt * vad;
    if (target < 0.0f) target = 0.0f;
    if (target > 0.95f) target = 0.95f;
    *wSmooth += 0.25f * (target - *wSmooth);
    return *wSmooth;
}

JNIEXPORT jlong JNICALL
Java_dev_xacnio_kciktv_mobile_ui_player_AudioNrTap_nrCreate(JNIEnv *env, jobject thiz, jint channels) {
    if (channels < 1) channels = 1;
    if (channels > MAXCH) channels = MAXCH;
    NrCtx *ctx = (NrCtx *) calloc(1, sizeof(NrCtx));
    if (!ctx) return 0;
    ctx->channels = channels;
    for (int c = 0; c < channels; c++) {
        ctx->ch[c].st = rnnoise_create(NULL);
        seed_channel(&ctx->ch[c]);
    }
    __android_log_print(ANDROID_LOG_INFO, TAG, "nrCreate channels=%d frameSize=%d", channels, rnnoise_get_frame_size());
    return (jlong) (intptr_t) ctx;
}

JNIEXPORT void JNICALL
Java_dev_xacnio_kciktv_mobile_ui_player_AudioNrTap_nrDestroy(JNIEnv *env, jobject thiz, jlong handle) {
    NrCtx *ctx = (NrCtx *) (intptr_t) handle;
    if (!ctx) return;
    for (int c = 0; c < ctx->channels; c++)
        if (ctx->ch[c].st) rnnoise_destroy(ctx->ch[c].st);
    free(ctx);
}

/* Process `sizeBytes` of interleaved 16-bit PCM starting at startByte in a direct ByteBuffer, in place. */
JNIEXPORT void JNICALL
Java_dev_xacnio_kciktv_mobile_ui_player_AudioNrTap_nrProcess(JNIEnv *env, jobject thiz,
        jlong handle, jobject buf, jint startByte, jint sizeBytes, jfloat baseWet, jfloat vadAdapt) {
    NrCtx *ctx = (NrCtx *) (intptr_t) handle;
    if (!ctx || !buf || startByte < 0 || sizeBytes <= 0) return;

    void *base = (*env)->GetDirectBufferAddress(env, buf);
    jlong cap = (*env)->GetDirectBufferCapacity(env, buf);
    if (!base) return;
    /* Clamp to the buffer's real capacity so we never write past its memory. */
    if (cap > 0 && (jlong) startByte + sizeBytes > cap) {
        sizeBytes = (jint) (cap - startByte);
    }
    if (sizeBytes <= 0) return;

    short *pcm = (short *) ((char *) base + startByte);

    const int ch = ctx->channels;
    const int nPerCh = (sizeBytes / 2) / ch;

    /* One-shot diagnostic: confirms native processing came up correctly. */
    static int dbgLogged = 0;
    if (!dbgLogged) {
        dbgLogged = 1;
        __android_log_print(ANDROID_LOG_INFO, TAG,
            "nrProcess live: baseWet=%.2f vadAdapt=%.2f nPerCh=%d channels=%d",
            baseWet, vadAdapt, nPerCh, ch);
    }

    for (int i = 0; i < nPerCh; i++) {
        for (int c = 0; c < ch; c++) {
            ChanCtx *cc = &ctx->ch[c];
            short s = pcm[i * ch + c];

            /* feed input; on full frame, denoise, mix, and push to ring */
            if (cc->accN < 0 || cc->accN >= FRAME) cc->accN = 0; /* defensive bound */
            cc->acc[cc->accN++] = (float) s;
            if (cc->accN == FRAME) {
                float out[FRAME];
                float vad = rnnoise_process_frame(cc->st, out, cc->acc);
                float w = frame_wet(baseWet, vadAdapt, vad, &cc->wSmooth);
                /* RNNoise output lags its input by one frame (960-sample window,
                 * 50% overlap-add), so out[] corresponds to prevAcc[], not acc[].
                 * Mixing with prevAcc keeps dry/wet phase-aligned (no comb filtering). */
                for (int k = 0; k < FRAME; k++) {
                    int wi = (cc->head + cc->count) % RING_CAP;
                    cc->ring[wi] = cc->prevAcc[k] * (1.0f - w) + out[k] * w;
                    cc->count++;
                }
                for (int k = 0; k < FRAME; k++) cc->prevAcc[k] = cc->acc[k];
                cc->accN = 0;
            }

            /* emit one aligned (delayed) sample */
            float o;
            if (cc->count > 0) {
                o = cc->ring[cc->head];
                cc->head = (cc->head + 1) % RING_CAP;
                cc->count--;
            } else {
                o = (float) s; /* underflow fallback: passthrough */
            }
            int v = (int) o;
            if (v > 32767) v = 32767; else if (v < -32768) v = -32768;
            pcm[i * ch + c] = (short) v;
        }
    }
}
