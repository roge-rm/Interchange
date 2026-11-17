// Drives the game's synth offline on the desktop: scenes and one-shots in, stereo samples out.
// Shared by the renderer and the host tests. Not built into the app.
#pragma once

#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <functional>
#include <string>
#include <vector>

#include "synth/synth.h"

namespace host {

using namespace interchange;

constexpr float kRate = 48000.0f;
/** How often a driver may change the scene, in seconds. The game sends one every 200 ms. */
constexpr float kStep = 0.05f;

/** One held sound for a scene, with its recipe parameters and gain, pan and Doppler. */
inline SceneEntry held(int key, int r, std::initializer_list<float> params, float gain = 0.6f, float pan = 0, float pitch = 0, int flags = 0) {
    SceneEntry e;
    e.key = key;
    e.recipe = r;
    e.flags = flags;
    int i = 0;
    for (float p : params) e.p[i++] = p;
    e.p[P_GAIN] = gain;
    e.p[P_PAN] = pan;
    e.p[P_PITCH] = pitch;
    return e;
}

inline Event shot(int r, std::initializer_list<float> params, float gain = 0.6f, float delay = 0, int flags = 0) {
    Event e;
    e.recipe = r;
    e.flags = flags;
    e.delay = delay;
    int i = 0;
    for (float p : params) e.p[i++] = p;
    e.p[P_GAIN] = gain;
    return e;
}

/** Sets the scene at time t: fills [scene] and pushes any one-shots due. */
using Driver = std::function<void(float t, Scene& scene, Synth& synth)>;

/** [seconds] of stereo at 48 kHz, interleaved, from [drive] called every [kStep]. */
inline std::vector<float> run(float seconds, const Driver& drive, int budget = 32, bool limiting = true) {
    Synth synth(kRate, budget);
    synth.limiting = limiting;
    int total = static_cast<int>(seconds * kRate);
    int block = static_cast<int>(kStep * kRate);
    std::vector<float> out(static_cast<size_t>(total) * 2);
    Scene scene;
    for (int at = 0; at < total; at += block) {
        scene.count = 0;
        drive(at / kRate, scene, synth);
        synth.publishScene(scene);
        int n = std::min(block, total - at);
        synth.render(out.data() + static_cast<size_t>(at) * 2, n);
    }
    return out;
}

inline float rmsDb(const std::vector<float>& s, size_t from = 0, size_t to = 0) {
    if (to == 0 || to > s.size()) to = s.size();
    double sum = 0;
    for (size_t i = from; i < to; ++i) sum += double(s[i]) * s[i];
    double rms = std::sqrt(sum / std::max<size_t>(1, to - from));
    return rms > 0 ? static_cast<float>(20.0 * std::log10(rms)) : -200.0f;
}

inline float peak(const std::vector<float>& s) {
    float p = 0;
    for (float x : s) p = std::max(p, std::fabs(x));
    return p;
}

/** True if every sample is a real number. A bit test, since fast-math can drop isfinite. */
inline bool allFinite(const std::vector<float>& s) {
    for (float x : s) {
        uint32_t bits;
        std::memcpy(&bits, &x, 4);
        if ((bits & 0x7f800000u) == 0x7f800000u) return false;
    }
    return true;
}

/** 16-bit stereo WAV at 48 kHz. */
inline bool writeWav(const std::string& path, const std::vector<float>& s) {
    FILE* f = std::fopen(path.c_str(), "wb");
    if (!f) return false;
    uint32_t data = static_cast<uint32_t>(s.size() * 2);
    auto u32 = [f](uint32_t v) { std::fwrite(&v, 4, 1, f); };
    auto u16 = [f](uint16_t v) { std::fwrite(&v, 2, 1, f); };
    std::fwrite("RIFF", 1, 4, f); u32(36 + data); std::fwrite("WAVE", 1, 4, f);
    std::fwrite("fmt ", 1, 4, f); u32(16); u16(1); u16(2); u32(48000); u32(48000 * 4); u16(4); u16(16);
    std::fwrite("data", 1, 4, f); u32(data);
    std::vector<int16_t> pcm(s.size());
    for (size_t i = 0; i < s.size(); ++i) pcm[i] = static_cast<int16_t>(std::lround(std::max(-1.0f, std::min(1.0f, s[i])) * 32767.0f));
    std::fwrite(pcm.data(), 2, pcm.size(), f);
    std::fclose(f);
    return true;
}

/**
 * A scene script, as SoundScenesTest writes it from WorldSound's frames. Each frame starts with
 * "F seconds", then "H key recipe flags p0..p8" per held sound and "S recipe flags delay p0..p8"
 * per one-shot.
 */
struct Frame {
    float time = 0;
    Scene scene;
    std::vector<Event> shots;
};

inline std::vector<Frame> readScript(const std::string& path) {
    std::vector<Frame> frames;
    FILE* f = std::fopen(path.c_str(), "r");
    if (!f) return frames;
    char line[1024];
    while (std::fgets(line, sizeof line, f)) {
        if (line[0] == 'F') {
            frames.emplace_back();
            std::sscanf(line + 1, "%f", &frames.back().time);
        } else if (line[0] == 'H' && !frames.empty()) {
            Scene& sc = frames.back().scene;
            if (sc.count >= kMaxSceneEntries) continue;
            SceneEntry& e = sc.entries[sc.count++];
            float* p = e.p;
            std::sscanf(line + 1, "%d %d %d %f %f %f %f %f %f %f %f %f", &e.key, &e.recipe, &e.flags, &p[0], &p[1], &p[2], &p[3], &p[4], &p[5], &p[6], &p[7], &p[8]);
        } else if (line[0] == 'S' && !frames.empty()) {
            Event e;
            float* p = e.p;
            std::sscanf(line + 1, "%d %d %f %f %f %f %f %f %f %f %f %f", &e.recipe, &e.flags, &e.delay, &p[0], &p[1], &p[2], &p[3], &p[4], &p[5], &p[6], &p[7], &p[8]);
            frames.back().shots.push_back(e);
        }
    }
    std::fclose(f);
    return frames;
}

/** Plays [frames] through the synth, each held until the next, with [tail] seconds after the last. */
inline std::vector<float> play(const std::vector<Frame>& frames, float tail = 2.0f, int budget = 32) {
    std::vector<float> out;
    if (frames.empty()) return out;
    Synth synth(kRate, budget);
    float end = frames.back().time + tail;
    out.resize(static_cast<size_t>(end * kRate) * 2);
    size_t at = 0;
    for (size_t i = 0; i < frames.size(); ++i) {
        synth.publishScene(frames[i].scene);
        for (const Event& e : frames[i].shots) synth.pushEvent(e);
        float until = i + 1 < frames.size() ? frames[i + 1].time : end;
        size_t stop = std::min(out.size() / 2, static_cast<size_t>(until * kRate));
        while (at < stop) {
            int n = static_cast<int>(std::min<size_t>(256, stop - at));
            synth.render(out.data() + at * 2, n);
            at += n;
        }
    }
    return out;
}

}  // namespace host
