#include <jni.h>
#include <android/log.h>
#include <atomic>
#include <chrono>
#include <memory>
#include <mutex>
#include <optional>
#include <string>
#include <thread>
#include <utility>
#include "utf8.h"

#define DISCORDPP_IMPLEMENTATION
#include "discordpp.h"

namespace {
std::mutex client_mutex;
std::shared_ptr<discordpp::Client> client;

struct RpcRequest {
    std::atomic<bool> done{false};
    bool successful = false;
    std::string failure;
};

std::string ToString(JNIEnv* env, jstring value) {
    if (value == nullptr) return {};
    const auto length = env->GetStringLength(value);
    const jchar* chars = env->GetStringChars(value, nullptr);
    if (chars == nullptr) return {};
    std::string result = chudpresence::Utf16ToUtf8(chars, static_cast<std::size_t>(length));
    env->ReleaseStringChars(value, chars);
    return result;
}

jstring Result(JNIEnv* env, const std::string& value) {
    return env->NewStringUTF(value.c_str());
}

bool WaitFor(const std::atomic<bool>& done) {
    const auto deadline = std::chrono::steady_clock::now() + std::chrono::seconds(12);
    while (!done.load() && std::chrono::steady_clock::now() < deadline) {
        discordpp::RunCallbacks();
        std::this_thread::sleep_for(std::chrono::milliseconds(10));
    }
    return done.load();
}

void RunCallbacksFor(std::chrono::milliseconds duration) {
    const auto deadline = std::chrono::steady_clock::now() + duration;
    while (std::chrono::steady_clock::now() < deadline) {
        discordpp::RunCallbacks();
        std::this_thread::sleep_for(std::chrono::milliseconds(10));
    }
}
}

extern "C" JNIEXPORT jstring JNICALL
Java_dev_chudpresence_mobile_reporting_LocalDiscordPresence_nativeInitialize(
    JNIEnv* env, jobject, jlong application_id) {
    std::lock_guard<std::mutex> lock(client_mutex);
    try {
        client = std::make_shared<discordpp::Client>();
        client->SetApplicationId(static_cast<uint64_t>(application_id));
        return Result(env, "ready");
    } catch (const std::exception& error) {
        client.reset();
        return Result(env, error.what());
    } catch (...) {
        client.reset();
        return Result(env, "Discord SDK initialization failed");
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_dev_chudpresence_mobile_reporting_LocalDiscordPresence_nativeUpdate(
    JNIEnv* env, jobject, jstring service_name, jstring title, jstring artist,
    jstring album, jstring artwork_url, jstring details_url, jstring state_url, jstring artwork_click_url,
    jstring button1_label, jstring button1_url, jstring button2_label, jstring button2_url,
    jint activity_type, jint status_display,
    jlong timestamp_start, jlong timestamp_end) {
    std::lock_guard<std::mutex> lock(client_mutex);
    if (!client) return Result(env, "Discord SDK is not initialized");

    try {
        discordpp::Activity activity;
        activity.SetName(ToString(env, service_name));
        activity.SetType(activity_type == 1 ? discordpp::ActivityTypes::Watching
            : activity_type == 0 ? discordpp::ActivityTypes::Listening
                                 : discordpp::ActivityTypes::Playing);
        activity.SetDetails(ToString(env, title));
        const auto details_link = ToString(env, details_url);
        if (!details_link.empty()) activity.SetDetailsUrl(details_link);
        const auto state = ToString(env, artist);
        if (!state.empty()) activity.SetState(state);
        const auto state_link = ToString(env, state_url);
        if (!state_link.empty()) activity.SetStateUrl(state_link);
        activity.SetStatusDisplayType(
            status_display == 1 ? discordpp::StatusDisplayTypes::Details
            : status_display == 2 ? discordpp::StatusDisplayTypes::State
                                  : discordpp::StatusDisplayTypes::Name);

        constexpr jlong max_discord_timestamp = 8640000000000000LL;
        const bool valid_start = timestamp_start >= 0 && timestamp_start <= max_discord_timestamp;
        const bool valid_end = timestamp_end >= 0 && timestamp_end <= max_discord_timestamp;
        if (valid_start || valid_end) {
            discordpp::ActivityTimestamps timestamps;
            if (valid_start) timestamps.SetStart(static_cast<uint64_t>(timestamp_start));
            if (valid_end) timestamps.SetEnd(static_cast<uint64_t>(timestamp_end));
            activity.SetTimestamps(timestamps);
        }

        const auto album_text = ToString(env, album);
        const auto artwork = ToString(env, artwork_url);
        if (!artwork.empty() || !album_text.empty()) {
            discordpp::ActivityAssets assets;
            if (!artwork.empty()) assets.SetLargeImage(artwork);
            if (!album_text.empty()) assets.SetLargeText(album_text);
            const auto artwork_link = ToString(env, artwork_click_url);
            if (!artwork_link.empty()) assets.SetLargeUrl(artwork_link);
            activity.SetAssets(assets);
        }

        auto add_button = [&](jstring label_value, jstring url_value) {
            const auto label = ToString(env, label_value);
            const auto url = ToString(env, url_value);
            if (label.empty() || url.empty()) return;
            discordpp::ActivityButton button;
            button.SetLabel(label);
            button.SetUrl(url);
            activity.AddButton(std::move(button));
        };
        add_button(button1_label, button1_url);
        add_button(button2_label, button2_url);

        // The callback may arrive after the timeout returns. Never capture stack locals that
        // would already be destroyed when the SDK services a late response during a retry.
        auto request = std::make_shared<RpcRequest>();
        client->UpdateRichPresence(std::move(activity), [request](discordpp::ClientResult result) {
            request->successful = result.Successful();
            if (!request->successful) request->failure = result.Error();
            request->done = true;
        });
        if (!WaitFor(request->done)) {
            __android_log_print(ANDROID_LOG_WARN, "ChudPresenceRpc", "Local RPC request timed out; reconnecting on the next attempt");
            return Result(env, "Discord did not answer the local RPC request");
        }
        return Result(env, request->successful ? "published" : (request->failure.empty() ? "Local Discord RPC failed" : request->failure));
    } catch (const std::exception& error) {
        return Result(env, error.what());
    } catch (...) {
        return Result(env, "Local Discord RPC failed");
    }
}

extern "C" JNIEXPORT void JNICALL
Java_dev_chudpresence_mobile_reporting_LocalDiscordPresence_nativeClear(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(client_mutex);
    if (!client) return;
    client->ClearRichPresence();
    // ClearRichPresence has no completion callback. Keep servicing the Android RPC
    // transport long enough for Discord to consume the clear before the client is dropped.
    RunCallbacksFor(std::chrono::seconds(2));
}

extern "C" JNIEXPORT void JNICALL
Java_dev_chudpresence_mobile_reporting_LocalDiscordPresence_nativeShutdown(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(client_mutex);
    client.reset();
}
