/* SPDX-License-Identifier: GPL-2.0-only */
/* Streaming MPEG-1/MP2 host decoder. Only the public engine API is used. */
#include "movie.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#define PL_MPEG_IMPLEMENTATION
#include "../src/third_party/pl_mpeg.h"
#define PICTURES 8
#define SOUND    88200

typedef struct Movie
{
	OpenBGI_MovieFile_t file;
	int64_t position;
	plm_t* plm;
	uint8_t* rgb;
	int* xmap;
	uint8_t* pictures[PICTURES];
	int sw, sh, w, h, decoded, taken, audio, rate, phase, ended;
	int read, written;
	int16_t* pcm;
	double fps;
} Movie;

static void Load(plm_buffer_t* b, void* ctx)
{
	Movie* m = ctx;
	if(b->discard_read_bytes)
		plm_buffer_discard_read_bytes(b);
	int n = m->file.read(m->file.ctx, b->bytes + b->length, (int)(b->capacity - b->length));
	if(n > 0)
	{
		b->length += n;
		m->position += n;
	}
	else
		b->has_ended = 1;
}
static void Seek(plm_buffer_t* b, size_t offset, void* ctx)
{
	Movie* m = ctx;
	(void)b;
	if(m->file.seek(m->file.ctx, (int64_t)offset))
		m->position = (int64_t)offset;
}
static size_t Tell(plm_buffer_t* b, void* ctx)
{
	(void)b;
	return (size_t)((Movie*)ctx)->position;
}

static void Video(plm_t* p, plm_frame_t* f, void* ctx)
{
	Movie* m = ctx;
	(void)p;
	uint8_t* dst = m->pictures[m->decoded % PICTURES];
	plm_frame_to_bgra(f, m->rgb, m->sw * 4);
	for(int y = 0; y < m->h; ++y)
	{
		const uint32_t* row = (uint32_t*)m->rgb + (size_t)(y * m->sh / m->h) * m->sw;
		uint32_t* out = (uint32_t*)dst + (size_t)y * m->w;
		for(int x = 0; x < m->w; ++x)
			out[x] = row[m->xmap[x]];
	}
	++m->decoded;
}
static void Audio(plm_t* p, plm_samples_t* s, void* ctx)
{
	Movie* m = ctx;
	(void)p;
	for(unsigned i = 0; i < s->count; ++i)
	{
		m->phase += OPENBGI_AUDIO_RATE;
		while(m->phase >= m->rate)
		{
			m->phase -= m->rate;
			if(m->written - m->read >= SOUND)
				continue;
			int slot = m->written++ % SOUND;
			for(int c = 0; c < 2; ++c)
			{
				float value = s->interleaved[i * 2 + c] * 32767.0f;
				if(value > 32767)
					value = 32767;
				if(value < -32768)
					value = -32768;
				m->pcm[slot * 2 + c] = (int16_t)value;
			}
		}
	}
}
static void Close(void* user, void* movie)
{
	Movie* m = movie;
	(void)user;
	if(!m)
		return;
	if(m->plm)
		plm_destroy(m->plm);
	for(int i = 0; i < PICTURES; ++i)
		free(m->pictures[i]);
	free(m->rgb);
	free(m->xmap);
	free(m->pcm);
	free(m);
}
static void* Open(void* user, const OpenBGI_MovieFile_t* file, const char* path, int w, int h, int audio, OpenBGI_MovieInfo_t* info)
{
	(void)user;
	(void)path;
	if(w <= 0 || h <= 0 || w > 8192 || h > 8192 || !file->seek || file->length <= 0)
		return NULL;
	uint8_t magic[4];
	if(!file->seek(file->ctx, 0) || file->read(file->ctx, magic, 4) != 4 || memcmp(magic, "\0\0\1\xba", 4))
		return NULL;
	if(!file->seek(file->ctx, 0))
		return NULL;
	Movie* m = calloc(1, sizeof *m);
	if(!m)
		return NULL;
	m->file = *file;
	m->w = w;
	m->h = h;
	plm_buffer_t* b = plm_buffer_create_with_callbacks(Load, Seek, Tell, (size_t)file->length, m);
	m->plm = plm_create_with_buffer(b, 1);
	if(!m->plm || !plm_has_headers(m->plm))
	{
		Close(NULL, m);
		return NULL;
	}
	m->sw = plm_get_width(m->plm);
	m->sh = plm_get_height(m->plm);
	m->fps = plm_get_framerate(m->plm);
	if(m->sw <= 0 || m->sh <= 0 || m->sw > 8192 || m->sh > 8192 || !(m->fps > 0))
	{
		Close(NULL, m);
		return NULL;
	}
	m->rgb = malloc((size_t)m->sw * m->sh * 4);
	m->pcm = malloc(SOUND * 4);
	m->xmap = malloc((size_t)w * sizeof *m->xmap);
	if(m->xmap)
		for(int x = 0; x < w; ++x)
			m->xmap[x] = x * m->sw / w;
	for(int i = 0; i < PICTURES; ++i)
		m->pictures[i] = malloc((size_t)w * h * 4);
	if(!m->rgb || !m->pcm || !m->xmap)
	{
		Close(NULL, m);
		return NULL;
	}
	for(int i = 0; i < PICTURES; ++i)
		if(!m->pictures[i])
		{
			Close(NULL, m);
			return NULL;
		}
	m->rate = plm_get_samplerate(m->plm);
	m->audio = audio && plm_get_num_audio_streams(m->plm) > 0 && m->rate > 0;
	plm_set_audio_enabled(m->plm, m->audio);
	plm_set_video_decode_callback(m->plm, Video, m);
	if(m->audio)
		plm_set_audio_decode_callback(m->plm, Audio, m);
	info->fps = m->fps;
	info->lengthMs = (int32_t)(plm_get_duration(m->plm) * 1000);
	info->audio = m->audio;
	fprintf(stderr, "openbgi: PL_MPEG %s (%dx%d, %.3f fps, %d Hz, %d ms)\n", path, m->sw, m->sh, m->fps, m->rate, info->lengthMs);
	return m;
}
static int32_t Frames(void* user, void* movie, int* ended)
{
	Movie* m = movie;
	(void)user;
	int attempts = 0;
	while(!m->ended && m->decoded - m->taken < 4 && attempts++ < 8)
	{
		plm_decode(m->plm, 1.0 / m->fps);
		m->ended = plm_has_ended(m->plm);
	}
	*ended = m->ended;
	return m->decoded;
}
static int Take(void* user, void* movie, int32_t index, uint8_t* out)
{
	Movie* m = movie;
	(void)user;
	if(index < m->taken || index >= m->decoded || index < m->decoded - PICTURES)
		return 0;
	memcpy(out, m->pictures[index % PICTURES], (size_t)m->w * m->h * 4);
	m->taken = index + 1;
	return 1;
}
static int Samples(void* user, void* movie, int16_t* out, int frames, int* ended)
{
	Movie* m = movie;
	(void)user;
	int count = m->written - m->read;
	if(count > frames)
		count = frames;
	for(int i = 0; i < count; ++i)
	{
		int slot = m->read++ % SOUND;
		if(out)
		{
			out[i * 2] = m->pcm[slot * 2];
			out[i * 2 + 1] = m->pcm[slot * 2 + 1];
		}
	}
	*ended = (!m->audio || m->ended) && m->read == m->written;
	return count;
}
const OpenBGI_MovieDecoder_t* Android_MpegDecoder(void)
{
	static const OpenBGI_MovieDecoder_t decoder = {sizeof decoder, Open, Frames, Take, Samples, Close};
	return &decoder;
}
