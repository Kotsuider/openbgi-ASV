/* SPDX-License-Identifier: GPL-2.0-only */
#include "movie.h"
#include "check.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
static int Read(void* ctx, uint8_t* dst, int n)
{
	return (int)fread(dst, 1, n, ctx);
}
static int Seek(void* ctx, int64_t off)
{
	return fseek(ctx, (long)off, SEEK_SET) == 0;
}
int main(int argc, char** argv)
{
	if(argc != 2)
	{
		fprintf(stderr, "usage: test_android_movie MPEG1.mpg\n");
		return 2;
	}
	FILE* fp = fopen(argv[1], "rb");
	if(!fp)
		return 2;
	fseek(fp, 0, SEEK_END);
	long size = ftell(fp);
	rewind(fp);
	OpenBGI_MovieFile_t file = {sizeof file, fp, Read, Seek, size};
	const OpenBGI_MovieDecoder_t* d = Android_MpegDecoder();
	OpenBGI_MovieInfo_t info;
	void* m = d->open(NULL, &file, argv[1], 160, 90, 1, &info);
	CHECK(m);
	if(!m)
		return 1;
	CHECK(info.fps > 0 && info.audio && info.lengthMs > 0);
	uint8_t pixels[160 * 90 * 4];
	int16_t samples[4096 * 2];
	int vEnd = 0, aEnd = 0, seen = 0, sound = 0, previous = 0, signal = 0;
	for(int pass = 0; pass < 10000 && !(vEnd && aEnd); ++pass)
	{
		int frames = d->frames(NULL, m, &vEnd);
		CHECK(frames >= previous);
		previous = frames;
		if(frames > seen)
		{
			CHECK(d->take(NULL, m, frames - 1, pixels));
			seen = frames;
			CHECK(!d->take(NULL, m, frames - 1, pixels));
		}
		int n;
		do
		{
			n = d->samples(NULL, m, samples, 4096, &aEnd);
			sound += n;
			for(int i = 0; i < n * 2; ++i)
				signal |= samples[i] != 0;
		} while(n);
	}
	CHECK(vEnd && aEnd && seen > 4 && sound > 0 && signal);
	d->close(NULL, m);
	rewind(fp);
	CHECK(!d->open(NULL, &file, "invalid size", 0, 90, 1, &info));
	fclose(fp);
	printf("android_movie: %d frames, %d samples, %d failures\n", seen, sound, gFails);
	return gFails ? 1 : 0;
}
