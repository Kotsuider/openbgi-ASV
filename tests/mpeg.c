/* SPDX-License-Identifier: GPL-2.0-only */
/* Vendor decoder regression; optionally decode BGI_MPEG_FILE for four seconds. */

#include <stdio.h>
#include <math.h>
#define PL_MPEG_IMPLEMENTATION
#include "../src/third_party/pl_mpeg.h"
static int pictures, audio;
static double energy;
static void video(plm_t* p, plm_frame_t* f, void* user) { (void)p;(void)user; if(!f->width || !f->height) pictures=-10000; pictures++; }
static void sound(plm_t* p, plm_samples_t* s, void* user) { (void)p;(void)user; audio++; for(unsigned i=0;i<s->count*2;i++) energy+=fabs(s->interleaved[i]); }
int main(int argc,char** argv) {
 uint8_t invalid[32] = {0};
 plm_t* bad = plm_create_with_memory(invalid, sizeof invalid, 0);
 if(bad && plm_has_headers(bad)) return 1;
 if(bad) plm_destroy(bad);
 const char* path = argc > 1 ? argv[1] : getenv("BGI_MPEG_FILE");
 if(!path || !*path) { puts("ok (real MPEG fixture skipped; set BGI_MPEG_FILE)"); return 0; }
 plm_t* p=plm_create_with_filename(path);
 if(!p || !plm_has_headers(p))return 3;
 printf("MPEG %dx%d %.3f fps %d Hz %.3f seconds\n",plm_get_width(p),plm_get_height(p),plm_get_framerate(p),plm_get_samplerate(p),plm_get_duration(p));
 plm_set_video_decode_callback(p,video,NULL);plm_set_audio_decode_callback(p,sound,NULL);
 for(int i=0;i<40;i++)plm_decode(p,0.1);
 printf("frames=%d audio_blocks=%d audio_energy=%.3f\n",pictures,audio,energy);
 int has_audio = plm_get_samplerate(p) > 0;
 plm_destroy(p);return pictures > 0 && (!has_audio || audio > 0) ? 0:4;
}
