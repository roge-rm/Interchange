// Renders the game's sounds to WAV files through the real synth, for listening to.
//   render sounds DIR         one WAV per sound added or changed in S7a, with levels printed
//   render scene SCRIPT OUT   a scene script from SoundScenesTest, played through the synth
//   render cpu                how long a busy scene takes against real time
#include <chrono>
#include <cstdio>
#include <string>

#include "host.h"

using namespace host;

namespace {

struct Clip {
    const char* name;
    float seconds;
    Driver drive;
};

/** 0 to 1 over the first third, held, then back to a crawl over the last third. */
float ramp(float t, float seconds) {
    float third = seconds / 3.0f;
    if (t < third) return t / third;
    if (t < 2 * third) return 1.0f;
    return std::max(0.05f, 1.0f - (t - 2 * third) / third);
}

Driver train(int kind) {
    return [kind](float t, Scene& sc, Synth&) {
        float speed = ramp(t, 12.0f);
        sc.entries[sc.count++] = held(1, recipe::TRAIN, {1, float(kind), speed}, 0.6f, 0, 1.0f + 0.35f * speed);
    };
}

Driver ship(int kind) {
    return [kind](float t, Scene& sc, Synth&) {
        float speed = ramp(t, 12.0f);
        sc.entries[sc.count++] = held(1, recipe::OUTBOARD, {1, 0.15f + 0.85f * speed, float(kind)}, 0.6f * (0.3f + 0.7f * speed));
    };
}

Driver steady(int r, std::initializer_list<float> params, float gain = 0.6f) {
    std::vector<float> p(params);
    return [r, p, gain](float, Scene& sc, Synth&) {
        SceneEntry e = held(1, r, {}, gain);
        for (size_t i = 0; i < p.size(); ++i) e.p[i] = p[i];
        sc.entries[sc.count++] = e;
    };
}

/** One-shots at the given times, each [params] a list. */
Driver shots(std::vector<std::pair<float, Event>> list) {
    return [list](float t, Scene&, Synth& synth) {
        for (const auto& [at, e] : list) if (at >= t && at < t + kStep) synth.pushEvent(e);
    };
}

std::vector<Clip> clips() {
    std::vector<Clip> c;
    const char* trains[] = {"train_steam", "train_diesel", "train_electric", "train_battery_hydrogen", "train_maglev", "train_hyperloop"};
    for (int k = 0; k < 6; ++k) c.push_back({trains[k], 12.0f, train(k)});
    const char* ships[] = {"ship_motor", "ship_steam", "ship_sail", "ship_battery_hydrogen", "ship_hovercraft"};
    for (int k = 0; k < 5; ++k) c.push_back({ships[k], 12.0f, ship(k)});
    c.push_back({"helicopter", 10.0f, [](float t, Scene& sc, Synth&) {
        float s = ramp(t, 10.0f);
        sc.entries[sc.count++] = held(1, recipe::HELI, {1, 0.25f + 0.75f * s}, 0.7f * (0.35f + 0.65f * s));
    }});
    c.push_back({"drone", 8.0f, [](float t, Scene& sc, Synth&) {
        float s = ramp(t, 8.0f);
        sc.entries[sc.count++] = held(1, recipe::DRONE, {1, 0.25f + 0.75f * s}, 0.7f * (0.35f + 0.65f * s));
    }});
    c.push_back({"town_hum_horses_near", 12.0f, steady(recipe::CITY, {1, 0, 0}, 0.5f)});
    c.push_back({"town_hum_horses_far", 12.0f, steady(recipe::CITY, {1, 0, 1}, 0.5f)});
    c.push_back({"town_hum_motors_near", 12.0f, steady(recipe::CITY, {1, 1, 0}, 0.5f)});
    c.push_back({"town_hum_motors_far", 12.0f, steady(recipe::CITY, {1, 1, 1}, 0.5f)});
    c.push_back({"industry_steam_near", 12.0f, steady(recipe::INDUSTRY, {1, 0, 0}, 0.5f)});
    c.push_back({"industry_modern_near", 12.0f, steady(recipe::INDUSTRY, {1, 1, 0}, 0.5f)});
    c.push_back({"industry_modern_far", 12.0f, steady(recipe::INDUSTRY, {1, 1, 1}, 0.5f)});
    c.push_back({"crickets", 8.0f, steady(recipe::CRICKETS, {1}, 0.3f)});
    c.push_back({"fire", 8.0f, steady(recipe::FIRE, {1}, 0.6f)});
    c.push_back({"traffic_battery_hydrogen", 10.0f, steady(recipe::TRAFFIC, {1, 1, 1, 0.6f}, 0.5f)});
    c.push_back({"station_chime", 4.0f, shots({{0.2f, shot(recipe::CHIME, {4}, 0.4f, 0, voiceFlag::TOWN)}})});
    c.push_back({"tram_gong", 4.0f, shots({{0.2f, shot(recipe::BELL, {0.8f}, 0.5f)}, {2.2f, shot(recipe::BELL, {0.5f}, 0.5f)}})});
    c.push_back({"horns_bulb_car_truck", 5.0f, shots({{0.2f, shot(recipe::HORN, {0, 0.4f}, 0.4f)}, {1.8f, shot(recipe::HORN, {1, 0.4f}, 0.4f)}, {3.4f, shot(recipe::HORN, {2, 0.5f}, 0.4f)}})});
    return c;
}

int sounds(const std::string& dir) {
    std::printf("%-28s %8s %8s %8s\n", "sound", "rms dB", "raw dB", "peak");
    for (const Clip& c : clips()) {
        std::vector<float> s = run(c.seconds, c.drive);
        // The level the gallery rule speaks of: limiter off, so the recipe's own.
        std::vector<float> raw = run(c.seconds, c.drive, 32, false);
        std::string path = dir + "/" + c.name + ".wav";
        if (!writeWav(path, s)) { std::fprintf(stderr, "can't write %s\n", path.c_str()); return 1; }
        std::printf("%-28s %8.1f %8.1f %8.2f\n", c.name, rmsDb(s), rmsDb(raw), peak(raw));
    }
    return 0;
}

int scene(const std::string& script, const std::string& out) {
    std::vector<Frame> frames = readScript(script);
    if (frames.empty()) { std::fprintf(stderr, "no frames in %s\n", script.c_str()); return 1; }
    std::vector<float> s = play(frames);
    if (!writeWav(out, s)) { std::fprintf(stderr, "can't write %s\n", out.c_str()); return 1; }
    std::printf("%s: %zu frames, %.1f s, rms %.1f dB, peak %.2f\n", out.c_str(), frames.size(), s.size() / 2 / kRate, rmsDb(s), peak(s));
    return 0;
}

/** A crowded moment: every kind of held sound at once, near the game's 24 held plus one-shots. */
int cpu() {
    Driver busy = [](float t, Scene& sc, Synth& synth) {
        int key = 1;
        for (int k = 0; k < 4; ++k) sc.entries[sc.count++] = held(key++, recipe::TRAIN, {1, float(k + 1), 0.7f}, 0.5f, -0.5f + 0.3f * k, 1.2f);
        for (int k = 0; k < 2; ++k) sc.entries[sc.count++] = held(key++, recipe::OUTBOARD, {1, 0.6f, float(k * 3)}, 0.4f);
        sc.entries[sc.count++] = held(key++, recipe::JET, {1, 0.8f, 0.8f}, 0.4f);
        sc.entries[sc.count++] = held(key++, recipe::HELI, {1, 0.6f}, 0.4f);
        int rest[] = {recipe::WIND, recipe::RAIN, recipe::SEA, recipe::BIRDS, recipe::CRICKETS, recipe::CITY, recipe::INDUSTRY, recipe::FIRE,
                      recipe::TRAFFIC, recipe::TRAM, recipe::SIREN, recipe::CROWD};
        for (int r : rest) sc.entries[sc.count++] = held(key++, r, {1, 0.5f, 0.5f, 0.5f}, 0.3f);
        if (std::fmod(t, 1.0f) < kStep) synth.pushEvent(shot(recipe::HORN, {1, 0.5f}, 0.3f));
    };
    auto start = std::chrono::steady_clock::now();
    const float seconds = 20.0f;
    std::vector<float> s = run(seconds, busy);
    double took = std::chrono::duration<double>(std::chrono::steady_clock::now() - start).count();
    std::printf("20 held and a horn a second: %.2f s of work for %.0f s of sound, %.2f%% of one core\n", took, seconds, 100.0 * took / seconds);
    return 0;
}

}  // namespace

int main(int argc, char** argv) {
    std::string mode = argc > 1 ? argv[1] : "";
    if (mode == "sounds" && argc > 2) return sounds(argv[2]);
    if (mode == "scene" && argc > 3) return scene(argv[2], argv[3]);
    if (mode == "cpu") return cpu();
    std::fprintf(stderr, "render sounds DIR | render scene SCRIPT OUT.wav | render cpu\n");
    return 2;
}
