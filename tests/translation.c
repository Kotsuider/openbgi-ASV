/* SPDX-License-Identifier: GPL-2.0-only */
#include "bgi/file.h"
#include "bgi/strutil.h"
#include "bgi/version.h"
#include "bgi/gfx/font.h"
#include <stdio.h>
#include <sys/stat.h>
#include <unistd.h>
#if defined(_WIN32)
#define MKDIR(p) mkdir(p)
#else
#define MKDIR(p) mkdir(p, 0755)
#endif

static int fails;
#define CHECK(c) do { if(!(c)) { printf("FAIL line %d: %s\n", __LINE__, #c); fails++; } } while(0)
static void Write(const char* path, const char* text)
{
	FILE* f = fopen(path, "wb");
	CHECK(f != NULL);
	if(f) { fwrite(text, 1, strlen(text), f); fclose(f); }
}

int main(int argc, char** argv)
{
	char dir[256], path[260], buf[32];
	uint32_t size;
	int generation;
	OS_Init();
	Drives_Scan();
	if(argc > 1)
	{
		char family[256];
		CHECK(FontFile_Family(argv[1], family, sizeof family));
		CHECK(strcmp(family, "EET") == 0);
		printf("Font family: %s\n", family);
	}
	snprintf(dir, sizeof dir, "translation-test-%ld", (long)getpid());
	CHECK(MKDIR(dir) == 0);
	CHECK(chdir(dir) == 0);
	CHECK(getcwd(path, sizeof path) != NULL);
	SetBaseDir(path);
	CHECK(MKDIR("patch") == 0);
	{
		// Minimal SFNT name table, independent of installed system fonts.
		uint8_t sfnt[52] = {0};
		char family[16];
		FILE* fixture;
		sfnt[1] = 1; sfnt[5] = 1;
		memcpy(sfnt + 12, "name", 4); sfnt[23] = 28; sfnt[27] = 24;
		sfnt[31] = 1; sfnt[33] = 18;
		sfnt[35] = 3; sfnt[37] = 1; sfnt[38] = 4; sfnt[39] = 9;
		sfnt[41] = 1; sfnt[43] = 6;
		sfnt[47] = 'E'; sfnt[49] = 'E'; sfnt[51] = 'T';
		fixture = fopen("fontfixture.ttf", "wb"); CHECK(fixture != NULL);
		if(fixture) { fwrite(sfnt, 1, sizeof sfnt, fixture); fclose(fixture); }
		CHECK(FontFile_Family("fontfixture.ttf", family, sizeof family));
		CHECK(!strcmp(family, "EET"));
		CHECK(!FontFile_Family("fontfixture.ttf", family, 2));
		fixture = fopen("fontfixture.ttf", "wb"); CHECK(fixture != NULL);
		if(fixture) { fwrite(sfnt, 1, 12, fixture); fclose(fixture); }
		CHECK(!FontFile_Family("fontfixture.ttf", family, sizeof family));
		remove("fontfixture.ttf");
	}

	Write("sample", "original");
	Write("fallback", "base");
	Write("patch/sample", "patched data");
	Write("patch/only", "extra");
	CHECK(File_SetPatchDir("patch"));
	CHECK(File_PatchPath(path, sizeof path, "sample"));
	CHECK(!File_PatchPath(path, sizeof path, "../sample"));
	CHECK(!File_PatchPath(path, sizeof path, "sub\\..\\sample"));
	CHECK(!File_PatchPath(path, 4, "sample"));
	CHECK(!File_PatchPath(path, sizeof path, "patch"));
	// Both legacy and decoded loaders must size, check and read the same override.
	for(generation = 0; generation < 2; generation++)
	{
		CHECK(Engine_SelectProfile(generation ? "1.69/472" : "1.66"));
		CHECK(GetFileSize_(NULL, "sample") == 12);
		CHECK(LoadFile(buf, NULL, "sample") == 12);
		CHECK(memcmp(buf, "patched data", 12) == 0);
		CHECK(LoadFileRange(buf, NULL, "sample", 2, 4) == 0);
		CHECK(memcmp(buf, "tche", 4) == 0);
		CHECK(FileExists(NULL, "only"));
		CHECK(LoadFile(buf, NULL, "fallback") == 4);
		CHECK(memcmp(buf, "base", 4) == 0);
	}
	CHECK(ReadFileEx(buf, &size, gBaseDir, "only", 0, 0) == 0 && size == 5);
	CHECK(FindMoviePath(path, "sample"));
	CHECK(File_SetPatchDir(""));
	CHECK(GetFileSize_(NULL, "sample") == 8);
	CHECK(!FileExists(NULL, "only"));

	CHECK(Text_SetCharRemap("003E:00D3,003C:00D5,002B:00B9,0025:00B2,0023:00C9,005E:00AB,0040:00BB,003B:00A1"));
	CHECK(Text_RemapUnicode('>') == 0xd3);
	CHECK(Text_RemapUnicode(';') == 0xa1);
	CHECK(Text_RemapUnicode('A') == 'A');
	CHECK(!Text_SetCharRemap("003E:D800"));
	CHECK(!Text_SetCharRemap("003E:110000"));
	CHECK(!Text_SetCharRemap("003E:00D3,"));
	CHECK(!Text_SetCharRemap("003E:00D3,003E:00D5"));
	CHECK(Text_RemapUnicode('>') == 0xd3); // rejection preserves the valid table
	CHECK(Text_SetCharRemap("003E:1F600,1F600:0041"));
	CHECK(Text_RemapUnicode('>') == 0x1f600); // substitutions do not cascade
	CHECK(Text_SetCharRemap(""));
	CHECK(Text_RemapUnicode('>') == '>');
	// Drawing and measuring use the same replacement, without changing script bytes.
	{
		OsFont_t* font = OS_FontOpen("Segoe UI", 24, 0, 0, 0);
		uint8_t expected[48 * 24] = {0}, actual[48 * 24] = {0};
		uint32_t scriptCode;
		CHECK(font != NULL);
		if(font)
		{
			Text_SetEncoding(TEXT_SJIS);
			OS_FontRender(font, "A", 1, expected, 48, 48, 24);
			CHECK(Text_SetCharRemap("003E:0041,3042:0041"));
			OS_FontRender(font, ">", 1, actual, 48, 48, 24);
			CHECK(memcmp(expected, actual, sizeof actual) == 0);
			CHECK(OS_FontCharAdvance(font, ">", 1) == OS_FontCharAdvance(font, "A", 1));
			memset(actual, 0, sizeof actual);
			OS_FontRender(font, "\x82\xa0", 2, actual, 48, 48, 24);
			CHECK(memcmp(expected, actual, sizeof actual) == 0);
			CHECK(TextGetChar(&scriptCode, ">") == 1 && scriptCode == '>');
			Text_SetEncoding(TEXT_UTF8);
			memset(actual, 0, sizeof actual);
			OS_FontRender(font, "\xe3\x81\x82", 3, actual, 48, 48, 24);
			CHECK(memcmp(expected, actual, sizeof actual) == 0);
			OS_FontClose(font);
			Text_SetEncoding(TEXT_SJIS);
			CHECK(Text_SetCharRemap(""));
		}
	}


	remove("sample"); remove("fallback"); remove("patch/sample"); remove("patch/only");
	rmdir("patch"); chdir(".."); rmdir(dir);
	OS_Shutdown();
	printf("%s\n", fails ? "FAILED" : "ok");
	return fails != 0;
}
