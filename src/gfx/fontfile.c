/* SPDX-License-Identifier: GPL-2.0-only */
#include "bgi/gfx/font.h"
#include "bgi/os.h"

static uint32_t BE16(const uint8_t* p) { return ((uint32_t)p[0] << 8) | p[1]; }
static uint32_t BE32(const uint8_t* p) { return (BE16(p) << 16) | BE16(p + 2); }

// Read a Windows Unicode family name from an SFNT/OTF or the first TTC face.
int FontFile_Family(const char* path, char* utf8, size_t capacity)
{
    OsFile_t* file = OS_FileOpenRead(path);
    uint8_t* data;
    char candidate[256];
    uint32_t size, offset = 0, i, result = 0;
    int best = -1;
    if(file == OS_INVALID_FILE || capacity < 1) return 0;
    size = OS_FileSize(file);
    if(size < 12 || size > 64 * 1024 * 1024) { OS_FileClose(file); return 0; }
    data = BGI_Alloc(size);
    if(OS_FileRead(file, data, size) != size) { OS_FileClose(file); BGI_Free(data); return 0; }
    OS_FileClose(file);
    if(!memcmp(data, "ttcf", 4)) {
        if(size < 16 || !BE32(data + 8)) goto done;
        offset = BE32(data + 12);
    }
    if(offset > size - 12) goto done;
    for(i = 0; i < BE16(data + offset + 4); i++) {
        uint32_t entry = offset + 12 + i * 16, start, length, j;
        if(entry > size || size - entry < 16) break;
        if(memcmp(data + entry, "name", 4)) continue;
        start = BE32(data + entry + 8); length = BE32(data + entry + 12);
        if(start > size || length > size - start || length < 6) break;
        for(j = 0; j < BE16(data + start + 2); j++) {
            uint32_t record = 6 + j * 12, pos, n, k, used = 0;
            int score;
            if(record > length || length - record < 12) break;
            const uint8_t* name = data + start + record;
            if(BE16(name + 6) != 1 || (BE16(name) != 3 && BE16(name) != 0)) continue;
            if(BE16(name) == 3 && BE16(name + 2) != 1 && BE16(name + 2) != 10) continue;
            score = (BE16(name) == 3 ? 2 : 0) + (BE16(name + 4) == 0x409 ? 1 : 0);
            if(score <= best) continue;
            n = BE16(name + 8); pos = BE16(data + start + 4) + BE16(name + 10);
            if(n == 0 || (n & 1) || pos > length || n > length - pos) continue;
            for(k = 0; k < n; k += 2) {
                uint32_t cp = BE16(data + start + pos + k);
                if(!cp || (cp >= 0xd800 && cp <= 0xdfff)) { used = 0; break; }
                int bytes = cp < 0x80 ? 1 : cp < 0x800 ? 2 : 3;
                if(used + bytes >= capacity || used + bytes >= sizeof candidate) { used = 0; break; }
                if(bytes == 1) candidate[used++] = (char)cp;
                else if(bytes == 2) { candidate[used++] = (char)(0xc0 | (cp >> 6)); candidate[used++] = (char)(0x80 | (cp & 63)); }
                else { candidate[used++] = (char)(0xe0 | (cp >> 12)); candidate[used++] = (char)(0x80 | ((cp >> 6) & 63)); candidate[used++] = (char)(0x80 | (cp & 63)); }
            }
            if(used) { candidate[used] = 0; memcpy(utf8, candidate, used + 1); best = score; result = 1; }
        }
        break;
    }
done:
    BGI_Free(data);
    return result;
}

static int CompareEntries(const void* a, const void* b)
{
    return strcmp(((const OsFindData_t*)a)->name, ((const OsFindData_t*)b)->name);
}
static int IsFont(const char* name)
{
    const char* ext = strrchr(name, '.');
    char lower[5]; int i;
    if(!ext || strlen(ext) != 4) return 0;
    for(i = 0; i < 5; i++) lower[i] = ext[i] >= 'A' && ext[i] <= 'Z' ? ext[i] + 32 : ext[i];
    return !strcmp(lower, ".ttf") || !strcmp(lower, ".otf") || !strcmp(lower, ".ttc");
}

int FontFile_AutoLoad(const char* directory, char* chosen, size_t capacity)
{
    typedef struct { char path[260]; int depth; } Dir;
    Dir* queue = BGI_Alloc(4096 * sizeof *queue);
    int head = 0, tail = 1, loaded = 0;
    snprintf(queue[0].path, sizeof queue[0].path, "%s", directory);
    queue[0].depth = 0;
    while(head < tail && !loaded) {
        Dir current = queue[head++];
        char pattern[260], path[260];
        OsFindData_t entry, *entries = NULL;
        int count = 0, i;
        OsFind_t* find;
        if(snprintf(pattern, sizeof pattern, "%s/*", current.path) >= (int)sizeof pattern) continue;
        find = OS_FindFirst(pattern, &entry);
        if(!find) continue;
        do {
            if(!strcmp(entry.name, ".") || !strcmp(entry.name, "..")) continue;
            if(count % 64 == 0) {
                OsFindData_t* grown = BGI_Alloc((count + 64) * sizeof *entries);
                if(count) memcpy(grown, entries, count * sizeof *entries);
                BGI_Free(entries); entries = grown;
            }
            entries[count++] = entry;
        } while(OS_FindNext(find, &entry));
        OS_FindClose(find);
        if(count > 1) qsort(entries, count, sizeof *entries, CompareEntries);
        for(i = 0; i < count && !loaded; i++) {
            if(snprintf(path, sizeof path, "%s/%s", current.path, entries[i].name) >= (int)sizeof path) continue;
            if(entries[i].attrs & OS_ATTR_DIRECTORY) {
                if(tail < 4096 && current.depth < 16 && !(entries[i].attrs & 0x400)) {
                    strcpy(queue[tail].path, path); queue[tail++].depth = current.depth + 1;
                }
            } else if(IsFont(entries[i].name)) {
                char family[256];
                if(!FontFile_Family(path, family, sizeof family)) continue;
                if(OS_FontAddFile(path)) {
                    snprintf(chosen, capacity, "%s", path); loaded = 1;
#if defined(BGI_WIN32)
                    char native[256];
                    OS_Utf8ToSjis(family, native, sizeof native);
                    _putenv_s("OPENBGI_FONT_FACE", native);
#endif
                }
            }
        }
        BGI_Free(entries);
    }
    BGI_Free(queue);
    return loaded;
}
