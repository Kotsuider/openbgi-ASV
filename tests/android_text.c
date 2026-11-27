/* SPDX-License-Identifier: GPL-2.0-only */
#include "bgi/core.h"
#include "../src/gfx/text/text_internal.h"
#include "check.h"
static uint32_t Ticks(void* u)
{
	(void)u;
	return 0;
}
static int Perf(void* u, int64_t* n)
{
	(void)u;
	*n = 0;
	return 1;
}
static void Date(void* u, OpenBGI_DateTime_t* t)
{
	(void)u;
	memset(t, 0, sizeof *t);
}
int main(void)
{
	OpenBGI_Host_t host = {.size = sizeof host, .api = OPENBGI_API_VERSION, .ticksMs = Ticks, .perfNs = Perf, .localTime = Date};
	OpenBGI_t *a = OpenBGI_New(&host), *b = OpenBGI_New(&host);
	CHECK(a && b);
	if(!a || !b)
		return 1;
	OpenBGI_Start(a, "--letter-spacing=3 --font-width=0 --font-size=24");
	CHECK(a->engine.letterSpacing == 3 && a->engine.naturalFontWidth && a->engine.fontSize == 24);
	CHECK(b->engine.letterSpacing == 0 && !b->engine.naturalFontWidth);
	CHECK(Text_SpacedAdvance(a, 7) == 10 && Text_SpacedAdvance(b, 7) == 7);
	CHECK(Text_GlyphProportional(a, 0x8440, 0) && Text_GlyphProportional(a, 0x8491, 0));
	CHECK(!Text_GlyphProportional(a, 0x82a0, 0));
	a->enc.textUtf8 = 1;
	CHECK(Text_GlyphProportional(a, 0x410, 0) && Text_GlyphProportional(a, 0x451, 0));
	CHECK(!Text_GlyphProportional(a, 0x3042, 0));
	CHECK(Text_NaturalSpacing(a, ' ') && Text_NaturalSpacing(a, 'A'));
	CHECK(!Text_NaturalSpacing(a, 0x3042));
	char word[64];
	CHECK(Text_WrapWordRun(a, word, "\xd0\x9f\xd1\x80\xd0\xb8\xd0\xb2\xd0\xb5\xd1\x82 world") == 12);
	a->engine.letterSpacing = -16;
	CHECK(Text_SpacedAdvance(a, 2) == 1);
	OpenBGI_Stop(a);
	OpenBGI_Start(a, "--letter-spacing=999 --font-size=3 --font-width=-1");
	CHECK(!a->engine.letterSpacing && !a->engine.fontSize && !a->engine.fontWidthSet);
	OpenBGI_Stop(a);
	OpenBGI_Start(a, "--letter-spacing= --font-width= --font-size=");
	CHECK(!a->engine.letterSpacing && !a->engine.fontSize && !a->engine.fontWidthSet);
	OpenBGI_Delete(a);
	OpenBGI_Delete(b);
	printf("android_text: %d failures\n", gFails);
	return gFails ? 1 : 0;
}
