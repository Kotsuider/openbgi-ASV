/* SPDX-License-Identifier: GPL-2.0-only */
#include <jni.h>
#include <android/log.h>
#include <android/bitmap.h>
#include <pthread.h>
#include <time.h>
#include "bgi/engine.h"
#include "bgi/version.h"
#include "bgi/os.h"
#include "bgi/os_wasm.h"
#include <unistd.h>
#include <errno.h>
#include <stdarg.h>

static JNIEnv* env;
static jobject activity;
static jclass activityClass;

// Callbacks run on the engine thread. The UI thread queues touch input.
static pthread_mutex_t inputLock = PTHREAD_MUTEX_INITIALIZER;
typedef struct { int type, x, y, down, button; } Input;
static Input input[128];
static unsigned readInput, writeInput;
static int closeRequested;

#define METHOD(name, sig) (*env)->GetMethodID(env, activityClass, name, sig)

static void HostLog(int priority, const char* format, ...)
{
	va_list args;
	va_start(args, format);
	__android_log_vprint(priority, "OpenBGI", format, args);
	va_end(args);
	va_start(args, format);
	vfprintf(stderr, format, args);
	va_end(args);
	fputc('\n', stderr);
	fflush(stderr);
}

JNIEXPORT void JNICALL Java_org_openbgi_MainActivity_input(JNIEnv* jni, jobject obj,
	jint type, jint x, jint y, jint down, jint button)
{
	(void)jni;
	(void)obj;
	pthread_mutex_lock(&inputLock);
	if(type == WASM_EV_CLOSE)
		closeRequested = 1;
	else
	{
		unsigned next = (writeInput + 1) % BGI_COUNTOF(input);
		if(next != readInput)
		{
			input[writeInput] = (Input){type, x, y, down, button};
			writeInput = next;
		}
	}
	pthread_mutex_unlock(&inputLock);
}

void WasmJs_YieldFast(void)
{
	pthread_mutex_lock(&inputLock);
	if(closeRequested)
	{
		OsWasm_PushEvent(WASM_EV_CLOSE, 0, 0, 0, 0);
		closeRequested = 0;
	}
	while(readInput != writeInput)
	{
		Input event = input[readInput];
		readInput = (readInput + 1) % BGI_COUNTOF(input);
		if(event.type == WASM_EV_BUTTON)
			OsWasm_PushEvent(event.type, event.button, event.down, event.x, event.y);
		else
			OsWasm_PushEvent(event.type, event.x, event.y, 0, 0);
	}
	pthread_mutex_unlock(&inputLock);
}

double emscripten_get_now(void)
{
	struct timespec ts;
	clock_gettime(CLOCK_MONOTONIC, &ts);
	return ts.tv_sec * 1000.0 + ts.tv_nsec / 1000000.0;
}

void emscripten_sleep(unsigned ms)
{
	struct timespec delay = {ms / 1000u, (ms % 1000u) * 1000000L};
	nanosleep(&delay, NULL);
	WasmJs_YieldFast();
}

void WasmHostJs_SjisTable(uint32_t* table)
{
	jintArray data = (*env)->CallObjectMethod(env, activity, METHOD("sjisTable", "()[I"));
	if(data)
	{
		(*env)->GetIntArrayRegion(env, data, 0, 0x10000, (jint*)table);
		(*env)->DeleteLocalRef(env, data);
	}
}

void WasmJs_CanvasSetup(int w, int h, int visible, int fullscreen)
{
	(void)fullscreen;
	(*env)->CallVoidMethod(env, activity, METHOD("canvasSetup", "(IIZ)V"), w, h, (jboolean)!!visible);
}

JNIEXPORT jboolean JNICALL Java_org_openbgi_MainActivity_copyFrame(JNIEnv* jni, jclass cls,
	jobject bitmap, jobject rgba, jint width, jint height, jint x, jint y)
{
	AndroidBitmapInfo info;
	const uint8_t* source = (*jni)->GetDirectBufferAddress(jni, rgba);
	void* pixels;
	int left, right, top, bottom;
	(void)cls;
	if(!source || width <= 0 || height <= 0 ||
		AndroidBitmap_getInfo(jni, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS ||
		info.format != ANDROID_BITMAP_FORMAT_RGBA_8888)
		return JNI_FALSE;

	left = x > 0 ? x : 0;
	top = y > 0 ? y : 0;
	right = x + width < (int)info.width ? x + width : (int)info.width;
	bottom = y + height < (int)info.height ? y + height : (int)info.height;
	if(left >= right || top >= bottom ||
		AndroidBitmap_lockPixels(jni, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS)
		return JNI_FALSE;

	// The engine emits opaque RGBA, which matches the Android bitmap layout.
	for(int row = top; row < bottom; ++row)
	{
		uint8_t* dst = (uint8_t*)pixels + (size_t)row * info.stride + (size_t)left * 4;
		const uint8_t* src = source + ((size_t)(row - y) * width + (left - x)) * 4;
		memcpy(dst, src, (size_t)(right - left) * 4);
	}
	AndroidBitmap_unlockPixels(jni, bitmap);
	return JNI_TRUE;
}

void WasmJs_Present(const void* rgba, int w, int h, int x, int y)
{
	static unsigned count;
	static double totalMs, maxMs;
	double start = emscripten_get_now();
	jobject data = (*env)->NewDirectByteBuffer(env, (void*)rgba, (jlong)w * h * 4);
	if(data)
	{
		(*env)->CallVoidMethod(env, activity, METHOD("present", "(Ljava/nio/ByteBuffer;IIII)V"), data, w, h, x, y);
		(*env)->DeleteLocalRef(env, data);
	}
	double elapsed = emscripten_get_now() - start;
	totalMs += elapsed;
	if(elapsed > maxMs)
		maxMs = elapsed;
	if(++count == 120)
	{
		HostLog(ANDROID_LOG_DEBUG, "Present upload: %.2f ms mean, %.2f ms max", totalMs / count, maxMs);
		count = 0;
		totalMs = maxMs = 0;
	}
}

void WasmJs_FillBlack(void)
{
	(*env)->CallVoidMethod(env, activity, METHOD("fillBlack", "()V"));
}

int Android_MovieFileOpen(const char* path)
{
	char relative[0x800];
	OsWasm_RelPath(path, relative, sizeof relative);
	jstring name = (*env)->NewStringUTF(env, relative);
	if(!name) return -1;
	int fd = (*env)->CallIntMethod(env, activity, METHOD("documentOpen", "(Ljava/lang/String;)I"), name);
	(*env)->DeleteLocalRef(env, name);
	return fd;
}

int Android_MovieOpen(const char* path)
{
	jstring name = (*env)->NewStringUTF(env, path);
	int result = (*env)->CallBooleanMethod(env, activity, METHOD("movieOpen", "(Ljava/lang/String;)Z"), name);
	(*env)->DeleteLocalRef(env, name);
	if(result) HostLog(ANDROID_LOG_INFO, "Android MediaPlayer opened %s", path);
	return result;
}
int Android_MovieStart(int x, int y, int w, int h, int volume) {
	return (*env)->CallIntMethod(env, activity, METHOD("movieStart", "(IIIII)I"), x, y, w, h, volume);
}
void Android_MovieStop(void) { (*env)->CallVoidMethod(env, activity, METHOD("movieStop", "()V")); }
int Android_MovieRunning(void) { return (*env)->CallBooleanMethod(env, activity, METHOD("movieRunning", "()Z")); }
void Android_MovieVolume(int volume) { (*env)->CallVoidMethod(env, activity, METHOD("movieVolume", "(I)V"), volume); }

int WasmJs_FontAddFile(const char* path)
{
	jstring name = (*env)->NewStringUTF(env, path);
	if(!name)
		return 0;
	int loaded = (*env)->CallBooleanMethod(env, activity, METHOD("fontAddFile", "(Ljava/lang/String;)Z"), name);
	(*env)->DeleteLocalRef(env, name);
	return loaded;
}

int WasmJs_FontOpen(const char* face, int height, int width, int bold, int italic)
{
	(void)face;
	const char* override = getenv("OPENBGI_FONT_FACE");
	const char* weightText = getenv("OPENBGI_FONT_WEIGHT");
	int weight = weightText ? atoi(weightText) : (bold ? 700 : 400);
	if(weight < 1 || weight > 1000)
		weight = bold ? 700 : 400;
	jstring family = override && *override ? (*env)->NewStringUTF(env, override) : NULL;
	int id = (*env)->CallIntMethod(env, activity, METHOD("fontOpen", "(Ljava/lang/String;IIII)I"),
		family, height, width, weight, italic);
	if(family)
		(*env)->DeleteLocalRef(env, family);
	return id;
}

int WasmJs_FontDecode(int value, int codepage)
{
	return (*env)->CallIntMethod(env, activity, METHOD("fontDecode", "(II)I"), value, codepage);
}

int WasmJs_FontAdvance(int id, int cp)
{
	return (*env)->CallIntMethod(env, activity, METHOD("fontAdvance", "(II)I"), id, cp);
}

void WasmJs_FontRender(int id, int cp, unsigned char* pixels, int pitch, int w, int h, int mono)
{
	jobject data = (*env)->NewDirectByteBuffer(env, pixels, (jlong)pitch * h);
	if(data)
	{
		(*env)->CallVoidMethod(env, activity, METHOD("fontRender", "(IILjava/nio/ByteBuffer;IIII)V"),
			id, cp, data, pitch, w, h, mono);
		(*env)->DeleteLocalRef(env, data);
	}
}

void WasmJs_SetTitle(const char* text) { HostLog(ANDROID_LOG_INFO, "%s", text); }
int WasmJs_ScreenW(void) { return 1920; }
int WasmJs_ScreenH(void) { return 1080; }
void WasmJs_SetCursor(int visible) { (void)visible; }
int WasmJs_Confirm(const char* text, const char* caption, int kind)
{
	HostLog(ANDROID_LOG_WARN, "%s: %s", caption, text);
	return kind ? 0 : 1;
}
int WasmJs_Prompt(const char* title, const char* initial, char* out, int n)
{
	(void)title; (void)initial; (void)out; (void)n;
	return 0;
}
void WasmJs_ClipboardWrite(const char* text) { (void)text; }
void WasmJs_InstallInput(void) {}
void WasmJs_OpenUrl(const char* url) { (void)url; }
double WasmJs_DeviceMemoryBytes(void) { return 0; }
void WasmJs_Ended(int code) { HostLog(ANDROID_LOG_INFO, "Engine exit: %d", code); }
int WasmJs_AudioAvailable(void) { return 1; }
int WasmJs_AudioStart(int rate)
{
	return (*env)->CallBooleanMethod(env, activity, METHOD("audioStart", "(I)Z"), rate);
}
void WasmJs_AudioStop(void)
{
	(*env)->CallVoidMethod(env, activity, METHOD("audioStop", "()V"));
}
int WasmJs_AudioWanted(void)
{
	return (*env)->CallIntMethod(env, activity, METHOD("audioWanted", "()I"));
}
void WasmJs_AudioPush(const int16_t* pcm, int frames)
{
	jobject data = (*env)->NewDirectByteBuffer(env, (void*)pcm, (jlong)frames * 4);
	if(data)
	{
		(*env)->CallVoidMethod(env, activity, METHOD("audioPush", "(Ljava/nio/ByteBuffer;)V"), data);
		(*env)->DeleteLocalRef(env, data);
	}
}

JNIEXPORT jobjectArray JNICALL Java_org_openbgi_LauncherActivity_engineProfiles(JNIEnv* jni, jclass cls)
{
	int count = Engine_ProfileCount();
	jclass stringClass = (*jni)->FindClass(jni, "java/lang/String");
	(void)cls;
	if(!stringClass)
		return NULL;
	jobjectArray names = (*jni)->NewObjectArray(jni, count, stringClass, NULL);
	for(int i = 0; names && i < count; ++i)
	{
		jstring name = (*jni)->NewStringUTF(jni, Engine_ProfileAt(i)->name);
		if(!name)
			break;
		(*jni)->SetObjectArrayElement(jni, names, i, name);
		(*jni)->DeleteLocalRef(jni, name);
	}
	return names;
}

static jbyteArray directoryListing;

int WasmHostJs_Connect(void) { return 1; }
void WasmHostJs_OverlayMount(const char* dir) { (void)dir; }
void WasmHostJs_OverlayChanged(void) {}

int WasmHostJs_Stat(const char* path, uint32_t* out)
{
	jstring name = (*env)->NewStringUTF(env, path);
	if(!name)
		return -1;
	jintArray data = (*env)->CallObjectMethod(env, activity, METHOD("documentStat", "(Ljava/lang/String;)[I"), name);
	(*env)->DeleteLocalRef(env, name);
	if(!data)
		return -1;
	(*env)->GetIntArrayRegion(env, data, 0, 5, (jint*)out);
	(*env)->DeleteLocalRef(env, data);
	return 0;
}

int WasmHostJs_Read(const char* path, uint32_t lo, uint32_t hi, uint32_t len, void* dst)
{
	jstring name = (*env)->NewStringUTF(env, path);
	if(!name)
		return -1;
	int fd = (*env)->CallIntMethod(env, activity, METHOD("documentOpen", "(Ljava/lang/String;)I"), name);
	(*env)->DeleteLocalRef(env, name);
	if(fd < 0)
		return -1;
	ssize_t count;
	do
		count = pread(fd, dst, len, (off_t)(((uint64_t)hi << 32) | lo));
	while(count < 0 && errno == EINTR);
	close(fd);
	return (int)count;
}

int WasmHostJs_ListSize(const char* path)
{
	if(directoryListing)
		(*env)->DeleteLocalRef(env, directoryListing);
	directoryListing = NULL;
	jstring name = (*env)->NewStringUTF(env, path);
	if(!name)
		return -1;
	directoryListing = (*env)->CallObjectMethod(env, activity, METHOD("documentList", "(Ljava/lang/String;)[B"), name);
	(*env)->DeleteLocalRef(env, name);
	return directoryListing ? (*env)->GetArrayLength(env, directoryListing) : -1;
}

void WasmHostJs_ListTake(void* dst)
{
	if(directoryListing)
	{
		(*env)->GetByteArrayRegion(env, directoryListing, 0, (*env)->GetArrayLength(env, directoryListing), dst);
		(*env)->DeleteLocalRef(env, directoryListing);
		directoryListing = NULL;
	}
}

JNIEXPORT jint JNICALL Java_org_openbgi_MainActivity_runEngine(JNIEnv* jni, jobject obj,
	jstring overlay, jstring profile, jstring arguments, jstring logPath)
{
	const char* saves = (*jni)->GetStringUTFChars(jni, overlay, NULL);
	const char* engine = (*jni)->GetStringUTFChars(jni, profile, NULL);
	const char* args = (*jni)->GetStringUTFChars(jni, arguments, NULL);
	const char* path = (*jni)->GetStringUTFChars(jni, logPath, NULL);
	if(path)
	{
		if(freopen(path, "a", stdout))
			setvbuf(stdout, NULL, _IONBF, 0);
		if(freopen(path, "a", stderr))
			setvbuf(stderr, NULL, _IONBF, 0);
		(*jni)->ReleaseStringUTFChars(jni, logPath, path);
	}
	int result = -1;
	if(saves && engine && args && Engine_SelectProfile(engine))
	{
		env = jni;
		activity = obj;
		activityClass = (*jni)->GetObjectClass(jni, obj);
		OsWasm_SetOverlayRoot(saves);
		if(OS_Init())
		{
			size_t length = strlen(engine) + strlen(args) + sizeof("--engine= ");
			char* options = malloc(length);
			if(options)
			{
				snprintf(options, length, "--engine=%s %s", engine, args);
				result = Engine_Main(options);
				free(options);
			}
			OS_Shutdown();
		}
	}
	if(args)
		(*jni)->ReleaseStringUTFChars(jni, arguments, args);
	if(engine)
		(*jni)->ReleaseStringUTFChars(jni, profile, engine);
	if(saves)
		(*jni)->ReleaseStringUTFChars(jni, overlay, saves);
	return result;
}
