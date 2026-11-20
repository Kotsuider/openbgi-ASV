/* SPDX-License-Identifier: GPL-2.0-only */
/* Android MPEG-1/MP2 fallback. Open through SAF once, then stream from the
 * retained descriptor. Decoding stays on the engine thread; PCM crosses to the mixer. */
#include "bgi/gfx.h"
#if defined(BGI_ANDROID)
#include "bgi/display.h"
#include "bgi/os.h"
#include "bgi/snd/wavemaster.h"
#include <math.h>
#include <unistd.h>
extern int Android_MovieFileOpen(const char* path);
#define PL_MPEG_IMPLEMENTATION
#include "../third_party/pl_mpeg.h"
#define AUDIO_RING 88200

typedef struct MpegMovie {
    plm_t* decoder;
    FILE* file;
    plm_frame_t* decoded;
    int* xmap;
    OsLock_t* lock;
    uint8_t* rgb;
    uint8_t* scaled;
    int sw, sh, x, y, w, h, dirty, rate, phase;
    int read, write, gain, audio;
    int16_t* pcm;
    uint32_t tick;
    int64_t decodeNs, presentNs, worstNs;
    unsigned shown;
    uint32_t statsTick;
} MpegMovie;
static MpegMovie* movie;

static void Video(plm_t* decoder, plm_frame_t* frame, void* user) {
    MpegMovie* m = user; BGI_UNUSED(decoder);
    // Only the last decoded frame is presented when catching up.
    m->decoded = frame;
    m->dirty = 1;
}
static void Audio(plm_t* decoder, plm_samples_t* samples, void* user) {
    MpegMovie* m = user;
    unsigned i;
    BGI_UNUSED(decoder);
    OS_LockEnter(m->lock);
    for(i = 0; i < samples->count; i++) {
        m->phase += 44100;
        while(m->phase >= m->rate) {
            m->phase -= m->rate;
            if(m->write - m->read < AUDIO_RING) {
                int slot = m->write++ % AUDIO_RING, channel;
                for(channel = 0; channel < 2; channel++) {
                    float value = samples->interleaved[i * 2 + channel] * 32767.0f;
                    if(value > 32767) value = 32767;
                    if(value < -32768) value = -32768;
                    m->pcm[slot * 2 + channel] = (int16_t)value;
                }
            }
        }
    }
    OS_LockLeave(m->lock);
}
static void Mix(void* user, int32_t* output, int frames) {
    MpegMovie* m = user; int i;
    OS_LockEnter(m->lock);
    for(i = 0; i < frames && m->read < m->write; i++) {
        int slot = m->read++ % AUDIO_RING;
        output[i*2] += (int32_t)((int64_t)m->pcm[slot*2] * m->gain / 65536);
        output[i*2+1] += (int32_t)((int64_t)m->pcm[slot*2+1] * m->gain / 65536);
    }
    OS_LockLeave(m->lock);
}
void MovieFx_SetVolume(int32_t volume) {
    if(!movie) return;
    OS_LockEnter(movie->lock);
    movie->gain = volume <= -10000 ? 0 : volume >= 0 ? 65536 : (int)(pow(10.0, volume / 2000.0) * 65536);
    OS_LockLeave(movie->lock);
}
void MovieFx_Stop(void) {
    MpegMovie* m = movie;
    if(!m) return;
    movie = NULL;
    if(m->audio) Wm_SetExternalMix(NULL, NULL);
    if(m->decoder) plm_destroy(m->decoder); // owns and closes the FILE
    else if(m->file) fclose(m->file);
    OS_LockDestroy(m->lock);
    BGI_Free(m->rgb); BGI_Free(m->scaled); BGI_Free(m->xmap); BGI_Free(m->pcm); BGI_Free(m);
}
int MovieFx_Start(const char* path, int x, int y, int w, int h, int32_t volume, int32_t* length) {
    uint8_t signature[4];
    MpegMovie* m;
    int fd;
    MovieFx_Stop();
    if(w <= 0 || h <= 0 || (size_t)w * h > 0x1000000) return 0;
    m = BGI_Calloc(sizeof *m);
    fd = Android_MovieFileOpen(path);
    if(fd < 0) { BGI_Free(m); return 0; }
    m->file = fdopen(fd, "rb");
    if(!m->file) { close(fd); BGI_Free(m); return 0; }
    if(fread(signature, 1, 4, m->file) != 4 || memcmp(signature, "\0\0\1\xba", 4)) {
        fclose(m->file); BGI_Free(m); return 0;
    }
    rewind(m->file);
    m->decoder = plm_create_with_file(m->file, 1);
    m->lock = OS_LockCreate();
    movie = m;
    if(!m->decoder || !plm_has_headers(m->decoder)) { MovieFx_Stop(); return 0; }
    m->sw = plm_get_width(m->decoder); m->sh = plm_get_height(m->decoder);
    if(m->sw <= 0 || m->sh <= 0 || (size_t)m->sw * m->sh > 0x1000000) { MovieFx_Stop(); return 0; }
    m->rgb = BGI_Alloc((size_t)m->sw * m->sh * 4);
    if(w != m->sw || h != m->sh) m->scaled = BGI_Alloc((size_t)w * h * 4);
    m->xmap = BGI_Alloc((size_t)w * sizeof *m->xmap);
    for(int column = 0; column < w; column++) m->xmap[column] = column * m->sw / w;
    m->pcm = BGI_Calloc(AUDIO_RING * 2 * sizeof *m->pcm);
    m->rate = plm_get_samplerate(m->decoder);
    m->x = x; m->y = y; m->w = w; m->h = h;
    m->audio = Wm_MixRunning() && m->rate > 0;
    plm_set_audio_enabled(m->decoder, m->audio);
    plm_set_video_decode_callback(m->decoder, Video, m);
    if(m->audio) {
        plm_set_audio_decode_callback(m->decoder, Audio, m);
        plm_set_audio_lead_time(m->decoder, 0.15);
    }
    *length = (int32_t)(plm_get_duration(m->decoder) * 1000);
    MovieFx_SetVolume(volume);
    plm_decode(m->decoder, 0);
    m->tick = OS_TicksMs();
    m->statsTick = m->tick;
    if(m->audio) Wm_SetExternalMix(Mix, m);
    fprintf(stderr, "bgi: PL_MPEG %s (%dx%d, %.3f fps, %d Hz, %d ms)\n", path, m->sw, m->sh, plm_get_framerate(m->decoder), m->rate, *length);
    return 1;
}
int MovieFx_IsRunning(void) { return movie != NULL; }
int MovieFx_Tick(void) {
    MpegMovie* m = movie;
    uint32_t now;
    int pending;
    int64_t begin = 0, afterDecode = 0, afterPresent = 0;
    if(!m) return 0;
    now = OS_TicksMs();
    OS_PerfCounterNs(&begin);
    plm_decode(m->decoder, (now - m->tick) / 1000.0);
    OS_PerfCounterNs(&afterDecode);
    m->tick = now;
    m->decodeNs += afterDecode - begin;
    if(m->dirty && gWindowAlive) {
        Bmp_t bitmap;
        OsDc_t* dc;
        int x, y;
        plm_frame_to_bgra(m->decoded, m->rgb, m->sw * 4);
        if(m->scaled) for(y = 0; y < m->h; y++) {
            const uint32_t* source = (const uint32_t*)m->rgb + (size_t)(y * m->sh / m->h) * m->sw;
            uint32_t* destination = (uint32_t*)m->scaled + (size_t)y * m->w;
            for(x = 0; x < m->w; x++) destination[x] = source[m->xmap[x]];
        }
        memset(&bitmap, 0, sizeof bitmap);
        bitmap.pixels = m->scaled ? m->scaled : m->rgb; bitmap.pitch = m->w * 4;
        bitmap.w = m->w; bitmap.h = m->h; bitmap.mode = PM_RGB32; bitmap.bpp = 4;
        dc = OS_WindowGetDc(); Window_DcBlit(dc, m->x, m->y, &bitmap); OS_WindowReleaseDc(dc);
        m->dirty = 0;
        m->shown++;
        OS_PerfCounterNs(&afterPresent);
        m->presentNs += afterPresent - afterDecode;
        if(afterPresent - begin > m->worstNs) m->worstNs = afterPresent - begin;
    }
    if(now - m->statsTick >= 5000 && m->shown) {
        fprintf(stderr, "bgi: MPEG performance: %.1f fps shown, decode %.2f ms/frame, output %.2f ms/frame, worst tick %.2f ms\n",
            m->shown * 1000.0 / (now - m->statsTick), m->decodeNs / (double)m->shown / 1e6,
            m->presentNs / (double)m->shown / 1e6, m->worstNs / 1e6);
        m->shown = 0; m->decodeNs = m->presentNs = m->worstNs = 0; m->statsTick = now;
    }
    OS_LockEnter(m->lock); pending = m->write - m->read; OS_LockLeave(m->lock);
    if(plm_has_ended(m->decoder) && (!m->audio || !pending)) { MovieFx_Stop(); return 0; }
    return 1;
}
#endif
