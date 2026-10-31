#include <jni.h>
#include "sysconfig.h"
#include "sysdeps.h"
#include "target_main.h"
#include "options.h"
#include "traps.h"
#include "input.h"
#include "inputdevice.h"
#include "savestate.h"
#include "uae.h"
#include <algorithm>
#include <atomic>
#include <cstring>
#include <mutex>
#include <string>
#include <thread>
#include <vector>
#include <cstdlib>

extern "C" void winuae_android_set_surface(JNIEnv *, jobject);
extern "C" void winuae_android_request_quit(void);
extern "C" void winuae_android_reset(void);
extern void unix_input_mouse_motion(int dx, int dy);
extern void unix_input_mouse_button(int button, bool pressed);
extern void unix_input_keyboard_key(int scancode, bool pressed, int lockstate);

static std::thread emulator_thread;
static std::atomic<bool> running{false};
static std::mutex state_request_mutex;
static std::string state_request_path;
static int state_request_action;

extern "C" int winuae_android_take_state_request(TCHAR *path, int size)
{
    std::lock_guard<std::mutex> guard(state_request_mutex);
    if (!state_request_action || !path || size < 2) return 0;
    const size_t count = std::min(state_request_path.size(), static_cast<size_t>(size - 1));
    std::memcpy(path, state_request_path.data(), count);
    path[count] = 0;
    int action = state_request_action;
    state_request_action = 0;
    state_request_path.clear();
    return action;
}

static std::string java_string(JNIEnv *env, jstring value)
{
    if (!value) return {};
    const char *text = env->GetStringUTFChars(value, nullptr);
    std::string result = text ? text : "";
    if (text) env->ReleaseStringUTFChars(value, text);
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_com_buddyapps_winuae_MainActivity_nativeSurface(JNIEnv *env, jclass, jobject surface)
{ winuae_android_set_surface(env, surface); }

extern "C" JNIEXPORT jboolean JNICALL
Java_com_buddyapps_winuae_MainActivity_nativeStart(JNIEnv *env, jclass, jstring home, jstring rom, jstring disk)
{
    if (running.exchange(true)) return JNI_FALSE;
    const std::string home_path = java_string(env, home);
    const std::string rom_path = java_string(env, rom);
    const std::string disk_path = java_string(env, disk);
    if (rom_path.empty()) { running.store(false); return JNI_FALSE; }
    winuae_android_reset();
    // The video backend observes this flag between emulation frames.
    // A previous nativeStop has already joined the prior emulation thread.
    setenv("HOME", home_path.c_str(), 1);
    emulator_thread = std::thread([home_path, rom_path, disk_path]() {
        std::vector<std::string> args{"winuae", "-s", "use_gui=no", "-s", "kickstart_rom_file=" + rom_path};
        if (!disk_path.empty()) { args.emplace_back("-s"); args.emplace_back("floppy0=" + disk_path); }
        std::vector<char *> argv;
        for (auto &arg : args) argv.push_back(arg.data());
        argv.push_back(nullptr);
        target_main_set_args((int)args.size(), argv.data());
        real_main((int)args.size(), argv.data());
        target_main_exit();
        running.store(false);
    });
    return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_buddyapps_winuae_MainActivity_nativeStop(JNIEnv *, jclass)
{
    if (running.load()) winuae_android_request_quit();
    if (emulator_thread.joinable()) emulator_thread.join();
}
extern "C" JNIEXPORT void JNICALL
Java_com_buddyapps_winuae_MainActivity_nativeMouse(JNIEnv *, jclass, jint dx, jint dy, jboolean down)
{ unix_input_mouse_motion(dx, dy); unix_input_mouse_button(0, down == JNI_TRUE); }
extern "C" JNIEXPORT void JNICALL
Java_com_buddyapps_winuae_MainActivity_nativeKey(JNIEnv *, jclass, jint code, jboolean down)
{ unix_input_keyboard_key(code, down == JNI_TRUE, 0); }

extern "C" JNIEXPORT void JNICALL
Java_com_buddyapps_winuae_MainActivity_nativeJoystick(JNIEnv *, jclass, jint control, jboolean down)
{
    static const int port0[] = {
        INPUTEVENT_JOY1_LEFT, INPUTEVENT_JOY1_DOWN, INPUTEVENT_JOY1_UP,
        INPUTEVENT_JOY1_RIGHT, INPUTEVENT_JOY1_FIRE_BUTTON
    };
    static const int port1[] = {
        INPUTEVENT_JOY2_LEFT, INPUTEVENT_JOY2_DOWN, INPUTEVENT_JOY2_UP,
        INPUTEVENT_JOY2_RIGHT, INPUTEVENT_JOY2_FIRE_BUTTON
    };
    if (control < 0 || control >= 5) return;
    int pressed = down == JNI_TRUE ? 1 : 0;
    send_input_event(port0[control], pressed, 1, 0);
    send_input_event(port1[control], pressed, 1, 0);
}

extern "C" JNIEXPORT void JNICALL
Java_com_buddyapps_winuae_MainActivity_nativeWarp(JNIEnv *, jclass, jboolean down)
{
    if (!running.load()) return;
    send_input_event(INPUTEVENT_SPC_WARP, down == JNI_TRUE ? 1 : 0, 1, 0);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_buddyapps_winuae_MainActivity_nativeState(JNIEnv *env, jclass, jstring path, jboolean load)
{
    if (!running.load()) return JNI_FALSE;
    const std::string filename = java_string(env, path);
    if (filename.empty() || filename.size() >= MAX_DPATH) return JNI_FALSE;
    std::lock_guard<std::mutex> guard(state_request_mutex);
    if (state_request_action) return JNI_FALSE;
    state_request_path = filename;
    state_request_action = load == JNI_TRUE ? STATE_DORESTORE : STATE_SAVE;
    return JNI_TRUE;
}
