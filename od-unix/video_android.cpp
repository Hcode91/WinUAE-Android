#include "sysconfig.h"
#include "sysdeps.h"
#include "video.h"

#include <jni.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <atomic>
#include <mutex>

static ANativeWindow *android_window;
static std::mutex window_mutex;
static std::atomic<bool> android_quit{false};

extern "C" void winuae_android_set_surface(JNIEnv *env, jobject surface)
{
    ANativeWindow *next = env && surface ? ANativeWindow_fromSurface(env, surface) : nullptr;
    std::lock_guard<std::mutex> lock(window_mutex);
    if (android_window) ANativeWindow_release(android_window);
    android_window = next;
    if (android_window) ANativeWindow_setBuffersGeometry(android_window, 0, 0, WINDOW_FORMAT_RGBA_8888);
}

extern "C" void winuae_android_request_quit(void) { android_quit.store(true); }
extern "C" void winuae_android_reset(void) { android_quit.store(false); }

bool unix_video_setup(void) { return true; }
bool unix_video_init(int, int, int) { return true; }
void unix_video_shutdown(void) { winuae_android_set_surface(nullptr, nullptr); }
int unix_video_poll(bool *quit_requested)
{
    if (quit_requested) *quit_requested = android_quit.load();
    return 0;
}
int unix_video_poll_window_events(bool *quit_requested) { return unix_video_poll(quit_requested); }
void unix_video_present(const struct unix_video_frame *frame)
{
    if (!frame || !frame->pixels || frame->width <= 0 || frame->height <= 0 || frame->pixbytes != 4) return;
    std::lock_guard<std::mutex> lock(window_mutex);
    if (!android_window) return;
    ANativeWindow_Buffer buffer;
    if (ANativeWindow_lock(android_window, &buffer, nullptr) != 0) return;
    const int w = frame->width < buffer.width ? frame->width : buffer.width;
    const int h = frame->height < buffer.height ? frame->height : buffer.height;
    const int ox = (buffer.width - w) / 2, oy = (buffer.height - h) / 2;
    for (int y = 0; y < h; ++y) {
        const auto *src = reinterpret_cast<const uae_u32 *>(frame->pixels + y * frame->rowbytes);
        auto *dst = reinterpret_cast<uae_u32 *>(buffer.bits) + (y + oy) * buffer.stride + ox;
        for (int x = 0; x < w; ++x) {
            const uae_u32 p = src[x];
            dst[x] = 0xff000000u | (p & 0x00ffffffu);
        }
    }
    ANativeWindow_unlockAndPost(android_window);
}
void unix_video_set_title(const TCHAR *) {}
bool unix_video_set_window_mode(enum unix_video_window_mode, int, int, int, int) { return true; }
enum unix_video_window_mode unix_video_get_window_mode(void) { return UNIX_VIDEO_FULLSCREEN; }
float unix_video_get_display_refresh_rate(int) { return 60.0f; }
int unix_video_get_display_modes(int, struct unix_video_display_mode *, int) { return 0; }
void unix_video_get_desktop(int *dw,int *dh,int *x,int *y,int *w,int *h)
{ if(dw)*dw=640;if(dh)*dh=480;if(x)*x=0;if(y)*y=0;if(w)*w=640;if(h)*h=480; }
void unix_video_set_mouse_grab(bool) {}
bool unix_video_get_mouse_grab(void) { return false; }
void unix_video_toggle_mouse_grab(void) {}
int target_get_display(const TCHAR *) { return -1; }
const TCHAR *target_get_display_name(int, bool) { return nullptr; }
